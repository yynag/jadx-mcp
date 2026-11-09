package com.yyang.jadxmcp.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yyang.jadxmcp.bootstrap.MasterApiServer
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Real HTTP and SDK sessions, not mocked handler receivers or request-local identity. */
class ServerStatusHttpTest {
    @Test fun initializeCallsFailuresAndDeleteHaveExactClientOwnership() = Fixture().use { f ->
        assertEquals(404, f.get("/health").statusCode())
        assertTrue(f.get("/status").headers().firstValue("content-type").orElse("").startsWith("text/plain"))
        val first = Peer(f)
        first.initialize(false)
        assertTrue(f.status().contains("clients: 0 (initialized)"))
        first.initialized()
        val firstAlias = f.clientLines().single().trim().substringBefore(' ')
        val second = Peer(f)
        second.initialize(true)
        assertNotEquals(first.sessionId, second.sessionId)
        first.call("tools/list", "{}")
        second.call("ping", "{}")
        f.post("/api/session_list", "{}")
        val codec = GuiTransportCodec()
        val lease = f.sessions.register(GuiRegistration("private-gui", "/private/project.apk", 0))
        f.post(GuiHttpPaths.HEARTBEAT, codec.encodeHeartbeat(GuiHeartbeatRequest(lease, 0)))
        f.post(GuiHttpPaths.POLL, codec.encodePollRequest(GuiPollRequest(lease, "poll", 0,
            System.currentTimeMillis() + 50)))
        assertTrue(f.status().contains("tools/call: 10s=0 30s=0 60s=0"))

        first.call("tools/call", """{"name":"session_list","arguments":{}}""")
        val failure = first.call("tools/call", """{"name":"nonexistent","arguments":{}}""").getAsJsonObject("result")
        assertTrue(failure.get("isError").asBoolean)
        assertTrue(text(failure).isNotBlank())
        second.call("tools/call", """{"name":"session_list","arguments":{}}""")
        assertTrue(f.clientLines().single { it.trim().startsWith("$firstAlias ") }.contains("10s=2 30s=2 60s=2"))
        assertTrue(f.clientLines().single { !it.trim().startsWith("$firstAlias ") }.contains("10s=1 30s=1 60s=1"))
        // The raw SDK handler must also count failures before a registered tool callback.
        assertTrue(second.call("tools/call", """{"name":"nonexistent","arguments":{}}""")
            .getAsJsonObject("result").get("isError").asBoolean)
        assertTrue(second.call("tools/call", "{}").has("error"))
        val snapshot = f.status()
        assertTrue(snapshot.contains("tools/call: 10s=5 30s=5 60s=5"), snapshot)
        listOf(first.sessionId, second.sessionId, lease.token, "/private/", "private-gui").forEach {
            assertFalse(snapshot.contains(it), snapshot)
        }
        first.close()
        assertEquals(1, f.clientLines().size)
        assertTrue(f.status().contains("tools/call: 10s=5 30s=5 60s=5"))
        second.close()
        assertTrue(f.status().contains("clients: 0 (initialized)"))
        assertTrue(f.status().contains("tools/call: 10s=5 30s=5 60s=5"))
    }

    @Test fun successTextUsesProjectionButRestStillReturnsTypedEnvelope() = Fixture().use { f ->
        val created = f.post("/api/session_create", """{"apk_path":"/private/fixture.apk"}""").body()
        val id = json(created).getAsJsonObject("result").get("session_id").asString
        val rest = f.post("/api/meta_summary", """{"session_id":"$id"}""").body()
        assertEquals(17, json(rest).getAsJsonObject("result").get("classes_count").asInt)
        val peer = Peer(f)
        peer.initialize(true)
        val result = peer.call("tools/call", """{"name":"meta_summary","arguments":{"session_id":"$id"}}""")
            .getAsJsonObject("result")
        assertFalse(result.get("isError")?.asBoolean ?: false)
        assertEquals(McpOutput.render(Operation.META_SUMMARY, rest), text(result))
        assertTrue(f.status().contains("name=fixture.apk"))
        assertFalse(f.status().contains("/private/"))
        peer.close()
    }

    private class Fixture : AutoCloseable {
        val sessions = SessionService(HeadlessBackendFactory { _, _ -> object : BackendEndpoint {
            override val capabilities = BackendCapabilities(BackendKind.HEADLESS)
            override fun execute(request: BackendRequest, context: CallContext): OperationResult =
                SummaryResult(classesCount = 17)
            override fun close() = Unit
        } })
        private val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port"
        val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        private val server = MasterApiServer(sessions, port, "127.0.0.1").apply { start() }
        fun get(path: String) = client.send(HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        fun post(path: String, body: String) = client.send(HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        fun status() = get("/status").body()
        fun clientLines() = status().substringAfter("(initialized)\n").lines().filter { it.isNotBlank() }
        override fun close() { server.close(); sessions.close(); client.close() }
    }

    private class Peer(private val f: Fixture) {
        var sessionId = ""
        private var nextId = 1
        fun initialize(notify: Boolean) {
            val response = exchange("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
            assertEquals(200, response.statusCode(), response.body())
            sessionId = response.headers().firstValue("Mcp-Session-Id").orElseThrow()
            if (notify) initialized()
        }
        fun initialized() { assertTrue(exchange("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode() in 200..299) }
        fun call(method: String, params: String): JsonObject {
            val response = exchange("""{"jsonrpc":"2.0","id":${nextId++},"method":"$method","params":$params}""")
            assertEquals(200, response.statusCode(), response.body())
            return if (response.body().trimStart().startsWith("{")) json(response.body()) else
                response.body().lineSequence().filter { it.startsWith("data:") }
                    .map { json(it.removePrefix("data:").trim()) }.first { it.has("result") || it.has("error") }
        }
        private fun exchange(body: String): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create(f.base + "/mcp")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26")
            if (sessionId.isNotEmpty()) request.header("Mcp-Session-Id", sessionId)
            return f.client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        }
        fun close() {
            val response = f.client.send(HttpRequest.newBuilder(URI.create(f.base + "/mcp"))
                .timeout(Duration.ofSeconds(10)).header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-03-26").DELETE().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), response.body())
        }
    }

    companion object {
        private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
        private fun text(result: JsonObject) = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
    }
}
