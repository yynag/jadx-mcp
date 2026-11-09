package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Schema discovery must stay usable without a live session or command execution. */
class CommandSchemaTest {
    @Test fun `every operation exposes transport metadata and a closed parameter object`() {
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
}
