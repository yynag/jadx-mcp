package com.yyang.jadxmcp.bridge

import java.util.ArrayDeque

/** Metadata only: never expose parameters, tokens, source, request IDs or exception messages. */
interface BridgeObserver {
    fun transition(state: BridgeState, epoch: Long, generation: Long, reason: String) {}
    fun received(generation: Long) {}
}

data class BridgeStatusSnapshot(
    val state: BridgeState,
    val reason: String,
    val epoch: Long,
    val generation: Long,
    val requests5s: Int,
    val requests10s: Int,
    val requests30s: Int,
)

/**
 * Exact half-open rolling windows (now-window, now], using a monotonic clock, not wall time.
 * Each generation starts empty. The bridge admits at most maxCommandsPerLease unique commands;
 * the equal capacity here bounds storage without approximation or request-content retention.
 * No listeners: the view samples once a second, so EDT never runs under the bridge's lock.
 */
class BridgeStatusModel(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = BridgePolicy().maxCommandsPerLease,
) : BridgeObserver {
    private val received = ArrayDeque<Long>()
    private var state = BridgeState.DISABLED
    private var reason = "Waiting for project"
    private var epoch = 0L
    private var generation = 0L

    init { require(capacity > 0) }

    @Synchronized override fun transition(state: BridgeState, epoch: Long, generation: Long, reason: String) {
        if (this.generation != generation || this.epoch != epoch) received.clear()
        this.state = state
        this.reason = reason
        this.epoch = epoch
        this.generation = generation
    }

    @Synchronized override fun received(generation: Long) {
        if (this.generation != generation || state != BridgeState.CONNECTED) return
        val now = nowMillis()
        expire(now)
        check(received.size < capacity) { "Bridge status capacity must match command policy" }
        received.addLast(now)
    }

    @Synchronized fun snapshot(): BridgeStatusSnapshot {
        val now = nowMillis()
        expire(now)
        return BridgeStatusSnapshot(state, reason, epoch, generation,
            received.count { now - it < 5_000 }, received.count { now - it < 10_000 }, received.size)
    }

    private fun expire(now: Long) {
        while (received.isNotEmpty() && now - received.first >= 30_000) received.removeFirst()
    }
}
