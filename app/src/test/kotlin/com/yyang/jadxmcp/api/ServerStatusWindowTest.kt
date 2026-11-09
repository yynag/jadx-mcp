package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.SessionInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ServerStatusWindowTest {
    @Test fun monotonicBucketsExpireAtTenThirtyAndSixtySecondsAndReuseSlots() {
        var nanos = 0L
        var wall = 1_700_000_000_000L
        val status = ServerStatus({ nanos }, { wall })
        val first = status.initialized("secret-sdk-id")
        assertEquals(first, status.initialized("secret-sdk-id"))
        assertEquals("$first-1", status.initialized("second-secret"))
        assertTrue(first.matches(Regex("\\d{8}")))
        status.toolCalled("secret-sdk-id")
        fun at(second: Long, expected: CallCounts) {
            nanos = second * 1_000_000_000
            assertEquals(expected, status.counts())
        }
        wall -= 100_000_000 // Wall-clock adjustments cannot extend call history.
        at(9, CallCounts(1, 1, 1))
        at(10, CallCounts(0, 1, 1))
        at(29, CallCounts(0, 1, 1))
        at(30, CallCounts(0, 0, 1))
        status.closed("secret-sdk-id")
        at(59, CallCounts(0, 0, 1))
        at(60, CallCounts(0, 0, 0))
        status.toolCalled("second-secret")
        at(60, CallCounts(1, 1, 1))
        at(1_000_000, CallCounts(0, 0, 0))
    }

    @Test fun statusProjectionHasOnlyPublicSessionFieldsAndNoSdkIdsOrDirectories() {
        val status = ServerStatus({ 0 }, { 0 })
        val alias = status.initialized("private-sdk-token")
        status.toolCalled("private-sdk-token")
        val text = status.render(listOf(
            SessionInfo(sessionId = "s_public", name = "/private/secret/project.apk"),
            SessionInfo(sessionId = "s_windows", name = "C:\\private\\other.apk\nforged")))
        assertTrue(text.contains("server: running"))
        assertTrue(text.contains("$alias 10s=1 30s=1 60s=1"))
        assertTrue(text.contains("name=project.apk"))
        assertTrue(text.contains("name=other.apkforged"))
        listOf("private", "secret", "token", "projectEpoch", "capabilities", "C:").forEach {
            assertFalse(text.contains(it), text)
        }
    }
}
