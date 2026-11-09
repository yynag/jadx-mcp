package com.yyang.jadxmcp.process

import com.yyang.jadxmcp.protocol.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

fun interface WorkerTransport {
    fun exchange(address: URI, token: String, frame: RequestFrame): CompletableFuture<CommandCompletion>
}

/** JDK transport only; domain payloads always go through the shared strict codec. */
class HttpWorkerTransport : WorkerTransport, AutoCloseable {
    private val codec = ProtocolCodec()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun exchange(address: URI, token: String, frame: RequestFrame): CompletableFuture<CommandCompletion> {
        require(address.host == "127.0.0.1" && address.scheme == "http") { "Worker address must be IPv4 loopback" }
        val remaining = (frame.context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1)
        val request = HttpRequest.newBuilder(address).timeout(Duration.ofMillis(remaining))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(codec.encodeFrame(frame))).build()
        val sending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val decoded = sending.thenApply { response ->
            if (response.statusCode() != 200) throw ProtocolException(ProtocolError(
                if (response.statusCode() == 401) ErrorCode.INVALID_ARGUMENT else ErrorCode.INTERNAL,
                "Worker HTTP status ${response.statusCode()}"))
            codec.decodeCompletion(response.body())
        }
        decoded.whenComplete { _, _ -> if (decoded.isCancelled) sending.cancel(true) }
        return decoded
    }

    override fun close() { client.shutdownNow() }
}
