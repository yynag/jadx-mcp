package com.yyang.jadxmcp.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Shared master/plugin control-channel paths and typed bodies, separate from operation dispatch. */
object GuiHttpPaths {
    const val REGISTER = "/gui/register"
    const val POLL = "/gui/poll"
    const val COMPLETE = "/gui/complete"
    const val HEARTBEAT = "/gui/heartbeat"
    const val UNREGISTER = "/gui/unregister"
}

data class GuiPollRequest(
    val lease: GuiLease = GuiLease(), val requestId: String = "", val projectEpoch: Long = 0,
    val deadlineEpochMillis: Long = 0
) {
    fun context(cancellation: CancellationSignal = CancellationSignal.NONE) =
        CallContext(requestId, projectEpoch, deadlineEpochMillis, cancellation)
}
data class GuiHeartbeatRequest(val lease: GuiLease = GuiLease(), val projectEpoch: Long = 0)
data class GuiCompletionRequest(val lease: GuiLease, val completion: CommandCompletion)
data class GuiAck(val accepted: Boolean = true)
data class ErrorResponse(val error: ProtocolError)

/**
 * Only COMMAND replies contain a frame. Nested frames/completions use ProtocolCodec, never
 * Gson reflection over their polymorphic request/result interfaces or cancellation signals.
 */
class GuiTransportCodec(private val protocol: ProtocolCodec = ProtocolCodec()) {
    fun encodeRegistration(value: GuiRegistration): String = protocol.encodeModel(value)
    fun decodeRegistration(json: String): GuiRegistration = protocol.decodeModel(json, GuiRegistration::class.java).also {
        nonBlank(it.instanceId, "instanceId"); nonBlank(it.name, "name")
        if (it.projectEpoch < 0 || it.capabilities.backend != BackendKind.GUI) invalid("Invalid GUI registration")
    }
    fun encodeLease(value: GuiLease): String = protocol.encodeModel(value)
    fun decodeLease(json: String): GuiLease = protocol.decodeModel(json, GuiLease::class.java).also(::validateLease)
    fun encodePollRequest(value: GuiPollRequest): String = protocol.encodeModel(value)
    fun decodePollRequest(json: String): GuiPollRequest = protocol.decodeModel(json, GuiPollRequest::class.java).also {
        validateLease(it.lease); it.context()
    }
    fun encodeHeartbeat(value: GuiHeartbeatRequest): String = protocol.encodeModel(value)
    fun decodeHeartbeat(json: String): GuiHeartbeatRequest = protocol.decodeModel(json, GuiHeartbeatRequest::class.java).also {
        validateLease(it.lease)
        if (it.projectEpoch < 0) invalid("Invalid project epoch")
    }
    fun encodeAck(value: GuiAck = GuiAck()): String = protocol.encodeModel(value)
    fun decodeAck(json: String): GuiAck = protocol.decodeModel(json, GuiAck::class.java)
    fun encodeError(value: ProtocolError): String = protocol.encodeModel(ErrorResponse(value))
    fun decodeError(json: String): ProtocolError {
        val value = root(json, setOf("error"))["error"] ?: invalid("error is required")
        val error = root(value.toString(), setOf("code", "message", "retryable"))
        if (!error.has("code") || !error.has("message")) invalid("Error requires code/message")
        return protocol.decodeModel(value.toString(), ProtocolError::class.java)
    }

    fun encodePollResult(value: PollResult): String {
        if ((value.status == PollStatus.COMMAND) != (value.command != null)) invalid("COMMAND requires exactly one frame")
        return JsonObject().apply {
            addProperty("status", value.status.name)
            value.command?.let { add("command", JsonParser.parseString(protocol.encodeFrame(it))) }
        }.toString()
    }
    fun decodePollResult(json: String): PollResult {
        val root = root(json, setOf("status", "command"))
        val value = root["status"]
        // JsonArray.asString unwraps singleton arrays; transport enums require an actual string.
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid("Invalid poll status")
        val status = try { PollStatus.valueOf(value.asString) }
        catch (_: RuntimeException) { invalid("Invalid poll status") }
        val frame = root["command"]?.let { protocol.decodeFrame(it.toString()) }
        if ((status == PollStatus.COMMAND) != (frame != null)) invalid("Invalid poll command/status")
        return PollResult(status, frame)
    }
    fun encodeCompletion(value: GuiCompletionRequest): String = JsonObject().apply {
        add("lease", JsonParser.parseString(encodeLease(value.lease)))
        add("completion", JsonParser.parseString(protocol.encodeCompletion(value.completion)))
    }.toString()
    fun decodeCompletion(json: String): GuiCompletionRequest {
        val root = root(json, setOf("lease", "completion"))
        return GuiCompletionRequest(decodeLease(root["lease"]?.toString() ?: "{}"),
            protocol.decodeCompletion(root["completion"]?.toString() ?: "{}"))
    }
    private fun validateLease(lease: GuiLease) {
        nonBlank(lease.sessionId, "sessionId"); nonBlank(lease.token, "token")
    }
    private fun root(json: String, allowed: Set<String>): JsonObject = try {
        val root = JsonParser.parseString(json).asJsonObject
        if ((root.keySet() - allowed).isNotEmpty()) invalid("Unknown GUI transport fields")
        root
    } catch (e: ProtocolException) { throw e }
    catch (e: RuntimeException) {
        throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Invalid GUI transport object"))
            .also { it.initCause(e) }
    }
}
