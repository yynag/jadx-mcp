package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.SessionInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A fixed-size, monotonic, one-second histogram: never retain individual requests. */
internal class ToolCallWindow {
    private val seconds = LongArray(60) { Long.MIN_VALUE }
    private val counts = LongArray(60)

    fun record(second: Long) {
        val slot = Math.floorMod(second, 60)
        if (seconds[slot] != second) { seconds[slot] = second; counts[slot] = 0 }
        counts[slot]++
    }

    fun snapshot(second: Long): CallCounts {
        fun total(window: Int) = counts.indices.sumOf { slot ->
            val age = second - seconds[slot]
            if (seconds[slot] != Long.MIN_VALUE && age in 0 until window.toLong()) counts[slot] else 0L
        }
        return CallCounts(total(10), total(30), total(60))
    }
}

internal data class CallCounts(val last10s: Long, val last30s: Long, val last60s: Long) {
    override fun toString() = "10s=$last10s 30s=$last30s 60s=$last60s"
}

/** SDK identifiers remain private map keys; human aliases never replace transport IDs. */
class ServerStatus internal constructor(
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Long = System::currentTimeMillis
) {
    private data class Client(val alias: String, val calls: ToolCallWindow = ToolCallWindow())
    private val origin = nanoTime()
    private val clients = LinkedHashMap<String, Client>()
    private val global = ToolCallWindow()
    private var aliasSecond = Long.MIN_VALUE
    private var aliasSequence = 0L
    private val aliasFormat = DateTimeFormatter.ofPattern("ddHHmmss").withZone(ZoneId.systemDefault())
    private fun second() = (nanoTime() - origin) / 1_000_000_000L

    @Synchronized internal fun initialized(sdkSessionId: String): String = clients.getOrPut(sdkSessionId) {
        val now = wallTime()
        val second = Math.floorDiv(now, 1000)
        if (second == aliasSecond) aliasSequence++ else { aliasSecond = second; aliasSequence = 0 }
        val base = aliasFormat.format(Instant.ofEpochMilli(now))
        Client(if (aliasSequence == 0L) base else "$base-$aliasSequence")
    }.alias

    @Synchronized internal fun closed(sdkSessionId: String) { clients.remove(sdkSessionId) }

    // Record at entry, not success: validation/backend errors are still business calls.
    // Global history has its own buckets and therefore survives client DELETE.
    @Synchronized internal fun toolCalled(sdkSessionId: String) {
        val now = second()
        global.record(now)
        clients[sdkSessionId]?.calls?.record(now)
    }

    @Synchronized internal fun counts(): CallCounts = global.snapshot(second())

    @Synchronized fun render(sessions: List<SessionInfo>): String = buildString {
        val now = second()
        appendLine("server: running")
        appendLine("tools/call: ${global.snapshot(now)}")
        appendLine("sessions: ${sessions.size}")
        sessions.forEach { session ->
            appendLine("  ${safeText(session.sessionId)} backend=${session.backend} name=${basename(session.name)} state=${session.state}")
        }
        appendLine("clients: ${clients.size} (initialized)")
        clients.values.forEach { appendLine("  ${it.alias} ${it.calls.snapshot(now)}") }
    }

    private fun basename(value: String) = safeText(value.replace('\\', '/').substringAfterLast('/'))
    private fun safeText(value: String) = value.filterNot { it.isISOControl() }.take(120)
}
