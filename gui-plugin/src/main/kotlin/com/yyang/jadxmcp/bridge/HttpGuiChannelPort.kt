package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class GuiHttpLimits(
    val connectTimeoutMillis: Long = 3_000,
    val ioTimeoutMillis: Long = 30_000,
    val cancellationCheckMillis: Long = 25,
    val maxResponseBytes: Int = 2 * 1024 * 1024,
    val maxRequestBytes: Int = 2 * 1024 * 1024
) {
    init {
        require(connectTimeoutMillis in 1..600_000 && ioTimeoutMillis in 1..600_000)
        require(cancellationCheckMillis in 1..1_000)
        require(maxResponseBytes in 1..16 * 1024 * 1024 && maxRequestBytes in 1..16 * 1024 * 1024)
    }
}

/** JDK HTTP transport for the shared GUI protocol; no analysis or GUI ownership.
 * The base URI is an HTTP(S) origin, not /gui. Redirects are refused so lease tokens are never
 * forwarded to a redirect target. Like the master API, plain HTTP assumes a trusted network.
 */
class HttpGuiChannelPort(
    private val baseUri: URI,
    private val limits: GuiHttpLimits = GuiHttpLimits(),
    private val codec: GuiTransportCodec = GuiTransportCodec(),
    private val diagnostics: GuiDiagnosticLog? = null,
) : GuiChannelPort {
    init {
        require(baseUri.scheme in setOf("http", "https") && baseUri.host != null)
        require(baseUri.rawUserInfo == null && baseUri.rawQuery == null && baseUri.rawFragment == null)
        require(baseUri.path.isNullOrEmpty() || baseUri.path == "/") { "Expected master HTTP origin" }
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(limits.connectTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun register(registration: GuiRegistration): GuiLease = diagnostic("register") {
        val json = step("register_encode") { codec.encodeRegistration(registration) }
        val response = post(GuiHttpPaths.REGISTER, json)
        step("register_decode") { codec.decodeLease(response) }
    }

    override fun poll(lease: GuiLease, context: CallContext): PollResult = diagnostic("poll") {
        context.checkActive()
        val json = step("poll_encode") { codec.encodePollRequest(
            GuiPollRequest(lease, context.requestId, context.projectEpoch, context.deadlineEpochMillis)) }
        val response = post(GuiHttpPaths.POLL, json, context)
        val result = step("poll_decode") { codec.decodePollResult(response) }
        if (result.status != PollStatus.CLOSED) context.checkActive()
        result
    }

    override fun complete(lease: GuiLease, completion: CommandCompletion) = diagnostic("complete") {
        diagnostics?.event("complete_begin", null, "session" to GuiDiagnosticLog.id(lease.sessionId),
            "request" to GuiDiagnosticLog.id(completion.requestId), "epoch" to completion.projectEpoch,
            "operation" to completion.operation)
        val json = step("complete_encode") { codec.encodeCompletion(GuiCompletionRequest(lease, completion)) }
        ack(post(GuiHttpPaths.COMPLETE, json), "complete")
    }

    override fun heartbeat(lease: GuiLease, projectEpoch: Long) = diagnostic("heartbeat") {
        val json = step("heartbeat_encode") { codec.encodeHeartbeat(GuiHeartbeatRequest(lease, projectEpoch)) }
        ack(post(GuiHttpPaths.HEARTBEAT, json), "heartbeat")
    }

    override fun unregister(lease: GuiLease) = diagnostic("unregister") {
        val json = step("unregister_encode") { codec.encodeLease(lease) }
        ack(post(GuiHttpPaths.UNREGISTER, json), "unregister")
    }

    private fun <T> diagnostic(stage: String, action: () -> T): T = try {
        action()
    } catch (failure: ProtocolException) {
        diagnostics?.event("channel_failure", failure, "stage" to stage)
        // Stage is local; summary is a closed vocabulary. Never log lease/token/body or peer text.
        val summary = "GUI channel $stage: ${guiFailureCategory(failure.error)}"
        throw ProtocolException(failure.error.copy(message = summary))
    }

    private fun <T> step(stage: String, action: () -> T): T = try { action() }
    catch (failure: Exception) {
        diagnostics?.event("stage_failure", failure, "stage" to stage)
        throw failure
    }

    private fun ack(json: String, stage: String) {
        val accepted = step("${stage}_ack_decode") { codec.decodeAck(json).accepted }
        if (stage != "heartbeat" || !accepted) diagnostics?.event("ack", null, "stage" to stage, "accepted" to accepted)
        if (!accepted) throw ProtocolException(
            ProtocolError(ErrorCode.CONFLICT, "Master rejected GUI acknowledgement"))
    }

    private fun post(path: String, json: String, context: CallContext? = null): String {
        context?.checkActive()
        val bytes = json.toByteArray(UTF_8)
        diagnostics?.event("request_size_check", null, "route" to path, "bytes" to bytes.size,
            "limit" to limits.maxRequestBytes, "accepted" to (bytes.size <= limits.maxRequestBytes))
        if (bytes.size > limits.maxRequestBytes) throw ProtocolException(
            ProtocolError(ErrorCode.INVALID_ARGUMENT, "GUI request exceeds size limit"))
        val wallBudget = context?.let { it.deadlineEpochMillis - System.currentTimeMillis() } ?: limits.ioTimeoutMillis
        val budget = minOf(limits.ioTimeoutMillis, wallBudget)
        if (budget <= 0) throw timeout()
        // This monotonic budget covers headers AND body, even when a peer trickles bytes forever.
        val endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget)
        val body = AtomicReference<BoundedBody>()
        val aborted = AtomicBoolean()
        val request = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(Duration.ofMillis(budget))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val future = step("http_send") { client.sendAsync(request, HttpResponse.BodyHandler { info ->
            diagnostics?.event("http_headers", null, "route" to path, "status" to info.statusCode(),
                "declaredBytes" to info.headers().firstValueAsLong("Content-Length").orElse(-1),
                "limit" to limits.maxResponseBytes)
            BoundedBody(limits.maxResponseBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1))
                .also { body.set(it); if (aborted.get()) it.cancel() }
        }) }
        try {
            while (true) {
                context?.checkActive()
                val remaining = endNanos - System.nanoTime()
                if (remaining <= 0) throw timeout()
                val wait = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(limits.cancellationCheckMillis))
                val response = try { future.get(wait, TimeUnit.NANOSECONDS) }
                catch (_: TimeoutException) { continue }
                if (System.nanoTime() >= endNanos) throw timeout()
                val text = String(response.body(), UTF_8)
                val status = response.statusCode()
                diagnostics?.event("http_response", null, "route" to path, "status" to status,
                    "bytes" to response.body().size, "limit" to limits.maxResponseBytes)
                if (status !in 200..299) {
                    val error = try { codec.decodeError(text) }
                    catch (failure: ProtocolException) {
                        diagnostics?.event("http_error_decode", failure, "route" to path, "status" to status)
                        throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT,
                            "HTTP $status response is not a typed GUI error"))
                    }
                    // Preserve typed codes without displaying untrusted peer text in the UI.
                    diagnostics?.event("http_typed_error", ProtocolException(error), "route" to path, "status" to status)
                    throw ProtocolException(error.copy(message = "HTTP $status typed GUI error: ${guiFailureCategory(error)}"))
                }
                return text
            }
        } catch (e: ProtocolException) {
            diagnostics?.event("http_call_failed", e, "route" to path, "requestBytes" to bytes.size,
                "requestLimit" to limits.maxRequestBytes, "responseLimit" to limits.maxResponseBytes)
            throw e
        } catch (e: InterruptedException) {
            diagnostics?.event("http_interrupted", e, "route" to path)
            Thread.currentThread().interrupt()
            throw ProtocolException(ProtocolError(ErrorCode.CANCELLED, "GUI HTTP call interrupted"))
        } catch (e: ExecutionException) {
            diagnostics?.event("http_response_failure", e, "route" to path, "receivedBytes" to body.get()?.receivedBytes,
                "responseLimit" to limits.maxResponseBytes)
            var cause = e.cause
            while (cause is java.util.concurrent.CompletionException && cause.cause != null) cause = cause.cause
            if (cause is ProtocolException) throw cause
            if (cause is java.net.http.HttpTimeoutException) throw timeout()
            throw ProtocolException(ProtocolError(ErrorCode.DISCONNECTED, "GUI HTTP transport failed", true))
        } catch (e: IOException) {
            diagnostics?.event("http_io_failure", e, "route" to path)
            throw ProtocolException(ProtocolError(ErrorCode.DISCONNECTED, "GUI HTTP transport failed", true))
        } finally {
            // Also abort streamed bodies. Response completion alone does not imply caller acceptance.
            aborted.set(true)
            future.cancel(true)
            body.get()?.cancel()
        }
    }

    private fun timeout() = ProtocolException(
        ProtocolError(ErrorCode.DEADLINE_EXCEEDED, "GUI HTTP call timed out", true))

    /** Aggregate at most max bytes, regardless of Content-Length or chunked transfer framing. */
    private class BoundedBody(private val max: Int, private val declaredLength: Long) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val output = ByteArrayOutputStream(minOf(max, 8192))
        @Volatile private var subscription: Flow.Subscription? = null
        private val cancelled = AtomicBoolean()
        val receivedBytes: Int get() = output.size()
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            if (cancelled.get()) value.cancel()
            else if (declaredLength > max) tooLarge() else value.request(1)
        }
        override fun onNext(items: List<ByteBuffer>) {
            if (result.isDone) return
            if (items.sumOf { it.remaining().toLong() } > max.toLong() - output.size()) {
                tooLarge()
                return
            }
            items.forEach { buffer ->
                // Copy in bounded slices, not a second allocation as large as the entire response.
                val slice = ByteArray(minOf(buffer.remaining(), 8192))
                while (buffer.hasRemaining()) {
                    val count = minOf(slice.size, buffer.remaining())
                    buffer.get(slice, 0, count)
                    output.write(slice, 0, count)
                }
            }
            subscription?.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(output.toByteArray()) }
        fun cancel() { cancelled.set(true); subscription?.cancel() }
        private fun tooLarge() {
            result.completeExceptionally(ProtocolException(
                ProtocolError(ErrorCode.INVALID_ARGUMENT, "GUI response exceeds size limit")))
            cancel()
        }
    }
}
