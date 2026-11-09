package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.Operation
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Schema discovery must stay usable without a live session or command execution. */
class CommandSchemaTest {
    @Test fun `REST schema exposes call context while MCP schema hides caller controls`() {
        Operation.entries.forEach { operation ->
            val schema = CommandSchema.forOperation(operation)
            assertEquals("object", schema.get("type").asString)
            assertFalse(schema.get("additionalProperties").asBoolean)
            val properties = schema.getAsJsonObject("properties")
            val timeout = properties.getAsJsonObject("timeout_ms")
            assertEquals(1, timeout.get("minimum").asInt)
            assertEquals(600_000, timeout.get("maximum").asInt)
            assertEquals(120_000, timeout.get("default").asInt)
            assertEquals(0, properties.getAsJsonObject("project_epoch").get("minimum").asInt)
            assertEquals(1, properties.getAsJsonObject("request_id").get("minLength").asInt)
            val mcpProperties = CommandSchema.forOperation(operation, mcp = true).getAsJsonObject("properties")
            assertTrue(mcpProperties.has("timeout_ms"))
            assertFalse(mcpProperties.has("project_epoch"))
            assertFalse(mcpProperties.has("request_id"))
        }
    }

    @Test fun `required fields keep session scope and operation requirements`() {
        fun required(operation: Operation) = CommandSchema.forOperation(operation)
            .getAsJsonArray("required").map { it.asString }

        assertEquals(listOf("apk_path"), required(Operation.SESSION_CREATE))
        assertEquals(listOf("session_id", "target", "new_name"), required(Operation.RENAME))
        assertTrue(required(Operation.SESSION_LIST).isEmpty())
        val createProperties = CommandSchema.forOperation(Operation.SESSION_CREATE).getAsJsonObject("properties")
        assertEquals(listOf("HEADLESS"), createProperties.getAsJsonObject("type").getAsJsonArray("enum").map { it.asString })
    }

    @Test fun `transport schema edits cannot leak into the next discovery call`() {
        val first = CommandSchema.forOperation(Operation.SEARCH)
        first.getAsJsonObject("properties").remove("timeout_ms")
        first.getAsJsonArray("required").add("transport_only")

        val next = CommandSchema.forOperation(Operation.SEARCH)
        assertTrue(next.getAsJsonObject("properties").has("timeout_ms"))
        assertFalse(next.getAsJsonArray("required").any { it.asString == "transport_only" })
    }

    @Test fun `nested identities and comment anchor requirements are explicit`() {
        fun nested(operation: Operation, field: String) = CommandSchema.forOperation(operation)
            .getAsJsonObject("properties").getAsJsonObject(field)
        fun required(schema: com.google.gson.JsonObject) = schema.getAsJsonArray("required").map { it.asString }

        val rename = nested(Operation.RENAME, "target")
        assertEquals(listOf("class_name"), required(rename))
        assertTrue(rename.getAsJsonObject("properties").getAsJsonObject("member_name").has("minLength"))
        assertTrue(rename.get("description").asString.contains("METHOD or FIELD"))
        val variableMethod = nested(Operation.RENAME_VARIABLE, "method")
        assertEquals(listOf("member_name"), required(variableMethod))
        val comment = CommandSchema.forOperation(Operation.ADD_COMMENT)
        assertEquals(listOf("session_id", "class_name", "comment"), comment.getAsJsonArray("required").map { it.asString })

        // The serialized schema remains ordinary JSON Schema data accepted by the MCP input-schema boundary.
        val serialized = comment.toString()
        assertTrue(JsonParser.parseString(serialized).isJsonObject)
        assertTrue(JsonParser.parseString(serialized).asJsonObject.getAsJsonObject("properties").has("class_name"))
    }
}
