package com.yyang.jadxmcp.session

import com.yyang.jadxmcp.protocol.*

/** All bounds are explicit; transport adapters need not own lifecycle timers. */
data class SessionPolicy(
    val maxSessions: Int = 128,
    val maxPendingPerSession: Int = 128,
    val idleLeaseMillis: Long = 90_000,
    val busyHeartbeatMillis: Long = 90_000,
    val disconnectedRetentionMillis: Long = 300_000,
    val tombstoneMillis: Long = 300_000,
    val maxTombstones: Int = 512,
    val sweepMillis: Long = 20,
    val maxPollMillis: Long = 20_000
) {
    init {
        require(maxSessions > 0 && maxPendingPerSession > 0 && maxTombstones > 0)
        require(listOf(idleLeaseMillis, busyHeartbeatMillis, disconnectedRetentionMillis,
            tombstoneMillis, sweepMillis, maxPollMillis).all { it > 0 })
    }
}

/** The factory owns loading; SessionService owns the returned endpoint and closes it exactly once. */
fun interface HeadlessBackendFactory {
    fun create(request: CreateSessionRequest, context: CallContext): BackendEndpoint
}

internal fun failure(code: ErrorCode, message: String): ProtocolException =
    ProtocolException(ProtocolError(code, message))
