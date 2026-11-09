package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*

enum class BridgeState { DISABLED, CONNECTING, CONNECTED, RECONNECTING, CLOSED }

/**
 * Transport is exactly the P1 GuiChannelPort, not a second wire protocol. An HTTP adapter must
 * bound every I/O call, honor poll context cancellation/deadline, and fence complete/unregister
 * by the supplied lease token. No transport or endpoint call is made on the caller/EDT thread.
 *
 * The endpoint is BORROWED: this client never closes it or the host decompiler. The caller must
 * fence project access using CallContext and updateProject before exposing a replacement project.
 * Runtime ownership, unlike endpoint ownership, is transferred to this client.
 */
class GuiBridgeClient(
    private val transport: GuiChannelPort,
    private val endpoint: BackendEndpoint,
    private val instanceId: String,
    private val name: String,
    initialProjectEpoch: Long = 0,
    private val runtime: BridgeRuntime = ExecutorBridgeRuntime(),
    private val clock: BridgeClock = BridgeClock(System::currentTimeMillis),
    private val policy: BridgePolicy = BridgePolicy(),
    private val observer: BridgeObserver = object : BridgeObserver {}
) : AutoCloseable {
    private class Generation(val epoch: Long, val activation: Any, val number: Long) {
        val cancellation = CancellationSource()
        var lease: GuiLease? = null
        val seen = HashSet<String>()
    }

    private val lock = Any()
    private var current: Generation? = null
    private var epoch = initialProjectEpoch
    private var backoff = policy.initialBackoffMillis
    private var executionOutstanding = false
    private var generationNumber = 0L
    @Volatile var state: BridgeState = BridgeState.DISABLED
        private set

    private fun transition(value: BridgeState, reason: String) {
        state = value
        observer.transition(value, epoch, generationNumber, reason)
    }

    init {
        require(instanceId.isNotBlank())
        require(initialProjectEpoch >= 0)
        require(endpoint.capabilities.backend == BackendKind.GUI)
    }

    fun enable() = synchronized(lock) {
        check(state != BridgeState.CLOSED) { "Bridge has been unloaded" }
        if (state != BridgeState.DISABLED) return@synchronized
        backoff = policy.initialBackoffMillis
        connect(0, false)
    }

    /** Explicit disable is sticky: only enable() may leave DISABLED. */
    fun disable() = synchronized(lock) {
        if (state == BridgeState.CLOSED) return@synchronized
        retire()
        transition(BridgeState.DISABLED, "Actively disconnected / project unavailable")
    }

    fun updateProject(projectEpoch: Long) = synchronized(lock) {
        require(projectEpoch >= epoch) { "Project epoch must not move backwards" }
        if (projectEpoch == epoch) return@synchronized
        check(state != BridgeState.CLOSED) { "Bridge has been unloaded" }
        epoch = projectEpoch
        retire()
        if (state != BridgeState.DISABLED) connect(0, false)
        else transition(BridgeState.DISABLED, "Project changed / waiting for project")
    }

    override fun close() = synchronized(lock) {
        if (state == BridgeState.CLOSED) return@synchronized
        retire()
        transition(BridgeState.CLOSED, "Plugin unloaded")
        runtime.close()
    }

    // All generation transitions are under lock; queued work checks identity, never just epoch.
    private fun connect(delay: Long, retry: Boolean, activation: Any = Any()) {
        val generation = Generation(epoch, activation, ++generationNumber)
        current = generation
        transition(if (retry) BridgeState.RECONNECTING else BridgeState.CONNECTING,
            if (retry) "Retry in ${delay}ms" else "Registering")
        runtime.schedule(BridgeLane.CHANNEL, delay) { register(generation) }
    }

    private fun active(generation: Generation): Boolean = synchronized(lock) {
        current === generation && !generation.cancellation.isCancelled()
    }

    private fun retire() {
        val old = current ?: return
        current = null
        old.cancellation.cancel()
        old.lease?.let { lease -> cleanup(lease) }
    }

    private fun cleanup(lease: GuiLease) {
        runtime.schedule(BridgeLane.CLEANUP) {
            try { transport.unregister(lease) } catch (_: Exception) { /* best effort, no resurrection */ }
        }
    }

    private fun disconnected(generation: Generation) = synchronized(lock) {
        if (!active(generation)) return@synchronized
        retire()
        val delay = backoff
        backoff = if (backoff > policy.maxBackoffMillis / 2) policy.maxBackoffMillis else backoff * 2
        // Only registration is retried. An old COMMAND/COMPLETE is never copied to this lease:
        // after a socket failure, whether a native edit or its report completed can be unknowable.
        connect(delay, true, generation.activation)
    }

    private fun serverClosed(generation: Generation) = synchronized(lock) {
        // CLOSED from an in-flight old heartbeat must also cancel its automatic successor.
        // Explicit enable/project changes create a different activation and are not affected.
        if (current?.activation !== generation.activation) return@synchronized
        retire()
        transition(BridgeState.DISABLED, "Actively closed by master")
    }

    private fun transportFailed(generation: Generation, failure: Exception) {
        val error = (failure as? ProtocolException)?.error
        when {
            error?.code == ErrorCode.CLOSED -> serverClosed(generation)
            // A restarted master has no old session/token. Only an established lease may
            // recover NOT_FOUND; registration/authentication/conflict errors remain terminal.
            error?.code == ErrorCode.NOT_FOUND && generation.lease != null -> disconnected(generation)
            failure is java.io.IOException || error?.code == ErrorCode.DISCONNECTED || error?.code == ErrorCode.DEADLINE_EXCEEDED ->
                disconnected(generation)
            else -> disableGeneration(generation, "Error: ${error?.code?.name ?: "INVALID_DATA"}")
        }
    }

    private fun disableGeneration(generation: Generation, reason: String = "Error: replay protection") = synchronized(lock) {
        // Authentication/lease conflicts, exhausted replay tracking and invalid data are not outages.
        if (active(generation)) { retire(); transition(BridgeState.DISABLED, reason) }
    }

    private fun register(generation: Generation) {
        if (!active(generation)) return
        try {
            val lease = transport.register(GuiRegistration(instanceId, name, generation.epoch, endpoint.capabilities))
            require(lease.sessionId.isNotBlank() && lease.token.isNotBlank()) { "Invalid GUI lease" }
            val accepted = synchronized(lock) {
                if (!active(generation)) false else {
                    generation.lease = lease
                    transition(BridgeState.CONNECTED, "Connected")
                    runtime.schedule(BridgeLane.HEARTBEAT) { heartbeat(generation, lease) }
                    runtime.schedule(BridgeLane.CHANNEL) { poll(generation, lease) }
                    true
                }
            }
            // Registration can finish after unload, when the cleanup executor is already shut down.
            // This is already a transport worker, so release directly without touching the EDT.
            if (!accepted) try { transport.unregister(lease) } catch (_: Exception) { }
        } catch (e: Exception) { transportFailed(generation, e) }
    }

    private fun heartbeat(generation: Generation, lease: GuiLease) {
        if (!active(generation)) return
        try {
            transport.heartbeat(lease, generation.epoch)
            synchronized(lock) {
                if (!active(generation)) return
                backoff = policy.initialBackoffMillis
                runtime.schedule(BridgeLane.HEARTBEAT, policy.heartbeatMillis) { heartbeat(generation, lease) }
            }
        } catch (e: Exception) { transportFailed(generation, e) }
    }

    private fun poll(generation: Generation, lease: GuiLease) {
        if (!active(generation)) return
        synchronized(lock) {
            if (!active(generation)) return
            if (executionOutstanding) {
                // A cancelled JADX call may ignore interruption. Do not accumulate commands behind it.
                runtime.schedule(BridgeLane.CHANNEL, policy.idlePollMillis) { poll(generation, lease) }
                return
            }
        }
        if (generation.seen.size >= policy.maxCommandsPerLease) {
            disableGeneration(generation) // Bound replay memory without silently forgetting IDs or re-registering.
            return
        }
        try {
            val context = CallContext("poll-${lease.sessionId}", generation.epoch,
                clock.nowMillis() + policy.pollTimeoutMillis, generation.cancellation)
            val response = transport.poll(lease, context)
            if (response.status == PollStatus.CLOSED) {
                serverClosed(generation)
                return
            }
            if (!active(generation)) return
            when (response.status) {
                PollStatus.CLOSED -> Unit // handled before the generation check, including late replies
                PollStatus.IDLE -> runtime.schedule(BridgeLane.CHANNEL, policy.idlePollMillis) { poll(generation, lease) }
                PollStatus.COMMAND -> {
                    val frame = requireNotNull(response.command) { "COMMAND without frame" }
                    // Duplicate IDs must never cause a repeated GUI edit or other side effect.
                    if (!generation.seen.add(frame.context.requestId)) {
                        disableGeneration(generation)
                    } else synchronized(lock) {
                        if (!active(generation)) return
                        executionOutstanding = true
                        observer.received(generation.number)
                        runtime.schedule(BridgeLane.EXECUTION) {
                            try { execute(generation, lease, frame) }
                            finally { synchronized(lock) { executionOutstanding = false } }
                        }
                    }
                }
            }
        } catch (e: Exception) { transportFailed(generation, e) }
    }

    private fun execute(generation: Generation, lease: GuiLease, frame: RequestFrame) {
        if (!active(generation)) return
        // Cooperative cancellation prevents future admission/reporting; it cannot undo a native
        // JADX call that already mutated the borrowed project or ignores thread interruption.
        val context = frame.context.copy(cancellation = CancellationSignal {
            generation.cancellation.isCancelled() || frame.context.cancellation.isCancelled()
        })
        val completion = try {
            context.checkActive(clock.nowMillis())
            context.requireEpoch(generation.epoch)
            val request = frame.request as? BackendRequest ?: throw ProtocolException(
                ProtocolError(ErrorCode.INVALID_ARGUMENT, "GUI command must be a backend request"))
            if (request.sessionId != lease.sessionId) throw ProtocolException(
                ProtocolError(ErrorCode.CONFLICT, "GUI session mismatch"))
            request.validate()
            if (endpoint.capabilities.operations.none { it.operation == request.operation && it.supported }) {
                throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Operation is not supported by this GUI endpoint"))
            }
            val result = endpoint.execute(request, context)
            context.checkActive(clock.nowMillis())
            if (!request.operation.resultType.isInstance(result)) throw ProtocolException(
                ProtocolError(ErrorCode.INTERNAL, "Endpoint returned a mismatched result type"))
            CommandCompletion(context.requestId, context.projectEpoch, request.operation, result = result)
        } catch (e: Exception) {
            CommandCompletion(context.requestId, context.projectEpoch, frame.request.operation,
                error = (e as? ProtocolException)?.error ?: ProtocolError(ErrorCode.INTERNAL, "GUI execution failed"))
        }
        // Old work can finish after a project swap or reconnect, but must never report to a new lease.
        synchronized(lock) {
            if (!active(generation)) return
            runtime.schedule(BridgeLane.CHANNEL) { report(generation, lease, completion) }
        }
    }

    private fun report(generation: Generation, lease: GuiLease, completion: CommandCompletion) {
        if (!active(generation)) return
        try {
            transport.complete(lease, completion)
            if (active(generation)) runtime.schedule(BridgeLane.CHANNEL) { poll(generation, lease) }
        } catch (e: Exception) {
            // Delivery is ambiguous: never replay execute/complete locally across a lease boundary.
            transportFailed(generation, e)
        }
    }
}
