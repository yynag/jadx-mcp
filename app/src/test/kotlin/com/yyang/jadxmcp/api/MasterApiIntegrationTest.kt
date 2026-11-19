package com.yyang.jadxmcp.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yyang.jadxmcp.bootstrap.MasterApiServer
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real HTTP + real MCP SDK transport; fake analysis/GUI endpoints, NOT a real JADX GUI smoke test. */
class MasterApiIntegrationTest {
    @Test fun lifecycleLogsFollowRealMcpAndGuiSessionsNotHttpRequests() =
        com.yyang.jadxmcp.bootstrap.LoggingTest.capture { events ->
            Fixture().use { f ->
                f.post("/api/session_list")
                f.post("/api/session_list")
                assertFalse(events.any { it.formattedMessage.startsWith("MCP client") })
                val peer = McpHttpPeer(f)
                peer.initialize()
                peer.call("tools/list", "{}")
                peer.call("tools/call", """{"name":"session_list","arguments":{}}""")
                peer.close()
                assertEquals(1, events.count { it.formattedMessage.startsWith("MCP client connected") })
                assertEquals(1, events.count { it.formattedMessage.startsWith("MCP client exited") })
                val codec = GuiTransportCodec()
                val lease = codec.decodeLease(f.post(GuiHttpPaths.REGISTER, codec.encodeRegistration(
                    GuiRegistration("logging-gui", "private-name", 0, BackendCapabilities(BackendKind.GUI)))).body())
                repeat(2) { f.post(GuiHttpPaths.UNREGISTER, codec.encodeLease(lease)) }
                assertEquals(1, events.count { it.formattedMessage.startsWith("GUI registered") })
                assertEquals(1, events.count { it.formattedMessage.startsWith("GUI closed") })
                assertEquals(1, events.count { it.formattedMessage.startsWith("MCP client connected") })
                val id = f.create("private-input")
                f.post("/api/meta_summary", """{"session_id":"$id"}""")
                repeat(2) { f.post("/api/session_close", """{"session_id":"$id"}""") }
                assertEquals(1, events.count { it.formattedMessage.startsWith("Session opened") && id in it.formattedMessage })
                assertEquals(1, events.count { it.formattedMessage.startsWith("Session closed") && id in it.formattedMessage })
                assertFalse(events.any { it.formattedMessage.contains("private-input") || it.formattedMessage.contains("private-name") })
                assertFalse(events.any { it.formattedMessage.startsWith("Request started") })
            }
            assertEquals(1, events.count { it.formattedMessage.startsWith("MCP client exited") })
        }
    private class Fixture(limits: ApiLimits = ApiLimits()) : AutoCloseable {
        val sessions = SessionService(HeadlessBackendFactory { creation, _ -> object : BackendEndpoint {
            override val capabilities = BackendCapabilities(BackendKind.HEADLESS)
            override fun execute(request: BackendRequest, context: CallContext): OperationResult {
                if (creation.apkPath == "cancelled") throw ProtocolException(ProtocolError(ErrorCode.CANCELLED, "fake cancellation"))
                if (creation.apkPath == "slow") while (true) { context.checkActive(); Thread.sleep(2) }
                return when (request) {
                    is SummaryRequest -> SummaryResult(classesCount = 17, mainPackage = "fake.fixture")
                    else -> throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Fake endpoint implements summary only"))
                }
            }
            override fun close() = Unit
        } })
        private val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port"
        private val server = MasterApiServer(sessions, port, "127.0.0.1", limits).apply { start() }
        val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        fun request(path: String, body: String = "{}", chunked: Boolean = false): HttpRequest = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(if (chunked) HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body.toByteArray()) }
                else HttpRequest.BodyPublishers.ofString(body)).build()
        fun post(path: String, body: String = "{}") = client.send(request(path, body), HttpResponse.BodyHandlers.ofString())
        fun create(path: String = "fake"): String = json(post("/api/session_create", """{"apk_path":"$path"}""").body())
            .getAsJsonObject("result").get("session_id").asString
        override fun close() { server.close(); sessions.close(); client.close() }
    }

    @Test fun managementAndAnalysisUseTheSameTypedPostDispatcher() = Fixture().use { f ->
        val id = f.create()
        val result = f.post("/api/meta_summary", """{"session_id":"$id"}""")
        assertEquals(200, result.statusCode())
        assertEquals(17, json(result.body()).getAsJsonObject("result").get("classes_count").asInt)
        val restSessions = json(f.post("/api/session_list").body()).getAsJsonObject("result").getAsJsonArray("sessions")
        assertEquals(1, restSessions.size())
        assertTrue(restSessions[0].asJsonObject.has("project_epoch"))
        assertNotEquals(404, f.post("/api/get_main_application_classes_names", """{"session_id":"$id"}""").statusCode())
        val invalid = f.post("/api/session_create", """{"apk_path":"fake","deobf":"false"}""")
        assertEquals(400, invalid.statusCode())
        assertEquals("INVALID_ARGUMENT", error(invalid))
        val get = f.client.send(HttpRequest.newBuilder(URI.create(f.base + "/api/rename")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(405, get.statusCode())
        assertEquals(400, f.post("/api/meta_summary", """{"apk_id":"$id"}""").statusCode())
        assertEquals(404, f.post("/api/meta_summary", """{"session_id":"missing"}""").statusCode())
        assertEquals(200, f.post("/api/session_close", """{"session_id":"$id"}""").statusCode())
    }

    @Test fun realHttpGuiChannelCarriesTypedFramesAndFencesEpochAndToken() = Fixture().use { f ->
        val codec = GuiTransportCodec()
        val lease = codec.decodeLease(f.post(GuiHttpPaths.REGISTER, codec.encodeRegistration(
            GuiRegistration("fake-gui", "Fake GUI", 4, BackendCapabilities(BackendKind.GUI,
                listOf(OperationCapability(Operation.GET_SELECTED_TEXT)))))).body())
        assertEquals(409, f.post("/api/get_selected_text", """{"session_id":"${lease.sessionId}","project_epoch":3}""").statusCode())
        val pending = f.client.sendAsync(f.request("/api/get_selected_text", """{"session_id":"${lease.sessionId}","timeout_ms":5000}"""), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, f.post(GuiHttpPaths.HEARTBEAT, codec.encodeHeartbeat(GuiHeartbeatRequest(lease, 4))).statusCode())
        val poll = codec.decodePollResult(f.post(GuiHttpPaths.POLL,
            codec.encodePollRequest(GuiPollRequest(lease, "poll", 4, System.currentTimeMillis() + 3000))).body())
        assertEquals(PollStatus.COMMAND, poll.status)
        val frame = requireNotNull(poll.command)
        assertEquals(SelectedTextRequest(lease.sessionId), frame.request)
        val completion = CommandCompletion(frame.context.requestId, 4, Operation.GET_SELECTED_TEXT, SelectionResult("selected"))
        assertEquals(409, f.post(GuiHttpPaths.COMPLETE,
            codec.encodeCompletion(GuiCompletionRequest(lease, completion.copy(requestId = "late")))).statusCode())
        assertEquals(200, f.post(GuiHttpPaths.COMPLETE, codec.encodeCompletion(GuiCompletionRequest(lease, completion))).statusCode())
        assertEquals("selected", json(pending.get(5, TimeUnit.SECONDS).body()).getAsJsonObject("result").get("text").asString)
        assertNotEquals(200, f.post(GuiHttpPaths.HEARTBEAT,
            codec.encodeHeartbeat(GuiHeartbeatRequest(lease.copy(token = "wrong"), 4))).statusCode())
        f.post("/api/session_close", """{"session_id":"${lease.sessionId}"}""")
        val closed = codec.decodePollResult(f.post(GuiHttpPaths.POLL,
            codec.encodePollRequest(GuiPollRequest(lease, "after-close", 4, System.currentTimeMillis() + 3000))).body())
        assertEquals(PollStatus.CLOSED, closed.status)
    }

    @Test fun sdkMcpHandshakeSchemasAndToolRoutingWorkOverRealHttp() = Fixture().use { f ->
        val mcp = McpHttpPeer(f)
        val initialization = mcp.initialize()
        assertEquals("2025-03-26", initialization.get("protocolVersion").asString)
        val serverInfo = initialization.getAsJsonObject("serverInfo")
        assertEquals("jadx-mcp", serverInfo.get("name").asString)
        assertEquals("2.0.0", serverInfo.get("version").asString)
        val tools = mcp.call("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(25, tools.size())
        assertFalse(tools.any { it.asJsonObject.get("name").asString == "get_main_application_classes_names" })
        assertFalse(tools.any { it.asJsonObject.get("name").asString.startsWith("apk_") })
        val source = tools.first { it.asJsonObject.get("name").asString == "decompile" }.asJsonObject
        val targets = source.getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("target").getAsJsonArray("enum")
        assertEquals(listOf("JAVA", "SMALI", "METHOD", "MAIN_ACTIVITY"), targets.map { it.asString })
        val searchSchema = tools.first { it.asJsonObject.get("name").asString == "search" }.asJsonObject
            .getAsJsonObject("inputSchema").getAsJsonObject("properties")
        assertTrue(searchSchema.has("timeout_ms"))
        assertFalse(searchSchema.has("project_epoch"))
        assertFalse(searchSchema.has("request_id"))
        val budgetSchema = searchSchema.getAsJsonObject("budget")
        assertTrue(budgetSchema.getAsJsonObject("properties").getAsJsonObject("max_decompile").get("description").asString.contains("cache hits"))
        val create = mcp.call("tools/call", """{"name":"session_create","arguments":{"apk_path":"fake"}}""")
        val created = json(create.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        assertFalse(created.has("project_epoch"))
        val id = created.get("session_id").asString
        val listed = mcp.call("tools/call", """{"name":"session_list","arguments":{}}""")
            .getAsJsonObject("result")
        val listedSessions = JsonParser.parseString(listed.getAsJsonArray("content")[0].asJsonObject.get("text").asString).asJsonArray
        assertFalse(listedSessions[0].asJsonObject.has("project_epoch"))
        val summary = mcp.call("tools/call", """{"name":"meta_summary","arguments":{"session_id":"$id"}}""").getAsJsonObject("result")
        assertFalse(summary.get("isError")?.asBoolean ?: false)
        assertEquals(17, json(summary.getAsJsonArray("content")[0].asJsonObject.get("text").asString).get("classes_count").asInt)
        val invalid = mcp.call("tools/call", """{"name":"decompile","arguments":{"session_id":"$id","class_name":"App","target":"java"}}""").getAsJsonObject("result")
        assertTrue(invalid.get("isError").asBoolean)
        assertEquals("INVALID_ARGUMENT", json(invalid.getAsJsonArray("content")[0].asJsonObject.get("text").asString).getAsJsonObject("error").get("code").asString)
        val hidden = mcp.call("tools/call", """{"name":"get_main_application_classes_names","arguments":{"session_id":"$id"}}""")
            .getAsJsonObject("result")
        assertTrue(hidden.get("isError").asBoolean)
        assertEquals("Tool get_main_application_classes_names not found",
            hidden.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        val unknown = mcp.call("tools/call", """{"name":"unregistered_nonempty_tool","arguments":{}}""")
            .getAsJsonObject("result")
        assertTrue(unknown.get("isError").asBoolean)
        assertEquals("Tool unregistered_nonempty_tool not found",
            unknown.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test fun deadlinesAndCancellationAreNotCollapsedIntoInternalErrors() = Fixture().use { f ->
        val slow = f.create("slow")
        val timedOut = f.post("/api/meta_summary", """{"session_id":"$slow","timeout_ms":20}""")
        assertEquals(504, timedOut.statusCode()); assertEquals("DEADLINE_EXCEEDED", error(timedOut))
        val cancelled = f.create("cancelled")
        val result = f.post("/api/meta_summary", """{"session_id":"$cancelled"}""")
        assertEquals(408, result.statusCode()); assertEquals("CANCELLED", error(result))
    }

    @Test fun restAcceptsExplicitRequestTraceIdButRejectsEmptyIds() = Fixture().use { f ->
        val id = f.create()
        assertEquals(200, f.post("/api/meta_summary", """{"session_id":"$id","request_id":"trace-abc"}""").statusCode())
        val empty = f.post("/api/meta_summary", """{"session_id":"$id","request_id":""}""")
        assertEquals(400, empty.statusCode())
        assertEquals("INVALID_ARGUMENT", error(empty))
    }

    @Test fun restAndMcpRejectTheSameInvalidRequestBoundaries() = Fixture().use { f ->
        val id = f.create()
        val mcp = McpHttpPeer(f)
        mcp.initialize()
        val arguments = listOf(
            """{"session_id":"$id","unexpected":true}""",
            """{"session_id":"$id","page":null}""",
            """{"session_id":"$id","page":{"count":0}}""",
            """{"session_id":"$id","page":{"count":10001}}""",
            """{"session_id":"$id","page":{"offset":-1}}""",
            """{"session_id":"$id","page":{"offset":2147483648}}""",
            """{"session_id":"$id","page":{"count":1.5}}""",
            """{"session_id":"$id","page":{"unknown":1}}""",
            """{"session_id":"$id","scope":"class"}""",
            """{"session_id":"$id","timeout_ms":0}""",
            """{"session_id":"$id","timeout_ms":600001}""",
            """{"session_id":"$id","timeout_ms":null}""",
            """{"session_id":"$id","project_epoch":-1}""",
            """{"session_id":"$id","request_id":""}"""
        )
        try {
            arguments.forEach { parameters ->
                // Include a valid term so failures identify the tested boundary, not a missing field.
                val params = json(parameters).apply { addProperty("term", "fixture") }.toString()
                val rest = f.post("/api/search", params)
                assertEquals(400, rest.statusCode(), params)
                assertEquals("INVALID_ARGUMENT", error(rest), params)
                if (!json(parameters).has("project_epoch") && !json(parameters).has("request_id")) {
                    val result = mcp.call("tools/call", """{"name":"search","arguments":$params}""")
                        .getAsJsonObject("result")
                    assertTrue(result.get("isError").asBoolean, params)
                    val mcpError = json(result.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
                        .getAsJsonObject("error")
                    assertEquals(json(rest.body()).getAsJsonObject("error"), mcpError, params)
                }
            }
        } finally { mcp.close() }
    }

    @Test fun managementEpochFenceRemainsRestOnlyWhileMcpCanUseDefaultClose() = Fixture().use { f ->
        val mcp = McpHttpPeer(f)
        mcp.initialize()
        fun invokeMcp(operation: String, params: String) = mcp.call("tools/call",
            """{"name":"$operation","arguments":$params}""").getAsJsonObject("result")
        fun payload(result: JsonObject) = json(result.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        try {
            val lease = f.sessions.register(GuiRegistration("management-fence", "project.apk", 4))
            f.sessions.heartbeat(lease, 5)
            val stale = """{"session_id":"${lease.sessionId}","project_epoch":4}"""
            val rest = f.post("/api/session_close", stale)
            assertEquals(409, rest.statusCode())
            assertEquals("CONFLICT", error(rest))
            assertEquals(5L, f.sessions.get(lease.sessionId).projectEpoch)
            f.sessions.heartbeat(lease, 5)
            val closed = f.post("/api/session_close", """{"session_id":"${lease.sessionId}","project_epoch":5}""")
            assertEquals(200, closed.statusCode())
            assertTrue(json(closed.body()).getAsJsonObject("result").get("closed").asBoolean)
            val other = f.sessions.register(GuiRegistration("management-default", "other.apk", 9))
            val defaultClose = invokeMcp("session_close", """{"session_id":"${other.sessionId}"}""")
            assertFalse(defaultClose.get("isError").asBoolean)
            assertTrue(payload(defaultClose).get("closed").asBoolean)
        } finally { mcp.close() }
    }

    @Test fun bodyLimitsApplyToFixedAndChunkedBodiesIncludingMcp() = Fixture(ApiLimits(maxBodyBytes = 1024)).use { f ->
        val large = "{\"padding\":\"" + "a".repeat(1500) + "\"}"
        assertEquals(413, f.post("/api/session_list", large).statusCode())
        val chunked = f.client.send(f.request("/api/session_list", large, true), HttpResponse.BodyHandlers.ofString())
        assertEquals(413, chunked.statusCode())
        val mcp = f.client.send(f.request("/mcp", large, true), HttpResponse.BodyHandlers.ofString())
        assertEquals(413, mcp.statusCode())
    }

    private class McpHttpPeer(private val f: Fixture) {
        private var sessionId: String? = null
        private var nextId = 1
        fun close() {
            val response = f.client.send(HttpRequest.newBuilder(URI.create(f.base + "/mcp"))
                .timeout(Duration.ofSeconds(10)).header("Mcp-Session-Id", requireNotNull(sessionId))
                .header("MCP-Protocol-Version", "2025-03-26").DELETE().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), response.body())
        }
        fun initialize(): JsonObject {
            val response = exchange("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"http-test","version":"1"}}}""")
            assertEquals(200, response.statusCode(), response.body())
            sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null)
            val result = parse(response.body()).getAsJsonObject("result")
            assertNotNull(result)
            val notified = exchange("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertTrue(notified.statusCode() in 200..299, notified.body())
            return result
        }
        fun call(method: String, params: String): JsonObject {
            val response = exchange("""{"jsonrpc":"2.0","id":${nextId++},"method":"$method","params":$params}""")
            assertEquals(200, response.statusCode(), response.body())
            return parse(response.body())
        }
        private fun exchange(body: String): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create(f.base + "/mcp")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26")
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            return f.client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        }
        private fun parse(body: String): JsonObject = if (body.trimStart().startsWith("{")) json(body)
            else body.lineSequence().filter { it.startsWith("data:") }.map { json(it.removePrefix("data:").trim()) }
                .first { it.has("result") || it.has("error") }
    }
    companion object {
        private fun json(body: String): JsonObject = JsonParser.parseString(body).asJsonObject
        private fun error(response: HttpResponse<String>) = json(response.body()).getAsJsonObject("error").get("code").asString
    }
}
