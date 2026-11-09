package com.yyang.jadxmcp.bridge

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun interface BridgeClock {
    fun nowMillis(): Long
}

enum class BridgeLane { CHANNEL, HEARTBEAT, EXECUTION, CLEANUP }

/** Each lane must be serial, asynchronous, and independent of the other lanes (never the EDT).
 * close() must return without waiting; EXECUTION must remain serial across lease replacements.
 */
interface BridgeRuntime : AutoCloseable {
    fun schedule(lane: BridgeLane, delayMillis: Long = 0, action: () -> Unit)
    override fun close()
}

class ExecutorBridgeRuntime : BridgeRuntime {
    private val lanes = BridgeLane.values().associateWith { lane ->
        Executors.newSingleThreadScheduledExecutor { action ->
            Thread(action, "jadx-mcp-bridge-${lane.name.lowercase()}").apply { isDaemon = true }
        }
    }

    override fun schedule(lane: BridgeLane, delayMillis: Long, action: () -> Unit) {
        lanes.getValue(lane).schedule(action, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        // Never join a worker on unload: JADX and a transport may ignore interruption.
        lanes.forEach { (lane, executor) ->
            if (lane == BridgeLane.CLEANUP) executor.shutdown() else {
                executor.shutdownNow()
            }
        }
    }
}

data class BridgePolicy(
    val heartbeatMillis: Long = 5_000,
    val idlePollMillis: Long = 100,
    val pollTimeoutMillis: Long = 25_000,
    val initialBackoffMillis: Long = 250,
    val maxBackoffMillis: Long = 30_000,
    val maxCommandsPerLease: Int = 10_000
) {
    init {
        require(heartbeatMillis > 0 && idlePollMillis > 0 && pollTimeoutMillis > 0)
        require(initialBackoffMillis > 0 && maxBackoffMillis >= initialBackoffMillis)
        require(maxCommandsPerLease > 0)
    }
}
