package com.yyang.jadxmcp.protocol

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Versioned, explicit polymorphic codec. Models use all-default constructors so Gson preserves
 * defaults, but every decoded request is validated. Structural checks reject Gson's otherwise
 * permissive string/number/boolean coercions. Optional nulls are omitted, not sent as JSON null.
 * No class names from untrusted input are loaded: only the Operation registry selects types.
 * Frames carry the immutable request deadline and project fence; transports may add cancellation,
 * but decoding does not extend or reinterpret either boundary.
 */
class ProtocolCodec {
    private val naming = FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES
    private val gson = GsonBuilder().setFieldNamingPolicy(naming).disableHtmlEscaping().create()

    internal fun encodeModel(value: Any): String = gson.toJson(value)
    internal fun <T> decodeModel(json: String, type: Class<T>): T = guarded {
        val root = objectRoot(json)
        checkShape(root, type, "payload")
        gson.fromJson(root, type)
    }

    fun encodeRequest(request: OperationRequest): String = guarded {
        checkRequest(request)
        envelope(request.operation, "params", gson.toJsonTree(request)).toString()
    }

    fun decodeRequest(json: String): OperationRequest = guarded {
        val root = objectRoot(json)
        keys(root, setOf("version", "operation", "params"))
        val operation = operation(root)
        val params = root["params"] ?: invalid("params is required")
        checkShape(params, operation.requestType, "params")
        val request = gson.fromJson(params, operation.requestType)
        // An absent comment must not accidentally become the default empty string (delete).
        if (operation == Operation.ADD_COMMENT && !params.asJsonObject.has("comment")) invalid("comment is required")
        checkRequest(request)
        request
    }

    fun encodeResult(operation: Operation, result: OperationResult): String = guarded {
        if (!operation.resultType.isInstance(result)) invalid("Wrong result type for ${operation.wireName}")
        envelope(operation, "result", gson.toJsonTree(result)).toString()
    }

    fun decodeResult(operation: Operation, json: String): OperationResult = guarded {
        val root = objectRoot(json)
        keys(root, setOf("version", "operation", "result"))
        if (operation(root) != operation) invalid("Result operation mismatch")
        val result = root["result"] ?: invalid("result is required")
        checkShape(result, operation.resultType, "result")
        gson.fromJson(result, operation.resultType)
    }

    fun encodeFrame(frame: RequestFrame): String = guarded {
        val root = JsonParser.parseString(encodeRequest(frame.request)).asJsonObject
        root.addProperty("request_id", frame.context.requestId)
        root.addProperty("project_epoch", frame.context.projectEpoch)
        root.addProperty("deadline_epoch_millis", frame.context.deadlineEpochMillis)
        root.toString()
    }

    fun decodeFrame(json: String, cancellation: CancellationSignal = CancellationSignal.NONE): RequestFrame = guarded {
        val root = objectRoot(json)
        keys(root, setOf("version", "operation", "params", "request_id", "project_epoch", "deadline_epoch_millis"))
        val context = CallContext(
            string(root["request_id"], "request_id"),
            long(root["project_epoch"], "project_epoch"),
            long(root["deadline_epoch_millis"], "deadline_epoch_millis"), cancellation
        )
        val requestRoot = root.deepCopy()
        listOf("request_id", "project_epoch", "deadline_epoch_millis").forEach(requestRoot::remove)
        RequestFrame(decodeRequest(requestRoot.toString()), context)
    }

    fun encodeCompletion(completion: CommandCompletion): String = guarded {
        val root = if (completion.result != null) {
            JsonParser.parseString(encodeResult(completion.operation, completion.result)).asJsonObject
        } else envelope(completion.operation, "error", gson.toJsonTree(completion.error))
        root.addProperty("request_id", completion.requestId)
        root.addProperty("project_epoch", completion.projectEpoch)
        root.toString()
    }

    fun decodeCompletion(json: String): CommandCompletion = guarded {
        val root = objectRoot(json)
        keys(root, setOf("version", "operation", "result", "error", "request_id", "project_epoch"))
        val operation = operation(root)
        val requestId = string(root["request_id"], "request_id")
        val epoch = long(root["project_epoch"], "project_epoch")
        if (root.has("result") == root.has("error")) invalid("Exactly one result/error is required")
        val result = root["result"]?.let {
            checkShape(it, operation.resultType, "result")
            gson.fromJson(it, operation.resultType)
        }
        val error = root["error"]?.let {
            checkShape(it, ProtocolError::class.java, "error")
            if (!it.asJsonObject.has("code") || !it.asJsonObject.has("message")) invalid("Error requires code/message")
            gson.fromJson(it, ProtocolError::class.java)
        }
        CommandCompletion(requestId, epoch, operation, result, error)
    }

