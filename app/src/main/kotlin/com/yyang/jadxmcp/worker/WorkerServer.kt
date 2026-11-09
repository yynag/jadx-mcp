package com.yyang.jadxmcp.worker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yyang.jadxmcp.protocol.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Child-side typed RPC. HTTP is internal worker transport, not an MCP/GUI adapter.
 * Initialization publishes the endpoint only after validation; an unaccepted endpoint is always
 * closed, while close() fences new work without waiting for the analysis semaphore.
 */
class WorkerServer(
    port: Int,
    private val token: String,
    private val factory: WorkerEndpointFactory,
    private val generatedCache: Path
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
    private val threads = Executors.newVirtualThreadPerTaskExecutor()
    private val codec = ProtocolCodec()
    private val guard = Any()
    private val lane = Semaphore(1)
    private val closing = AtomicBoolean()
    private val stopped = CountDownLatch(1)
    private val sessionId = UUID.randomUUID().toString()
    private var endpoint: BackendEndpoint? = null
    private var initialization: CreateSessionRequest? = null
    private var loadError: ProtocolError? = null
    private var initializing = false
    private var boundSession: String? = null
    private val createdAt = System.currentTimeMillis()
    val address: URI get() = URI("http://127.0.0.1:${server.address.port}/rpc")

    init {
        require(token.length >= 32) { "Worker token must have at least 32 characters" }
        server.executor = threads
        server.createContext("/rpc", ::handle)
    }

    fun start() { server.start() }
    fun awaitStopped() { stopped.await() }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val auth = exchange.requestHeaders.getFirst("Authorization") ?: ""
            if (!MessageDigest.isEqual(auth.toByteArray(StandardCharsets.UTF_8), "Bearer $token".toByteArray(StandardCharsets.UTF_8))) {
                reply(exchange, 401, "Unauthorized")
                return
            }
            if (exchange.requestMethod != "POST" || exchange.requestURI.path != "/rpc") {
                reply(exchange, 404, "Not found")
                return
            }
            val bytes = exchange.requestBody.readNBytes(MAX_REQUEST_BYTES + 1)
            if (bytes.size > MAX_REQUEST_BYTES) { reply(exchange, 413, "Request too large"); return }
            val frame = try { codec.decodeFrame(bytes.toString(StandardCharsets.UTF_8)) } catch (_: ProtocolException) {
                reply(exchange, 400, "Invalid typed frame")
                return
            }
            var closeAfterReply = false
            val completion = try {
                frame.context.checkActive()
                frame.context.requireEpoch(0)
                val result = when (val request = frame.request) {
                    is CreateSessionRequest -> initialize(request, frame.context)
                    is CloseSessionRequest -> {
                        closing.set(true) // Never waits for the analysis semaphore.
                        closeAfterReply = true
                        CloseSessionResult(request.sessionId, true)
                    }
                    is BackendRequest -> execute(request, frame.context)
                    else -> throw fail(ErrorCode.UNSUPPORTED, "Worker does not manage session lists")
                }
                CommandCompletion(frame.context.requestId, frame.context.projectEpoch, frame.request.operation, result)
            } catch (e: OutOfMemoryError) {
                // Dedicated marker/exit code, rather than guessing OOM from arbitrary SIGKILL.
                System.err.println("WORKER_OOM")
                Runtime.getRuntime().halt(73)
                throw e
            } catch (e: ProtocolException) {
                CommandCompletion(frame.context.requestId, frame.context.projectEpoch, frame.request.operation, error = e.error)
            } catch (e: Throwable) {
                CommandCompletion(frame.context.requestId, frame.context.projectEpoch, frame.request.operation,
                    error = ProtocolError(ErrorCode.INTERNAL, "WORKER_CALL_FAILED: ${e.javaClass.simpleName}: ${e.message}"))
            }
            try { reply(exchange, 200, codec.encodeCompletion(completion)) }
            finally { if (closeAfterReply) Thread.ofVirtual().name("worker-close").start { close() } }
        }
    }

    private fun initialize(request: CreateSessionRequest, context: CallContext): SessionInfo {
        synchronized(guard) {
            if (closing.get()) throw fail(ErrorCode.CLOSED, "Worker closing")
            initialization?.let { if (it != request) throw fail(ErrorCode.CONFLICT, "Worker already initialized for another APK") }
            loadError?.let { throw ProtocolException(it) }
            if (endpoint != null) return info()
            if (initializing) throw fail(ErrorCode.BUSY, "Worker is loading")
            initializing = true
            initialization = request
        }
        var loaded: BackendEndpoint? = null
        var accepted = false
        try {
            val opened = factory.open(request, context, generatedCache)
            loaded = opened
            context.checkActive()
            if (opened.capabilities.backend != BackendKind.HEADLESS) throw fail(ErrorCode.INVALID_ARGUMENT, "Worker factory returned GUI endpoint")
            return synchronized(guard) {
                if (closing.get()) throw fail(ErrorCode.CLOSED, "Worker closed while loading")
                endpoint = opened
                accepted = true
                info()
            }
        } catch (e: OutOfMemoryError) { throw e }
        catch (e: Throwable) {
            val error = if (e is ProtocolException) e.error else ProtocolError(ErrorCode.INTERNAL,
                "WORKER_LOAD_FAILED: ${e.javaClass.simpleName}: ${e.message}")
            synchronized(guard) { loadError = error }
            throw ProtocolException(error)
        } finally {
            synchronized(guard) { initializing = false }
            if (!accepted) loaded?.close()
        }
    }

    private fun execute(request: BackendRequest, context: CallContext): OperationResult {
        if (!lane.tryAcquire()) throw fail(ErrorCode.BUSY, "Worker already executing")
        try {
            val backend = synchronized(guard) {
                if (closing.get()) throw fail(ErrorCode.CLOSED, "Worker closing")
                val current = endpoint ?: throw fail(ErrorCode.BUSY, "Worker is not ready")
                if (boundSession == null) boundSession = request.sessionId
                if (boundSession != request.sessionId) throw fail(ErrorCode.CONFLICT, "Worker belongs to another session")
                if (!request.operation.allowsBackend(BackendKind.HEADLESS) ||
                    current.capabilities.operations.any { it.operation == request.operation && !it.supported })
                    throw fail(ErrorCode.UNSUPPORTED, "Worker operation unsupported")
                current
            }
            val result = backend.execute(request, context)
            context.checkActive()
            if (closing.get()) throw fail(ErrorCode.CLOSED, "Worker closed during execution")
            return result
        } finally { lane.release() }
    }

    private fun info() = SessionInfo(sessionId, BackendKind.HEADLESS, initialization?.apkPath ?: "",
        if (closing.get()) SessionState.CLOSED else if (endpoint == null) SessionState.CONNECTING else SessionState.READY,
        0, createdAt, System.currentTimeMillis(), endpoint?.capabilities)

    override fun close() {
        closing.set(true)
        val backend = synchronized(guard) { endpoint.also { endpoint = null } }
        try { backend?.close() } finally {
            server.stop(0)
            threads.shutdownNow()
            stopped.countDown()
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
    private fun fail(code: ErrorCode, message: String) = ProtocolException(ProtocolError(code, message))
    companion object { private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024 }
}
