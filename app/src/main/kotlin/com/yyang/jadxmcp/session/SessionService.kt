package com.yyang.jadxmcp.session

import com.yyang.jadxmcp.protocol.*
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport-neutral registry/router and GUI control port. Registry locks never cover backend work.
 * A canceled caller is released immediately; its physical lane is retained until backend exit.
 * Thus a non-cooperative JADX call cannot overlap its successor, even after a deadline.
 * Entry removal is a control-plane decision; endpoint retirement and owned-cache cleanup remain
 * outside the registry lock and may complete after the public close result.
 */
class SessionService(
    private val headlessFactory: HeadlessBackendFactory,
    private val policy: SessionPolicy = SessionPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
    idClock: Clock = Clock.systemDefaultZone()
) : GuiChannelPort, AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(SessionService::class.java)
    // Protects registry membership and Entry bookkeeping, never backend execution or disposal.
    private val guard = Any()
    private val sessionIds = ShortIdGenerator(idClock)
    private val entries = LinkedHashMap<String, Entry>()
    // Removed sessions still consume capacity until BOTH endpoint disposal and lane exit finish.
    private val retiring = LinkedHashMap<String, Entry>()
    private val identities = HashMap<String, String>()
    private data class Tombstone(val token: String?, val expires: Long)
    private val tombstones = LinkedHashMap<String, Tombstone>()
    private var closed = false
    private val creations = LinkedHashSet<Call>()
    private val reaper = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "session-control").apply { isDaemon = true }
    }

    private class Entry(
        val id: String, var name: String, var endpoint: BackendEndpoint,
        val created: Long, val identity: String? = null
    ) {
        // One physical execution at a time, including a cancelled call that ignores cancellation.
        val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
            ThreadFactory { task -> Thread(task, "session-$id").apply { isDaemon = true } })
        val calls = LinkedHashMap<String, Call>()
        var seen = created
        var endpointReleased = false
        var terminalAt: Long? = null
        var disconnectedLogged = false
    }
    private class Call(val original: CallContext) {
        val cancellation = CancellationSource()
        val context = original.copy(cancellation = CancellationSignal {
            cancellation.isCancelled() || original.cancellation.isCancelled()
        })
        val result = CompletableFuture<OperationResult>()
        // Result delivery is independent of task lifetime: abort must not free a running lane.
        private val resolved = AtomicBoolean()
        lateinit var task: Runnable
        fun succeed(value: OperationResult) = resolve(value, null)
        fun fail(error: Throwable) = resolve(null, error)
        private fun resolve(value: OperationResult?, error: Throwable?) {
            if (!resolved.compareAndSet(false, true)) return
            // Public CompletableFuture dependents may run inline. Never execute caller callbacks
            // while holding registry/control locks, or on the single maintenance thread.
            Thread.ofVirtual().name("session-result").start {
                if (error != null) result.completeExceptionally(error)
                else result.complete(requireNotNull(value))
            }
        }
    }

    init {
        reaper.scheduleWithFixedDelay({ sweep() }, policy.sweepMillis, policy.sweepMillis, TimeUnit.MILLISECONDS)
    }

    fun create(request: CreateSessionRequest, context: CallContext): SessionInfo {
        request.validate()
        context.checkActive(now())
        val call = Call(context)
        synchronized(guard) {
            ensureOpen()
            requireCapacity()
            creations += call
        }
        // Loading can itself be non-cooperative. The control timer releases its caller while the
        // reserved capacity remains charged until loading AND disposal have physically finished.
        Thread.ofVirtual().name("session-load").start {
            var endpoint: BackendEndpoint? = null
            var published = false
            try {
                call.context.checkActive(now())
                val loaded = headlessFactory.create(request, call.context)
                endpoint = loaded
                synchronized(guard) {
                    call.context.checkActive(now())
                    ensureOpen()
                    if (call.result.isCancelled) throw failure(ErrorCode.CANCELLED, "Loading caller cancelled")
                    if (loaded.capabilities.backend != BackendKind.HEADLESS)
                        throw failure(ErrorCode.INVALID_ARGUMENT, "Factory must return a headless backend")
                    (loaded as? ManagedBackendEndpoint)?.terminalError?.let { throw ProtocolException(it) }
                    val entry = Entry(sessionId(), Path.of(request.apkPath).fileName.toString(), loaded, now())
                    entries[entry.id] = entry
                    published = true
                    creations.remove(call)
                    log.info("Session opened sessionId={} backend=HEADLESS state=READY", entry.id)
                    call.succeed(info(entry))
                }
            } catch (e: Throwable) {
                if (e is ProtocolException && e.error.code in setOf(ErrorCode.CANCELLED, ErrorCode.CLOSED, ErrorCode.DEADLINE_EXCEEDED))
                    log.warn("Session open aborted backend=HEADLESS requestId={} code={}", context.requestId, e.error.code)
                else log.error("Session open failed backend=HEADLESS requestId={} type={}", context.requestId, e.javaClass.simpleName)
                call.fail(e)
            } finally {
                if (!published) {
                    endpoint?.let { runCatching { it.close() } }
                    synchronized(guard) { creations.remove(call) }
                }
            }
        }
        return await(call.result) as SessionInfo
    }

    fun list(): SessionListResult = synchronized(guard) {
        SessionListResult(entries.values.map(::info))
    }

    internal fun get(sessionId: String, context: CallContext? = null): SessionInfo = synchronized(guard) {
        val snapshot = info(entry(sessionId))
        context?.checkActive(now())
        context?.requireEpoch(snapshot.projectEpoch)
        snapshot
    }

    /** Management is direct, not submitted to the analysis lane. */
    fun execute(request: OperationRequest, context: CallContext): OperationResult {
        request.validate()
        context.checkActive(now())
        return when (request) {
            is CreateSessionRequest -> create(request, context)
            is ListSessionsRequest -> list()
            is CloseSessionRequest -> closeSession(request.sessionId, request.clearCache, context)
            is BackendRequest -> await(submit(request, context))
            else -> throw failure(ErrorCode.UNSUPPORTED, "Unsupported request")
        }
    }

    fun submit(request: BackendRequest, context: CallContext): CompletableFuture<OperationResult> {
        request.validate()
        context.checkActive(now())
        return synchronized(guard) {
            ensureOpen()
            val entry = entry(request.sessionId)
            val endpoint = entry.endpoint
            val snapshot = info(entry)
            context.requireEpoch(snapshot.projectEpoch)
            (endpoint as? ManagedBackendEndpoint)?.terminalError?.let { throw ProtocolException(it) }
            if (snapshot.state == SessionState.DISCONNECTED) throw failure(ErrorCode.DISCONNECTED, "GUI disconnected")
            if (!request.operation.allowsBackend(endpoint.capabilities.backend) ||
                endpoint.capabilities.operations.any { it.operation == request.operation && !it.supported })
                throw failure(ErrorCode.UNSUPPORTED, "Backend does not support ${request.operation.wireName}")
            if (entry.calls.containsKey(context.requestId)) throw failure(ErrorCode.CONFLICT, "Duplicate request ID")
            if (entry.calls.size >= policy.maxPendingPerSession) throw failure(ErrorCode.BUSY, "Session queue is full")
            val call = Call(context)
            call.task = Runnable { executeOnLane(entry, endpoint, request, call) }
            entry.calls[context.requestId] = call
            entry.lane.execute(call.task)
            call.result
        }
    }

    /** The captured endpoint may be replaced while executing; only its still-current result is publishable. */
    private fun executeOnLane(entry: Entry, endpoint: BackendEndpoint, request: BackendRequest, call: Call) {
        val started = System.nanoTime()
        log.debug("Backend started sessionId={} requestId={} operation={}", entry.id, call.original.requestId, request.operation.wireName)
        try {
            call.context.checkActive(now())
            (endpoint as? ManagedBackendEndpoint)?.terminalError?.let { throw ProtocolException(it) }
            val result = endpoint.execute(request, call.context)
            synchronized(guard) {
                call.context.checkActive(now())
                if (entries[entry.id] !== entry || entry.endpoint !== endpoint)
                    throw failure(ErrorCode.CLOSED, "Session endpoint replaced or closed")
                call.context.requireEpoch(info(entry).projectEpoch)
                if (!request.operation.resultType.isInstance(result))
                    throw failure(ErrorCode.INTERNAL, "Backend returned wrong result type")
                entry.seen = now()
                entry.calls.remove(call.original.requestId, call)
                call.succeed(result)
            }
        } catch (e: Throwable) {
            call.fail(e)
        } finally {
            log.debug("Backend completed sessionId={} requestId={} operation={} elapsedMs={}", entry.id,
                call.original.requestId, request.operation.wireName, (System.nanoTime() - started) / 1_000_000)
            // This is the physical exit point, not the earlier public cancellation/deadline result.
            synchronized(guard) { entry.calls.remove(call.original.requestId, call) }
        }
    }

    fun cancel(sessionId: String, requestId: String): Boolean = synchronized(guard) {
        val entry = entry(sessionId)
        val call = entry.calls[requestId] ?: return@synchronized false
        abort(entry, call, failure(ErrorCode.CANCELLED, "Call cancelled"))
        true
    }

    /** Explicit cache deletion acknowledges owned cleanup outside both registry lock and analysis lane. */
    fun closeSession(sessionId: String, clearCache: Boolean = false, context: CallContext? = null): CloseSessionResult {
        val endpoint = synchronized(guard) {
            val entry = entries[sessionId] ?: return CloseSessionResult(sessionId, false)
            context?.checkActive(now())
            // Heartbeat epoch updates and reconnects share guard: the fence and retirement are atomic.
            context?.requireEpoch(info(entry).projectEpoch)
            if (clearCache && entry.endpoint !is ManagedBackendEndpoint)
                throw failure(ErrorCode.UNSUPPORTED, "Backend does not own deletable generated caches")
            retire(entry, failure(ErrorCode.CLOSED, "Session closed"))
        }
        val cleanup = release(endpoint, clearCache)
        if (clearCache) {
            val waiting = context ?: CallContext(ShortIds.next(), 0, now() + 10_000)
            awaitCleanup(cleanup, waiting)
        }
        return CloseSessionResult(sessionId, true)
    }

    /** Giving up this wait never cancels disposal: the service still owns the retired endpoint. */
    private fun awaitCleanup(cleanup: CompletableFuture<Unit>, context: CallContext) {
        try {
            while (true) {
                context.checkActive(now())
                try {
                    cleanup.get(10, TimeUnit.MILLISECONDS)
                    return
                } catch (_: TimeoutException) {
                    // Poll so cancellation/deadline can release the waiter even if disposal hangs.
                }
            }
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure(ErrorCode.CANCELLED, "Cleanup waiter interrupted; owned cleanup continues")
        }
    }

    override fun register(registration: GuiRegistration): GuiLease = synchronized(guard) {
        ensureOpen()
        if (registration.instanceId.isBlank() || registration.name.isBlank() || registration.projectEpoch < 0 ||
            registration.capabilities.backend != BackendKind.GUI)
            throw failure(ErrorCode.INVALID_ARGUMENT, "Invalid GUI registration")
        val previous = identities[registration.instanceId]?.let(entries::get)
        if (previous != null) {
            val old = previous.endpoint as ReverseGuiEndpoint
            if (!old.disconnected()) throw failure(ErrorCode.CONFLICT, "GUI instance already registered")
            if (registration.projectEpoch < old.snapshot().second)
                throw failure(ErrorCode.CONFLICT, "Stale reconnect epoch")
            // Explicit registration may reconnect an unexpected disconnect, but old tokens never revive.
            previous.calls.values.toList().forEach { abort(previous, it, failure(ErrorCode.DISCONNECTED, "GUI reconnected")) }
            old.close()
            val replacement = ReverseGuiEndpoint(GuiLease(previous.id, leaseToken()), registration, policy, now)
            // A reconnect can bind a different project; publish its display name with its endpoint.
            previous.name = registration.name
            previous.endpoint = replacement
            previous.disconnectedLogged = false
            log.info("GUI reconnected sessionId={} backend=GUI", previous.id)
            return@synchronized replacement.lease
        }
        requireCapacity()
        val lease = GuiLease(sessionId(), leaseToken())
        val endpoint = ReverseGuiEndpoint(lease, registration, policy, now)
        val entry = Entry(lease.sessionId, registration.name, endpoint, now(), registration.instanceId)
        entries[entry.id] = entry
        identities[registration.instanceId] = entry.id
        log.info("GUI registered sessionId={} backend=GUI state={}", entry.id, info(entry).state)
        lease
    }

    override fun poll(lease: GuiLease, context: CallContext): PollResult {
        val endpoint = synchronized(guard) {
            val tombstone = tombstones[lease.sessionId]
            if (tombstone?.token == lease.token) return PollResult(PollStatus.CLOSED)
            channel(lease)
        }
        return endpoint.poll(context)
    }

    override fun complete(lease: GuiLease, completion: CommandCompletion) = channelFor(lease).complete(completion)
    override fun heartbeat(lease: GuiLease, projectEpoch: Long) = synchronized(guard) {
        channel(lease).heartbeat(projectEpoch)
    }
    override fun unregister(lease: GuiLease) {
        val endpoint = synchronized(guard) {
            if (tombstones[lease.sessionId]?.token == lease.token) return
            channel(lease)
            retire(entry(lease.sessionId), failure(ErrorCode.CLOSED, "GUI unregistered"))
        }
        release(endpoint)
    }

    /** Public deterministic maintenance hook; the service also schedules it automatically. */
    fun sweep() {
        val releases = synchronized(guard) {
            val expired = mutableListOf<BackendEndpoint>()
            creations.forEach { call ->
                try {
                    call.context.checkActive(now())
                    if (call.result.isCancelled) throw failure(ErrorCode.CANCELLED, "Loading caller cancelled")
                } catch (e: ProtocolException) {
                    call.cancellation.cancel()
                    call.fail(e)
                }
            }
            entries.values.toList().forEach { entry ->
                entry.calls.values.toList().forEach { call ->
                    try {
                        call.context.checkActive(now())
                        call.context.requireEpoch(info(entry).projectEpoch)
                        if (call.result.isCancelled) throw failure(ErrorCode.CANCELLED, "Future cancelled")
                    } catch (e: ProtocolException) { abort(entry, call, e) }
                }
                val endpoint = entry.endpoint
                val terminal = (endpoint as? ManagedBackendEndpoint)?.terminalError
                if (terminal != null) {
                    val terminalAt = entry.terminalAt
                    if (terminalAt == null) {
                        log.warn("Session terminal sessionId={} backend=HEADLESS code={}", entry.id, terminal.code)
                        entry.terminalAt = now()
                        entry.calls.values.toList().forEach { abort(entry, it, ProtocolException(terminal)) }
                        entry.lane.shutdown()
                        expired += endpoint
                    } else if (now() - terminalAt >= policy.disconnectedRetentionMillis) {
                        expired += retire(entry, ProtocolException(terminal), "terminal-retention-expired")
                    }
                    // Retain a bounded terminal record briefly so explicit close(clearCache=true)
                    // can still remove owned caches after cancellation/crash, without reviving it.
                }
                else if (endpoint is ReverseGuiEndpoint) {
                    val reap = endpoint.reap()
                    if (endpoint.disconnected() && !entry.disconnectedLogged) {
                        entry.disconnectedLogged = true
                        log.info("GUI disconnected sessionId={} reason=lease-timeout", entry.id)
                    }
                    if (reap) expired += retire(entry, failure(ErrorCode.DISCONNECTED, "Disconnected GUI expired"), "lease-retention-expired")
                }
            }
            tombstones.entries.removeIf { it.value.expires <= now() }
            forgetReleasedEntries()
            expired
        }
        releases.forEach { release(it) }
    }

    override fun close() {
        val endpoints = synchronized(guard) {
            if (closed) return
            closed = true
            creations.forEach {
                it.cancellation.cancel()
                it.fail(failure(ErrorCode.CLOSED, "Service closed"))
            }
            entries.values.toList().map { retire(it, failure(ErrorCode.CLOSED, "Service closed"), "service-shutdown") }
        }
        reaper.shutdownNow()
        endpoints.forEach { release(it) }
    }

    private fun info(entry: Entry): SessionInfo {
        val endpoint = entry.endpoint
        val guiSnapshot = (endpoint as? ReverseGuiEndpoint)?.snapshot()
        val state = guiSnapshot?.first ?: when {
            (endpoint as? ManagedBackendEndpoint)?.terminalError != null -> SessionState.CLOSED
            entry.calls.isEmpty() -> SessionState.READY
            else -> SessionState.BUSY
        }
        val projectEpoch = guiSnapshot?.second ?: 0
        val lastSeen = guiSnapshot?.third ?: entry.seen
        return SessionInfo(entry.id, endpoint.capabilities.backend, entry.name, state,
            projectEpoch, entry.created, lastSeen, endpoint.capabilities)
    }

    private fun abort(entry: Entry, call: Call, error: ProtocolException) {
        call.cancellation.cancel()
        call.fail(error)
        // Removing queued work is safe. Running work retains the physical lane until its finally block.
        if (entry.lane.remove(call.task)) entry.calls.remove(call.original.requestId, call)
    }

    /** Under guard: revoke routing immediately; the caller must release the returned endpoint outside guard. */
    private fun retire(entry: Entry, reason: ProtocolException, event: String = "explicit-close"): BackendEndpoint {
        entries.remove(entry.id)
        log.info("{} closed sessionId={} backend={} reason={} code={}",
            if (entry.endpoint.capabilities.backend == BackendKind.GUI) "GUI" else "Session", entry.id,
            entry.endpoint.capabilities.backend, event, reason.error.code)
        entry.identity?.let { identities.remove(it, entry.id) }
        entry.calls.values.toList().forEach { abort(entry, it, reason) }
        entry.lane.shutdown()
        val gui = entry.endpoint as? ReverseGuiEndpoint
        gui?.close() // Internal, nonblocking; wakes poll and remote completion waiters immediately.
        entry.endpointReleased = gui != null
        retiring[entry.id] = entry
        tombstones[entry.id] = Tombstone(gui?.lease?.token, now() + policy.tombstoneMillis)
        while (tombstones.size > policy.maxTombstones) tombstones.remove(tombstones.keys.first())
        return entry.endpoint
    }

    private fun channelFor(lease: GuiLease) = synchronized(guard) { channel(lease) }
    private fun channel(lease: GuiLease): ReverseGuiEndpoint {
        val endpoint = entry(lease.sessionId).endpoint as? ReverseGuiEndpoint
            ?: throw failure(ErrorCode.INVALID_ARGUMENT, "Not a GUI session")
        endpoint.authenticate(lease)
        return endpoint
    }
    private fun entry(id: String): Entry = entries[id] ?: throw failure(
        if (tombstones.containsKey(id)) ErrorCode.CLOSED else ErrorCode.NOT_FOUND, "Unknown or closed session")
    private fun ensureOpen() { if (closed) throw failure(ErrorCode.CLOSED, "Service closed") }

    /** Under guard: endpoint close alone is insufficient if non-cooperative work still occupies its lane. */
    private fun forgetReleasedEntries() {
        retiring.entries.removeIf { it.value.endpointReleased && it.value.lane.isTerminated }
    }

    private fun requireCapacity() {
        forgetReleasedEntries()
        if (entries.size + retiring.size + creations.size >= policy.maxSessions)
            throw failure(ErrorCode.BUSY, "Session capacity reached (including draining backends)")
    }
    // Under guard: retained/retiring records must not be shadowed when the calendar repeats.
    private fun sessionId() = sessionIds.next { it in entries || it in retiring || it in tombstones }
    private fun leaseToken() = UUID.randomUUID().toString()
    private fun release(endpoint: BackendEndpoint, clearCache: Boolean = false): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        // Backend close may block in native code. Never run it on control-plane threads.
        if (endpoint is ReverseGuiEndpoint) return CompletableFuture.completedFuture(Unit)
        Thread.ofVirtual().name("session-release").start {
            try {
                if (endpoint is ManagedBackendEndpoint) endpoint.close(clearCache) else endpoint.close()
                synchronized(guard) {
                    retiring.values.filter { it.endpoint === endpoint }.forEach { it.endpointReleased = true }
                }
                completion.complete(Unit)
            } catch (e: Throwable) {
                // Ordinary close does not await this future; disposal failures still need a safe signal.
                log.error("Backend release failed backend={} type={}", endpoint.capabilities.backend, e.javaClass.simpleName)
                completion.completeExceptionally(e)
            }
        }
        return completion
    }
    private fun await(result: CompletableFuture<OperationResult>): OperationResult = try {
        result.get()
    } catch (e: ExecutionException) { throw (e.cause ?: e) }
    catch (e: InterruptedException) {
        result.cancel(false)
        Thread.currentThread().interrupt()
        throw failure(ErrorCode.CANCELLED, "Caller interrupted")
    }
}
