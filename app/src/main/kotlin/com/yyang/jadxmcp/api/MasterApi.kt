package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.SessionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray

data class ApiLimits(val maxBodyBytes: Int = 2 * 1024 * 1024, val bodyReadTimeoutMillis: Long = 10_000) {
    init { require(maxBodyBytes in 1024..16 * 1024 * 1024); require(bodyReadTimeoutMillis > 0) }
}
private class BodyTooLarge : RuntimeException()

/** Install on an otherwise empty Ktor application; ownership of SessionService stays with the caller. */
@OptIn(io.ktor.utils.io.InternalAPI::class)
fun Application.masterApi(sessions: SessionService, limits: ApiLimits = ApiLimits()) {
    val registry = CommandRegistry(sessions, limits.maxBodyBytes)
    val gui = GuiTransportCodec()
    val mcpServers = java.util.concurrent.ConcurrentHashMap.newKeySet<io.modelcontextprotocol.kotlin.sdk.server.Server>()
    // The SDK DSL retains transports across HTTP calls but does not own application shutdown.
    // Close protocol sessions explicitly, so silent clients receive one transport-close event.
    monitor.subscribe(ApplicationStopping) {
        runBlocking { mcpServers.toList().forEach { server -> server.close() } }
        mcpServers.clear()
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Delete) // Streamable HTTP transport session termination.
    }
    // Bound and replay the raw channel BEFORE entering SDK handlers. A receive-transform exception
    // is too late: the SDK correctly treats receive failures as malformed JSON-RPC (HTTP 400).
    // setReceiveChannel is version-sensitive Ktor InternalAPI, explicitly opted in above.
    // Keep the real-HTTP chunked-body test when upgrading Ktor; JSON-RPC remains SDK-owned.
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > limits.maxBodyBytes) throw BodyTooLarge()
            if (call.request.httpMethod == HttpMethod.Post) {
                val channel = call.request.receiveChannel()
                val bytes = withTimeout(limits.bodyReadTimeoutMillis) {
                    channel.readRemaining(limits.maxBodyBytes.toLong() + 1).readByteArray()
                }
                if (bytes.size > limits.maxBodyBytes) throw BodyTooLarge()
                context.request.setReceiveChannel(ByteReadChannel(bytes))
            }
            proceed()
        } catch (_: BodyTooLarge) {
            call.respondText(gui.encodeError(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Request body exceeds limit")),
                ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
            finish()
        } catch (_: TimeoutCancellationException) {
            call.respondText(gui.encodeError(ProtocolError(ErrorCode.DEADLINE_EXCEEDED, "Request body read timed out")),
                ContentType.Application.Json, HttpStatusCode.RequestTimeout)
            finish()
        }
    }
    mcpStreamableHttp(path = "/mcp", enableDnsRebindingProtection = false, allowedHosts = null, allowedOrigins = null) {
        McpAdapter(registry).createServer().also { server ->
            mcpServers += server
            server.onConnect { server.sessions.values.forEach { session -> session.onClose { mcpServers.remove(server) } } }
        }
    }
    routing {
        get("/health") { call.respondText("""{"status":"ok","mcp":"/mcp","api":"/api","auth":"trusted-network-only"}""", ContentType.Application.Json) }
        registry.operations.forEach { operation ->
            // Every command is POST: mutating operations never get an accidental GET alias.
            post("/api/${operation.wireName}") {
                call.handleApi(gui) {
                    val body = call.jsonBody()
                    runInterruptible(Dispatchers.IO) { registry.execute(operation, body) }
                }
            }
        }
        post(GuiHttpPaths.REGISTER) {
            call.handleApi(gui) {
                val request = gui.decodeRegistration(call.jsonBody())
                gui.encodeLease(sessions.register(request))
            }
        }
        post(GuiHttpPaths.POLL) {
            call.handleApi(gui) {
                val request = gui.decodePollRequest(call.jsonBody())
                if (request.deadlineEpochMillis > System.currentTimeMillis() + 600_000) {
                    throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Poll deadline exceeds hard cap"))
                }
                gui.encodePollResult(runInterruptible(Dispatchers.IO) { sessions.poll(request.lease, request.context()) })
            }
        }
        post(GuiHttpPaths.COMPLETE) {
            call.handleApi(gui) {
                val request = gui.decodeCompletion(call.jsonBody())
                sessions.complete(request.lease, request.completion)
                gui.encodeAck()
            }
        }
        post(GuiHttpPaths.HEARTBEAT) {
            call.handleApi(gui) {
                val request = gui.decodeHeartbeat(call.jsonBody())
                sessions.heartbeat(request.lease, request.projectEpoch)
                gui.encodeAck()
            }
        }
        post(GuiHttpPaths.UNREGISTER) {
            call.handleApi(gui) {
                sessions.unregister(gui.decodeLease(call.jsonBody()))
                gui.encodeAck()
            }
        }
    }
}

private suspend fun ApplicationCall.jsonBody(): String {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Content-Type must be application/json"))
    }
    return receiveText()
}

private suspend fun ApplicationCall.handleApi(codec: GuiTransportCodec, action: suspend () -> String) {
    try {
        respondText(action(), ContentType.Application.Json)
    } catch (e: CancellationException) { throw e }
    catch (e: BodyTooLarge) { throw e }
    catch (e: Exception) {
        val error = apiError(e)
        respondText(codec.encodeError(error), ContentType.Application.Json, status(error))
    }
}
