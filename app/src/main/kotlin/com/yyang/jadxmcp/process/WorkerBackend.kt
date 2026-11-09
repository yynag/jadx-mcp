package com.yyang.jadxmcp.process

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.HeadlessBackendFactory
import com.yyang.jadxmcp.session.ManagedBackendEndpoint
import com.yyang.jadxmcp.worker.OwnedWorkerCache
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** One authenticated loopback child JVM per factory call; no global analysis lock. */
class WorkerBackendFactory(
    private val config: WorkerProcessConfig,
    private val launcher: WorkerLauncher = JvmWorkerLauncher(),
    private val transportFactory: () -> WorkerTransport = { HttpWorkerTransport() }
) : HeadlessBackendFactory, AutoCloseable {
    private val guard = Any()
    private val workers = LinkedHashSet<WorkerBackend>()
    private var closed = false
    private val shutdown = Thread({ close() }, "worker-factory-shutdown")
    init { Runtime.getRuntime().addShutdownHook(shutdown) }

    override fun create(request: CreateSessionRequest, context: CallContext): WorkerBackend {
        request.validate()
        context.checkActive()
        context.requireEpoch(0)
        synchronized(guard) { if (closed) throw workerError(ErrorCode.CLOSED, "Worker factory closed") }
        val input = try { java.nio.file.Path.of(request.apkPath).toAbsolutePath().normalize().toRealPath() }
        catch (e: java.nio.file.InvalidPathException) { throw workerError(ErrorCode.INVALID_ARGUMENT, "Invalid input path: ${e.message}") }
        catch (e: java.io.IOException) { throw workerError(ErrorCode.NOT_FOUND, "Input is not a readable file: ${request.apkPath}: ${e.message}") }
        if (!Files.isRegularFile(input) || !Files.isReadable(input))
            throw workerError(ErrorCode.NOT_FOUND, "Input is not a readable file: $input")
        val cache = try { OwnedWorkerCache.forInput(input) }
        catch (e: Exception) { throw workerError(ErrorCode.PERSISTENCE_FAILED, "Cannot create input-adjacent cache for $input: ${e.message}") }
        var process: WorkerProcess? = null
        var transport: WorkerTransport? = null
        var backend: WorkerBackend? = null
        try {
            val port = ServerSocket().use {
                it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                it.localPort
            }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
            val jvmTemp = Files.createDirectories(cache.directory.resolve("jvm-temp")).toAbsolutePath()
            // Resolve against the Master's cwd before giving the child its own working directory.
            // Preserve classpath entries (including spaces/wildcards), not shell-quoted strings.
            val classpath = config.classpath.split(java.io.File.pathSeparator).joinToString(java.io.File.pathSeparator) {
                java.nio.file.Path.of(it.ifEmpty { "." }).toAbsolutePath().normalize().toString()
            }
            val spec = WorkerLaunchSpec(listOf(config.javaExecutable, "-Xmx${request.maxHeap ?: config.defaultMaxHeap}",
                "-Djava.io.tmpdir=$jvmTemp",
                "-Djadx.mcp.logLevel=${config.logLevel.name}",
                "-cp", classpath, "com.yyang.jadxmcp.worker.WorkerMain"), mapOf(
                "JADX_MCP_WORKER_PORT" to port.toString(), "JADX_MCP_WORKER_TOKEN" to token,
                "JADX_MCP_WORKER_FACTORY" to config.factoryClassName,
                "JADX_MCP_WORKER_CACHE" to cache.directory.toAbsolutePath().toString(),
                "JADX_MCP_PARENT_PID" to ProcessHandle.current().pid().toString()), cache.directory)
            process = launcher.launch(spec)
            transport = transportFactory()
            val worker = WorkerBackend(process, transport, URI("http://127.0.0.1:$port/rpc"), token,
                cache, config.shutdownGraceMillis) { worker -> synchronized(guard) { workers.remove(worker) } }
            backend = worker
            synchronized(guard) {
                if (closed) throw workerError(ErrorCode.CLOSED, "Worker factory closed")
                workers += worker
            }
            val startup = context.copy(deadlineEpochMillis = minOf(context.deadlineEpochMillis,
                System.currentTimeMillis() + config.startupTimeoutMillis))
            worker.initialize(request.copy(apkPath = input.toString()), startup)
            return worker
        } catch (e: Throwable) {
            if (backend != null) runCatching { backend.close(clearCache = true) }
            else {
                process?.let { runCatching { it.terminate(config.shutdownGraceMillis) } }
                (transport as? AutoCloseable)?.let { runCatching { it.close() } }
                if (process?.isAlive != true) runCatching { cache.clear() }
            }
            throw if (e is ProtocolException) e else backend?.startupFailure(e)
                ?: workerError(ErrorCode.INTERNAL, "WORKER_START_FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun close() {
        val snapshot = synchronized(guard) {
            if (closed && workers.isEmpty()) return
            closed = true
            workers.toList()
        }
        // Independent shutdowns: one hung child must not prevent destruction of its siblings.
        val failures = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            snapshot.map { worker -> executor.submit<Throwable?> { runCatching { worker.close() }.exceptionOrNull() } }
                .mapNotNull { it.get() }
        }
        if (failures.isEmpty() && Thread.currentThread() !== shutdown)
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        if (failures.isNotEmpty()) throw workerError(ErrorCode.INTERNAL,
            "Worker shutdown failed for ${failures.size} child process(es): ${failures.first().message}")
    }
}

/**
 * One single-flight analysis endpoint with an independent close path.
 * A deadline ends the caller's wait, not necessarily JADX execution; cancellation therefore
 * makes this endpoint terminal and terminates the child instead of allowing a successor call.
 */
class WorkerBackend internal constructor(
    private val process: WorkerProcess,
    private val transport: WorkerTransport,
    private val address: URI,
    private val token: String,
    private val cache: OwnedWorkerCache,
    private val shutdownGraceMillis: Long,
    private val onClosed: (WorkerBackend) -> Unit
) : ManagedBackendEndpoint {
    // closed rejects new work immediately; processDisposed means termination actually succeeded.
    // A failed disposal remains retryable, including a later explicit owned-cache cleanup.
    private val closed = AtomicBoolean()
    private val executing = AtomicBoolean()
    private val closeGuard = Any()
    private var processDisposed = false
    @Volatile private var workerId = ""
    @Volatile private var boundSession: String? = null
    private var advertised = BackendCapabilities(BackendKind.HEADLESS)
    @Volatile private var terminal: ProtocolError? = null
    override val capabilities get() = advertised
    override val terminalError: ProtocolError? get() = terminal ?: when {
        !process.isAlive -> exitFailure().error
        closed.get() -> ProtocolError(ErrorCode.CLOSED, "Worker closed")
        else -> null
    }
    val isAlive get() = process.isAlive
    val generatedCacheDirectory get() = cache.directory

    internal fun initialize(request: CreateSessionRequest, context: CallContext) {
        while (true) {
            context.checkActive()
            ensureAlive()
            try {
                val completion = exchange(RequestFrame(request, context), context)
                val info = completion.result as? SessionInfo ?: throw workerError(ErrorCode.INTERNAL, "Invalid worker handshake")
                if (info.state != SessionState.READY || info.backend != BackendKind.HEADLESS || info.projectEpoch != 0L ||
                    info.sessionId.isBlank() || info.capabilities?.backend != BackendKind.HEADLESS)
                    throw workerError(ErrorCode.INTERNAL, "Worker handshake was not READY/headless")
                workerId = info.sessionId
                advertised = requireNotNull(info.capabilities)
                return
            } catch (e: Throwable) {
                // Port selection is not a readiness promise. Only a typed READY after loading is.
                // A raced bind failure exits the child; authentication prevents accepting an impostor.
                if (generateSequence(e) { it.cause }.none { it is ConnectException }) throw e
                context.checkActive()
                ensureAlive()
                Thread.sleep(10)
            }
        }
    }

    override fun execute(request: BackendRequest, context: CallContext): OperationResult {
        request.validate()
        context.checkActive()
        context.requireEpoch(0)
        if (closed.get()) throw workerError(ErrorCode.CLOSED, "Worker closed")
        if (!executing.compareAndSet(false, true)) throw workerError(ErrorCode.BUSY, "Worker already executing")
        try {
            if (boundSession == null) boundSession = request.sessionId
            if (boundSession != request.sessionId) throw workerError(ErrorCode.CONFLICT, "Worker belongs to another session")
            if (!request.operation.allowsBackend(BackendKind.HEADLESS))
                throw workerError(ErrorCode.UNSUPPORTED, "Operation is GUI-only")
            return requireNotNull(exchange(RequestFrame(request, context), context).result)
        } catch (e: Throwable) {
            failExecution(e)
        } finally { executing.set(false) }
    }

    private fun failExecution(cause: Throwable): Nothing {
        val protocolError = (cause as? ProtocolException)?.error
        when {
            protocolError != null && (protocolError.code == ErrorCode.CANCELLED || protocolError.code == ErrorCode.DEADLINE_EXCEEDED) -> {
                terminal = ProtocolError(ErrorCode.DISCONNECTED,
                    "Worker terminal after ${protocolError.code}: ${protocolError.message}")
                // Cancelling the transport future does not acknowledge remote cancellation.
                // Skip graceful RPC: a non-cooperative analysis may never service that request.
                // Preserve the caller's cancellation error even if termination itself fails;
                // closed/terminal still prevent reuse and disposal can be retried by close().
                runCatching { dispose(clearCache = false, graceful = false) }
            }
            protocolError == null || !process.isAlive -> {
                val exit = exitFailure()
                terminal = exit.error
                runCatching { close() }
                throw exit
            }
        }
        throw cause
    }

    private fun exchange(frame: RequestFrame, context: CallContext): CommandCompletion {
        ensureAlive()
        val response = transport.exchange(address, token, frame)
        try {
            while (true) {
                context.checkActive()
                if (closed.get()) throw workerError(ErrorCode.CLOSED, "Worker closed")
                ensureAlive()
                val completion = try { response.get(10, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { continue }
                context.checkActive()
                validateCompletion(frame, completion)
                return completion
            }
        } catch (e: ExecutionException) {
            context.checkActive()
            ensureAlive()
            throw (e.cause ?: e)
        }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw workerError(ErrorCode.CANCELLED, "Worker call interrupted")
        } finally { if (!response.isDone) response.cancel(true) }
    }

    private fun validateCompletion(frame: RequestFrame, completion: CommandCompletion) {
        if (completion.requestId != frame.context.requestId || completion.projectEpoch != frame.context.projectEpoch ||
            completion.operation != frame.request.operation)
            throw workerError(ErrorCode.CONFLICT, "Mismatched worker completion")
        completion.error?.let { throw ProtocolException(it) }
        if (!frame.request.operation.resultType.isInstance(completion.result))
            throw workerError(ErrorCode.INTERNAL, "Wrong worker result type")
    }

    override fun close() = close(clearCache = false)

    /** Extension for application wiring; BackendEndpoint.close itself has no clearCache argument. */
    override fun close(clearCache: Boolean) = dispose(clearCache, graceful = true)

    private fun dispose(clearCache: Boolean, graceful: Boolean) = synchronized(closeGuard) {
        closed.set(true)
        if (!processDisposed) {
            try {
                if (graceful && process.isAlive && workerId.isNotEmpty()) requestGracefulClose(clearCache)
            } finally {
                process.terminate(if (graceful) shutdownGraceMillis else 0)
                if (process.isAlive) throw workerError(ErrorCode.INTERNAL, "Worker survived termination")
                (transport as? AutoCloseable)?.let { runCatching { it.close() } }
                processDisposed = true
                onClosed(this)
            }
        }
        // The child must no longer write these files. Keep cleanup outside the one-time disposal
        // block so close(true) can retry a failed deletion, or follow an earlier close(false).
        if (clearCache) try { cache.clear() } catch (e: Exception) {
            throw workerError(ErrorCode.PERSISTENCE_FAILED, "Owned generated-cache cleanup failed: ${e.message}")
        }
    }

    /** Best-effort persistence opportunity only; the finally block in dispose always enforces exit. */
    private fun requestGracefulClose(clearCache: Boolean) {
        val context = CallContext(UUID.randomUUID().toString(), 0, System.currentTimeMillis() + shutdownGraceMillis)
        val request = CloseSessionRequest(boundSession ?: workerId, clearCache)
        runCatching {
            transport.exchange(address, token, RequestFrame(request, context)).get(shutdownGraceMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun ensureAlive() { if (!process.isAlive) throw exitFailure() }
    internal fun startupFailure(cause: Throwable): ProtocolException {
        val failure = exitFailure()
        return workerError(failure.error.code, "${failure.message}; startup: ${cause.javaClass.simpleName}: ${cause.message}")
    }
    private fun exitFailure(): ProtocolException {
        val diagnostics = process.diagnostics.takeLast(4_096).replace(token, "[redacted]")
        val reason = when {
            process.exitCode == 73 || diagnostics.contains("WORKER_OOM") -> "WORKER_OOM: increase maxHeap or close unused sessions"
            process.exitCode == 137 || process.exitCode == 9 -> "WORKER_KILLED: possible OS memory pressure, not confirmed OOM"
            process.isAlive -> "WORKER_TRANSPORT_FAILED"
            else -> "WORKER_EXIT"
        }
        return workerError(ErrorCode.DISCONNECTED, "$reason (exit=${process.exitCode}): $diagnostics")
    }
}

internal fun workerError(code: ErrorCode, message: String) = ProtocolException(ProtocolError(code, message))
