package com.yyang.jadxmcp.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yyang.jadxmcp.protocol.Operation

/** MCP-only projection: keep the typed REST/GUI envelope and producers unchanged. */
object McpOutput {
    fun render(operation: Operation, encodedResult: String): String {
        val envelope = JsonParser.parseString(encodedResult).asJsonObject
        // Registry throws failures; the adapter's GuiTransportCodec supplies this error shape.
        // isError belongs to CallToolResult, not this text payload.
        envelope.objectAt("error")?.let { error ->
            return JsonObject().apply {
                add("error", JsonObject().apply {
                    listOf("code", "message", "retryable").forEach { key -> error[key]?.let { add(key, it) } }
                })
            }.toString()
        }
        val result = envelope.getAsJsonObject("result")
        when (operation) {
            Operation.SESSION_LIST -> return JsonArray().apply {
                result.getAsJsonArray("sessions").forEach { add(session(it.asJsonObject, summary = true)) }
            }.toString()
            Operation.SESSION_CREATE -> return session(result, summary = true).toString()
            Operation.SESSION_CLOSE -> result.keep("closed")
            Operation.META_MANIFEST -> {
                if (result.hasValue("xml")) result.drop("components", "component_type")
                else result.drop("xml", "component_type")
            }
            Operation.META_CLASS -> {
                // MetadataService returns either a class detail or a paged list, never both.
                if (result.hasValue("class_name")) result.drop("classes", "page")
                else { result.drop("class_name", "methods", "fields"); page(result) }
            }
            Operation.DECOMPILE, Operation.FETCH_CURRENT_CLASS, Operation.GET_METHOD_BY_NAME -> source(result)
            Operation.RESOURCE -> {
                when (result["action"].asString) {
                    "LIST" -> { result.drop("file", "strings", "resource"); page(result) }
                    "STRINGS" -> { result.drop("files", "file", "resource"); page(result) }
                    "FILE" -> {
                        result.drop("files", "strings", "resource", "page")
                        result.objectAt("file")?.objectAt("text")?.let(::text)
                    }
                    "ID" -> result.drop("files", "file", "strings", "page")
                }
                result.remove("action")
            }
            Operation.XREF -> {
                page(result)
                result.getAsJsonArray("references").forEach { hit ->
                    // snippet is an excerpt; method_code is full method source. Keep both.
                    hit.asJsonObject.objectAt("method_code")?.let(::text)
                }
            }
            Operation.SEARCH -> { result.remove("string_mode"); page(result) }
            Operation.GET_MAIN_APPLICATION_CLASSES_CODE -> {
                result.getAsJsonArray("classes").forEach { source(it.asJsonObject) }
                page(result)
            }
            Operation.GET_PACKAGE_TREE -> {
                val packages = result.getAsJsonArray("packages")
                // GuiQueryAdapter never computes this heuristic; false is only the DTO default.
                packages.forEach { it.asJsonObject.remove("likely_library") }
                if (result["truncated"]?.asBoolean == false &&
                    result["total_packages"]?.asInt == packages.size()) result.remove("total_packages")
            }
            Operation.CLEAR_CACHE -> result.keep("cleared")
            Operation.RENAME, Operation.RENAME_PACKAGE, Operation.RENAME_VARIABLE, Operation.ADD_COMMENT ->
                result.objectAt("state")?.remove("project_path")
            Operation.META_SUMMARY, Operation.GET_SELECTED_TEXT, Operation.GET_MAIN_APPLICATION_CLASSES_NAMES,
            Operation.GET_CACHE_STATS, Operation.LIST_COMMENTS, Operation.DEBUG_GET_STACK_FRAMES,
            Operation.DEBUG_GET_THREADS, Operation.DEBUG_GET_VARIABLES -> Unit
        }
        return result.toString()
    }

    private fun session(result: JsonObject, summary: Boolean): JsonObject {
        if (result["backend"]?.asString == "HEADLESS") {
            // Enforce SessionService's APK-basename convention even for a path-bearing snapshot.
            result["name"]?.let { result.addProperty("name", it.asString.replace('\\', '/').substringAfterLast('/')) }
        }
        // Epoch remains a REST/session-internal fence, never part of the MCP session summary.
        if (summary) result.keep("session_id", "backend", "name", "state")
        else {
            result.drop("created_at", "last_seen_at")
            result.objectAt("capabilities")?.remove("backend")
        }
        return result
    }

    private fun source(result: JsonObject) { result.objectAt("text")?.let(::text) }

    private fun text(result: JsonObject) {
        if (result["truncated"]?.asBoolean != true) result.remove("total_chars")
    }

    private fun page(result: JsonObject) {
        result.objectAt("page")?.let {
            it.drop("offset", "count")
            // Unknown total stays unknown; an empty page object carries no continuation metadata.
            if (it.size() == 0) result.remove("page")
        }
    }

    private fun JsonObject.objectAt(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.hasValue(key: String): Boolean = get(key)?.isJsonNull == false
    private fun JsonObject.drop(vararg keys: String) { keys.forEach(::remove) }
    private fun JsonObject.keep(vararg keys: String) { (keySet().toSet() - keys.toSet()).forEach(::remove) }
}
