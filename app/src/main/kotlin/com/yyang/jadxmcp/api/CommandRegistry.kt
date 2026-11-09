package com.yyang.jadxmcp.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.SessionService
import java.util.UUID

/** Both public transports consume this registry; validation always goes through ProtocolCodec.
 * timeout_ms covers queueing and execution from the caller's frame deadline; project_epoch is an
 * optimistic fence and is never silently replaced when explicitly supplied by the caller.
 */
class CommandRegistry(private val sessions: SessionService, val maxArgumentBytes: Int = 2 * 1024 * 1024) {
    private val log = org.slf4j.LoggerFactory.getLogger(CommandRegistry::class.java)
    private val codec = ProtocolCodec()
    val operations: List<Operation> = Operation.entries.toList()

    fun execute(operation: Operation, arguments: String, cancellation: CancellationSignal = CancellationSignal.NONE): String {
        val frame = decode(operation, arguments, cancellation)
        frame.context.checkActive()
        val started = System.nanoTime()
        log.debug("Request started requestId={} operation={}", frame.context.requestId, operation.wireName)
        try {
            return codec.encodeResult(operation, sessions.execute(frame.request, frame.context))
        } catch (error: Exception) {
            // Exception messages may contain absolute paths or user source; only stable codes/types
            // belong in operational logs. Cancellation/validation is recoverable, not server failure.
            val code = (error as? ProtocolException)?.error?.code
            if (code != null && code != ErrorCode.INTERNAL)
                log.warn("Request rejected requestId={} operation={} code={}", frame.context.requestId, operation.wireName, code)
            else log.error("Request failed requestId={} operation={} type={}", frame.context.requestId,
                operation.wireName, error.javaClass.simpleName)
            throw error
        } finally {
            log.debug("Request completed requestId={} operation={} elapsedMs={}", frame.context.requestId,
                operation.wireName, (System.nanoTime() - started) / 1_000_000)
        }
    }

    fun decode(operation: Operation, arguments: String, cancellation: CancellationSignal = CancellationSignal.NONE): RequestFrame {
        if (arguments.toByteArray(Charsets.UTF_8).size > maxArgumentBytes) fail(ErrorCode.INVALID_ARGUMENT, "Arguments exceed size limit")
        val params = try { JsonParser.parseString(arguments).asJsonObject.deepCopy() }
        catch (_: RuntimeException) { fail(ErrorCode.INVALID_ARGUMENT, "Arguments must be a JSON object") }

        // Strip transport metadata first: ProtocolCodec must only receive operation parameters.
        val timeoutMillis = removeInteger(params, "timeout_ms", 120_000)
        if (timeoutMillis !in 1..600_000) fail(ErrorCode.INVALID_ARGUMENT, "timeout_ms must be 1..600000")
        val explicitEpoch = params.has("project_epoch")
        val requestedEpoch = removeInteger(params, "project_epoch", -1)
        if (explicitEpoch && requestedEpoch < 0) fail(ErrorCode.INVALID_ARGUMENT, "project_epoch must be non-negative")
        val requestId = params.remove("request_id")?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) fail(ErrorCode.INVALID_ARGUMENT, "request_id must be a string")
            it.asString
        } ?: UUID.randomUUID().toString()
        val request = codec.decodeRequest(JsonObject().apply {
            addProperty("version", 1)
            addProperty("operation", operation.wireName)
            add("params", params)
        }.toString())
        // If no fence is supplied, snapshot the current epoch, never overwrite an explicit fence.
        val epoch = when {
            requestedEpoch >= 0 -> requestedEpoch
            request is BackendRequest -> sessions.get(request.sessionId).projectEpoch
            else -> 0
        }
        return RequestFrame(request, CallContext(requestId, epoch, System.currentTimeMillis() + timeoutMillis, cancellation))
    }

    private fun removeInteger(params: JsonObject, name: String, default: Long): Long {
        val value = params.remove(name) ?: return default
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) fail(ErrorCode.INVALID_ARGUMENT, "$name must be an integer")
        return try { value.asBigDecimal.longValueExact() }
        catch (_: RuntimeException) { fail(ErrorCode.INVALID_ARGUMENT, "$name must be a 64-bit integer") }
    }

    /** Parameter enums are exact uppercase names; defaults and conditional targets remain validated by the typed request. */
    fun schema(operation: Operation): JsonObject = CommandSchema.forOperation(operation)

    fun description(operation: Operation): String = buildString {
        append(operation.wireName.replace('_', ' '))
        append(". Enums use the exact UPPERCASE values in the schema. ")
        if (operation.scope == OperationScope.GUI) append("GUI-only. ")
        append(when (operation) {
            Operation.DECOMPILE -> "JAVA/SMALI/METHOD require class_name; METHOD also requires method_name. Signatures disambiguate overloads."
            Operation.RESOURCE -> "FILE requires file_name; ID requires resource_id."
            Operation.XREF, Operation.RENAME -> "target.class_name is required; METHOD/FIELD also require target.member_name."
            Operation.SEARCH -> "STRING defaults to DEX_CONSTANTS; SOURCE_TEXT must be explicitly requested. " +
                "budget.max_decompile and result stats.decompiled count unique source-access attempts, including cache hits, not newly generated code. " +
                "A zero max_decompile disables all source reads, including cached source."
            Operation.ADD_COMMENT -> "An explicit empty comment deletes it; line is 1-based. GUI persistence is reported separately."
            Operation.RENAME_VARIABLE -> "method.class_name and method.member_name identify the method; use its descriptor for overloads and reg/ssa for ambiguous variables."
            Operation.SESSION_CLOSE -> "GUI close disables the MCP session, not the GUI application. clear_cache may be unsupported by persistence policy."
            else -> "Parameters and results use the typed protocol; no legacy apk_id aliases."
        })
    }

    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(ProtocolError(code, message))
}
