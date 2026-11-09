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

/** JDK 11 HTTP transport for the shared GUI protocol; no analysis or GUI ownership.
 * The base URI is an HTTP(S) origin, not /gui. Redirects are refused so lease tokens are never
 * forwarded to a redirect target. Like the master API, plain HTTP assumes a trusted network.
 */
class HttpGuiChannelPort(
    private val baseUri: URI,
    private val limits: GuiHttpLimits = GuiHttpLimits(),
    private val codec: GuiTransportCodec = GuiTransportCodec()
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

    override fun register(registration: GuiRegistration): GuiLease =
        codec.decodeLease(post(GuiHttpPaths.REGISTER, codec.encodeRegistration(registration)))

    override fun poll(lease: GuiLease, context: CallContext): PollResult {
        context.checkActive()
        val result = codec.decodePollResult(post(GuiHttpPaths.POLL, codec.encodePollRequest(
            GuiPollRequest(lease, context.requestId, context.projectEpoch, context.deadlineEpochMillis)), context))
        if (result.status != PollStatus.CLOSED) context.checkActive()
        return result
    }

    override fun complete(lease: GuiLease, completion: CommandCompletion) {
        ack(post(GuiHttpPaths.COMPLETE, codec.encodeCompletion(GuiCompletionRequest(lease, completion))))
    }

    override fun heartbeat(lease: GuiLease, projectEpoch: Long) {
        ack(post(GuiHttpPaths.HEARTBEAT, codec.encodeHeartbeat(GuiHeartbeatRequest(lease, projectEpoch))))
    }

    override fun unregister(lease: GuiLease) {
        ack(post(GuiHttpPaths.UNREGISTER, codec.encodeLease(lease)))
    }

    private fun ack(json: String) {
        if (!codec.decodeAck(json).accepted) throw ProtocolException(
            ProtocolError(ErrorCode.CONFLICT, "Master rejected GUI acknowledgement"))
    }

    private fun post(path: String, json: String, context: CallContext? = null): String {
        context?.checkActive()
        val bytes = json.toByteArray(UTF_8)
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
        val future = client.sendAsync(request, HttpResponse.BodyHandler { info ->
            BoundedBody(limits.maxResponseBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1))
                .also { body.set(it); if (aborted.get()) it.cancel() }
        })
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
                if (response.statusCode() !in 200..299) throw ProtocolException(codec.decodeError(text))
                return text
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProtocolException(ProtocolError(ErrorCode.CANCELLED, "GUI HTTP call interrupted"))
        } catch (e: ExecutionException) {
            var cause = e.cause
            while (cause is java.util.concurrent.CompletionException && cause.cause != null) cause = cause.cause
            if (cause is ProtocolException) throw cause
            if (cause is java.net.http.HttpTimeoutException) throw timeout()
            throw ProtocolException(ProtocolError(ErrorCode.DISCONNECTED, "GUI HTTP transport failed", true))
        } catch (e: IOException) {
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
