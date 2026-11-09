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

/** Real SDK tool registration; the SDK owns JSON-RPC and Streamable HTTP session semantics. */
class McpAdapter(private val registry: CommandRegistry) {
    fun createServer(): Server = Server(
        serverInfo = Implementation(name = "jadx-mcp", version = "3.0.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        instructions = "Use session_create or session_list, then meta_summary, targeted search, xref, and decompile. " +
            "Use exact uppercase enum values. GUI-only tools require a GUI session. " +
            "Edits report applied and persisted separately. No synchronization policy is implied."
    ).also { server ->
        // onConnect runs after the SDK registers the real session, before HTTP initialize
        // processing. Session close means transport/DELETE close, NOT instantaneous TCP loss.
        server.onConnect {
            server.sessions.values.forEach { session ->
                val lifecycle = McpClientLifecycle(session.sessionId)
                session.onInitialized { lifecycle.connected() }
                session.onClose { lifecycle.closed() }
            }
        }
        registry.operations.forEach { operation ->
            val definition = registry.schema(operation)
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
        CallToolResult(content = listOf(TextContent(text = result)), isError = false)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        CallToolResult(content = listOf(TextContent(text = GuiTransportCodec().encodeError(apiError(e)))), isError = true)
    }
}
