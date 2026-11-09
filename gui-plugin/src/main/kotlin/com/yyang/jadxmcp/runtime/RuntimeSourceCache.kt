package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.atomic.AtomicLong

/** Nonblocking native-event callback: no EDT wait, decompiler lease, disk I/O, or codeData mutation. */
class GuiGeneration {
    private val value = AtomicLong()
    fun current(): Long = value.get()
    fun invalidate() { value.incrementAndGet() }
}

/**
 * Measurable bounded rendered-source cache. Stats describe ONLY these entries, not hidden JADX,
 * resource, search or JVM caches. Native JadxSourceService has no public cache-statistics API.
 * Compute outside the short monitor; generation-fenced publication prevents stale results.
 */
class RuntimeSourceCache(
    private val delegate: SourceService,
    private val generation: GuiGeneration,
    private val capacity: Int = 64
) : SourceService {
    private data class Entry(val source: SourceResult, val bytes: Long)
    private val monitor = Any()
    private val entries = LinkedHashMap<DecompileRequest, Entry>(16, 0.75f, true)
    private var observed = generation.current()
    private var hits = 0L
    private var misses = 0L

    init { require(capacity > 0) }

    private fun align() {
        if (observed != generation.current()) {
            entries.clear(); hits = 0; misses = 0; observed = generation.current()
        }
    }

    override fun decompile(request: DecompileRequest, context: CallContext): SourceResult {
        request.validate(); context.checkActive()
        val key = request.copy(sessionId = "runtime-cache")
        val token: Long
        synchronized(monitor) {
            align(); token = observed
            entries[key]?.let { hits++; return it.source }
            misses++
        }
        val result = delegate.decompile(request, context)
        context.checkActive()
        synchronized(monitor) {
            if (generation.current() != token) runtimeFailure(ErrorCode.CONFLICT, "GUI source generation changed")
            entries[key] = Entry(result, result.text.content.toByteArray(Charsets.UTF_8).size.toLong())
            if (entries.size > capacity) entries.remove(entries.keys.first())
        }
        return result
    }

    fun stats(): CacheStatsResult = synchronized(monitor) {
        align()
        val bytes = entries.values.sumOf { it.bytes }
        CacheStatsResult(entries.size, hits, misses, bytes, bytes)
    }
}

internal fun runtimeFailure(code: ErrorCode, message: String): Nothing =
    throw ProtocolException(ProtocolError(code, message))