    /** Snapshot serialization is neutral about who initiates synchronization or owns storage. */
    fun encodeSnapshot(snapshot: ProjectDataSnapshot): String = guarded {
        snapshot.validate()
        gson.toJson(snapshot)
    }

    fun decodeSnapshot(json: String): ProjectDataSnapshot = guarded {
        val root = objectRoot(json)
        checkShape(root, ProjectDataSnapshot::class.java, "snapshot")
        gson.fromJson(root, ProjectDataSnapshot::class.java).also { it.validate() }
    }

    private fun checkRequest(request: OperationRequest) {
        if (!request.operation.requestType.isInstance(request)) invalid("Wrong request type")
        request.validate()
    }

    private fun envelope(operation: Operation, key: String, payload: JsonElement) = JsonObject().apply {
        addProperty("version", 1)
        addProperty("operation", operation.wireName)
        add(key, payload)
    }

    private fun operation(root: JsonObject): Operation {
        if (long(root["version"], "version") != 1L) invalid("Unsupported protocol version")
        return Operation.fromWireName(string(root["operation"], "operation"))
    }

    private fun objectRoot(json: String): JsonObject {
        val value = JsonParser.parseString(json)
        if (!value.isJsonObject) invalid("Expected JSON object")
        return value.asJsonObject
    }

    private fun keys(value: JsonObject, allowed: Set<String>) {
        val unknown = value.keySet() - allowed
        if (unknown.isNotEmpty()) invalid("Unknown fields: ${unknown.joinToString()}")
    }

    private fun string(value: JsonElement?, path: String): String {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid("$path must be a string")
        return value.asString
    }

    private fun long(value: JsonElement?, path: String): Long {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) invalid("$path must be an integer")
        return try { value.asBigDecimal.longValueExact() } catch (_: ArithmeticException) { invalid("$path is not a 64-bit integer") }
    }

    /**
     * Validate before Gson can coerce primitives or silently discard unknown fields.
     * Contract models use declared instance fields (no inherited payload state), all-default
     * constructors, and collections with concrete element types. Maps and other generic shapes
     * need explicit support here before being added to the wire contract; Kotlin nullability
     * is not inferred from reflection, so absent-field semantics remain in model validation.
     */
    private fun checkShape(value: JsonElement, type: Type, path: String) {
        if (value.isJsonNull) invalid("$path must be omitted instead of null")
        if (type is WildcardType) return checkShape(value, type.upperBounds.first(), path)
        if (type is ParameterizedType) {
            return checkCollectionShape(value, type, path)
        }
        val cls = type as? Class<*> ?: invalid("Unsupported contract type at $path")
        when {
            cls == String::class.java -> string(value, path)
            cls == Boolean::class.javaPrimitiveType || cls == Boolean::class.javaObjectType ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) invalid("$path must be boolean")
            cls == Int::class.javaPrimitiveType || cls == Int::class.javaObjectType -> {
                if (long(value, path) !in Int.MIN_VALUE..Int.MAX_VALUE) invalid("$path is not a 32-bit integer")
            }
            cls == Long::class.javaPrimitiveType || cls == Long::class.javaObjectType -> long(value, path)
            cls.isEnum -> if (cls.enumConstants.none { (it as Enum<*>).name == string(value, path) }) invalid("Unknown enum at $path")
            else -> checkObjectShape(value, cls, path)
        }
    }

    private fun checkCollectionShape(value: JsonElement, type: ParameterizedType, path: String) {
        if (!Collection::class.java.isAssignableFrom(type.rawType as Class<*>) || !value.isJsonArray) {
            invalid("$path must be an array")
        }
        val elementType = type.actualTypeArguments.first()
        value.asJsonArray.forEach { checkShape(it, elementType, "$path[]") }
    }

    private fun checkObjectShape(value: JsonElement, modelClass: Class<*>, path: String) {
        if (!value.isJsonObject) invalid("$path must be an object")
        val fieldsByWireName = modelClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .associateBy { naming.translateName(it) }
        keys(value.asJsonObject, fieldsByWireName.keys)
        value.asJsonObject.entrySet().forEach { (name, child) ->
            checkShape(child, fieldsByWireName.getValue(name).genericType, "$path.$name")
        }
    }

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (e: ProtocolException) {
        throw e
    } catch (e: RuntimeException) {
        throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT,
            "Invalid protocol payload: ${e.message ?: e.javaClass.simpleName}")).also { it.initCause(e) }
    }
}
