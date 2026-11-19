package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.GuiTransportCodec
import com.yyang.jadxmcp.protocol.Operation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Properties

private val productVersion: String = Properties().apply {
    requireNotNull(McpAdapter::class.java.getResourceAsStream("/jadx-mcp-version.properties")) {
        "Missing generated product version resource"
    }.use { load(it) }
}.getProperty("version")?.takeIf { it.isNotBlank() }
    ?: error("Missing product version")

/** Real SDK tool registration; the SDK owns JSON-RPC and Streamable HTTP session semantics. */
class McpAdapter(private val registry: CommandRegistry, private val status: ServerStatus = ServerStatus()) {
    fun createServer(): Server = Server(
        serverInfo = Implementation(name = "jadx-mcp", version = productVersion),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        instructions = "Use session_create or session_list, then meta_summary, targeted search, xref, and decompile. " +
            "Use exact uppercase enum values. GUI-only tools require a GUI session. " +
            "Edits report applied and persisted separately. No synchronization policy is implied."
    ).also { server ->
        // onConnect runs after the SDK registers the real session, before HTTP initialize
        // processing. Session close means transport/DELETE close, NOT instantaneous TCP loss.
        val attached = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        server.onConnect {
            server.sessions.values.forEach { session ->
                if (!attached.add(session.sessionId)) return@forEach
                val lifecycle = McpClientLifecycle(session.sessionId, status)
                session.onInitialized { lifecycle.connected() }
                session.onClose { lifecycle.closed(); attached.remove(session.sessionId) }
                // Capture the SDK's raw handler before decoding: unknown tool names and
                // invalid CallToolRequest frames must count too, exactly once. Delegate
                // the untouched request so SDK validation/error semantics stay SDK-owned.
                val sdkCall = checkNotNull(session.requestHandlers["tools/call"])
                val fallback = session.fallbackRequestHandler
                session.removeRequestHandler(Method.Defined.ToolsCall)
                session.fallbackRequestHandler = { request, extra ->
                    if (request.method == "tools/call") {
                        status.toolCalled(session.sessionId)
                        sdkCall(request, extra)
                    } else if (fallback != null) {
                        fallback(request, extra)
                    } else {
                        throw McpException(RPCError.ErrorCode.METHOD_NOT_FOUND,
                            "Server does not support ${request.method}")
                    }
                }
            }
        }
        registry.operations.filterNot { it == Operation.GET_MAIN_APPLICATION_CLASSES_NAMES }.forEach { operation ->
            val definition = registry.mcpSchema(operation)
            val schema = Json.parseToJsonElement(definition.toString()).jsonObject
            val required = definition.getAsJsonArray("required").map { it.asString }
            server.addTool(Tool(name = operation.wireName, description = registry.description(operation),
                inputSchema = ToolSchema(properties = schema.getValue("properties").jsonObject, required = required))) { request ->
                call(operation, request.arguments)
            }
        }
    }

    private suspend fun call(operation: Operation, args: JsonObject?): CallToolResult = try {
        val result = runInterruptible(Dispatchers.IO) { registry.execute(operation, args?.toString() ?: "{}") }
        CallToolResult(content = listOf(TextContent(text = McpOutput.render(operation, result))), isError = false)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        CallToolResult(content = listOf(TextContent(text = GuiTransportCodec().encodeError(apiError(e)))), isError = true)
    }
}
