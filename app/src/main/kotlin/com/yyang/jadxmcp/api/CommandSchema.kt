package com.yyang.jadxmcp.api

import com.google.gson.FieldNamingPolicy
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.yyang.jadxmcp.protocol.*
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Transport-facing parameter discovery, independent of session state and command execution.
 * Reflection describes DTO shapes, not all validation rules: ProtocolCodec remains authoritative
 * for defaults, conditional requirements and request validation. Return a fresh tree per call so
 * a transport can adapt its schema without changing another transport's view.
 */
internal object CommandSchema {
    fun forOperation(operation: Operation, mcp: Boolean = false): JsonObject = objectSchema(operation.requestType).apply {
        val properties = getAsJsonObject("properties")
        if (operation == Operation.SESSION_CREATE) {
            properties.getAsJsonObject("type").add("enum", JsonArray().apply { add("HEADLESS") })
        }
        addCallContextProperties(properties, mcp)
        add("required", JsonArray().apply { requiredParameters(operation).forEach(::add) })
    }

    /** These transport options are removed before decoding the typed operation request. */
    private fun addCallContextProperties(properties: JsonObject, mcp: Boolean) {
        properties.add("timeout_ms", JsonObject().apply {
            addProperty("type", "integer")
            addProperty("minimum", 1)
            addProperty("maximum", 600_000)
            addProperty("default", 120_000)
            addProperty("description", "Whole-call timeout in milliseconds, including queue time")
        })
        if (!mcp) {
            properties.add("project_epoch", JsonObject().apply {
                addProperty("type", "integer")
                addProperty("minimum", 0)
                addProperty("description", "Optional project fence; defaults to the current epoch")
            })
            properties.add("request_id", JsonObject().apply {
                addProperty("type", "string")
                addProperty("minLength", 1)
            })
        }
    }

    private fun requiredParameters(operation: Operation): List<String> = buildList {
        if (SessionScoped::class.java.isAssignableFrom(operation.requestType)) add("session_id")
        addAll(when (operation) {
            Operation.SESSION_CREATE -> listOf("apk_path")
            Operation.SEARCH -> listOf("term")
            Operation.XREF -> listOf("target")
            Operation.RENAME -> listOf("target", "new_name")
            Operation.GET_METHOD_BY_NAME -> listOf("method_name")
            Operation.RENAME_PACKAGE -> listOf("old_package_name", "new_package_name")
            Operation.RENAME_VARIABLE -> listOf("method", "variable_name", "new_name")
            Operation.ADD_COMMENT -> listOf("class_name", "comment")
            else -> emptyList()
        })
    }

    private fun objectSchema(type: Class<*>): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        addProperty("additionalProperties", false)
        val properties = JsonObject()
        type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }.forEach { field ->
            properties.add(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES.translateName(field), typeSchema(field.genericType))
        }
        add("properties", properties)
        if (type == PageRequest::class.java) {
            properties.getAsJsonObject("offset").addProperty("minimum", 0)
            properties.getAsJsonObject("count").apply {
                addProperty("minimum", 1)
                addProperty("maximum", 10_000)
                addProperty("default", 50)
            }
        }
        if (type == SymbolRef::class.java) {
            add("required", JsonArray().apply { add("class_name") })
            properties.getAsJsonObject("class_name").addProperty("minLength", 1)
            properties.getAsJsonObject("member_name").addProperty("minLength", 1)
        }
        if (type == SearchBudget::class.java) {
            properties.getAsJsonObject("max_decompile").apply {
                addProperty("minimum", 0)
                addProperty("default", 50)
                addProperty("description", "Unique source-access attempts, including cache hits; not new decompilations. Zero forbids all source reads, including cached source.")
            }
            properties.getAsJsonObject("max_scan").apply {
                addProperty("minimum", 1)
                addProperty("default", 500)
                addProperty("description", "Maximum candidate classes examined by budgeted searches")
            }
        }
        if (type == RenameRequest::class.java) {
            properties.getAsJsonObject("target").addProperty("description", "Requires member_name when target_type is METHOD or FIELD; CLASS only requires class_name")
        }
        if (type == RenameVariableRequest::class.java) {
            properties.getAsJsonObject("method").add("required", JsonArray().apply { add("member_name") })
        }
        if (type == AddCommentRequest::class.java) {
            properties.getAsJsonObject("class_name").addProperty("minLength", 1)
            properties.getAsJsonObject("comment").addProperty("description", "Required; empty string removes the comment")
            properties.getAsJsonObject("method_name").addProperty("description", "Optional comment anchor; method_signature requires method_name; field_name conflicts with method_name and line")
            properties.getAsJsonObject("field_name").addProperty("description", "Optional field anchor; conflicts with method_name and line")
            properties.getAsJsonObject("line").addProperty("minimum", 1)
        }
    }

    private fun typeSchema(type: Type): JsonObject {
        if (type is WildcardType) return typeSchema(type.upperBounds.first())
        if (type is ParameterizedType) return JsonObject().apply {
            addProperty("type", "array")
            add("items", typeSchema(type.actualTypeArguments.first()))
        }
        val rawType = type as Class<*>
        return when {
            rawType == String::class.java -> primitiveSchema("string")
            rawType == Boolean::class.javaPrimitiveType || rawType == Boolean::class.javaObjectType -> primitiveSchema("boolean")
            rawType == Int::class.javaPrimitiveType || rawType == Int::class.javaObjectType ||
                rawType == Long::class.javaPrimitiveType || rawType == Long::class.javaObjectType -> primitiveSchema("integer")
            rawType.isEnum -> primitiveSchema("string").apply {
                add("enum", JsonArray().apply { rawType.enumConstants.forEach { add((it as Enum<*>).name) } })
            }
            else -> objectSchema(rawType)
        }
    }

    private fun primitiveSchema(type: String): JsonObject = JsonObject().apply { addProperty("type", type) }
}
