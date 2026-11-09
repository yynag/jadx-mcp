package com.yyang.jadxmcp.bridge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BridgeStatusModelTest {
    @Test fun exactWindowBoundariesAndIdleZero() {
        var now = 0L
        val model = BridgeStatusModel({ now })
        model.transition(BridgeState.CONNECTED, 0, 1, "Connected")
        model.received(1)
        fun counts() = model.snapshot().let { listOf(it.requests5s, it.requests10s, it.requests30s) }
        assertEquals(listOf(1, 1, 1), counts())
        now = 4_999; assertEquals(listOf(1, 1, 1), counts())
        now = 5_000; assertEquals(listOf(0, 1, 1), counts())
        now = 10_000; assertEquals(listOf(0, 0, 1), counts())
        now = 30_000; assertEquals(listOf(0, 0, 0), counts())
    }

    @Test fun generationAndEpochResetAndStaleNotificationsAreIgnored() {
        val model = BridgeStatusModel({ 100 })
        model.transition(BridgeState.CONNECTED, 0, 1, "Connected"); model.received(1)
        model.transition(BridgeState.RECONNECTING, 0, 2, "Retry")
        model.received(1); model.received(2)
        assertEquals(0, model.snapshot().requests30s)
        model.transition(BridgeState.CONNECTED, 0, 2, "Connected"); model.received(2)
        assertEquals(1, model.snapshot().requests30s)
        model.transition(BridgeState.DISABLED, 1, 2, "Project changed")
        assertEquals(0, model.snapshot().requests30s)
        assertEquals(1L, model.snapshot().epoch)
    }

    @Test fun negativeMonotonicOriginAndLongIdleDoNotUseWallClock() {
        var now = -100_000L
        val model = BridgeStatusModel({ now })
        model.transition(BridgeState.CONNECTED, 0, 1, "Connected"); model.received(1)
        now += 29_999
        assertEquals(1, model.snapshot().requests30s)
        now += 1_000_000
        assertEquals(0, model.snapshot().requests30s)
    }

    @Test fun boundedCapacityCanBeReusedAfterExpiry() {
        var now = 0L
        val model = BridgeStatusModel({ now }, 2)
        model.transition(BridgeState.CONNECTED, 0, 1, "Connected")
        repeat(2) { model.received(1) }
        assertEquals(2, model.snapshot().requests30s)
        now = 30_000; model.received(1)
        assertEquals(1, model.snapshot().requests30s)
    }
}
