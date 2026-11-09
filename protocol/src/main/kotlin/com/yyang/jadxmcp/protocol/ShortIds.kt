package com.yyang.jadxmcp.protocol

import java.security.SecureRandom
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/** Human-readable IDs, not authentication credentials or globally persistent identities. */
class ShortIdGenerator(private val clock: Clock = Clock.systemDefaultZone()) {
    private val formatter = DateTimeFormatter.ofPattern("ddHHmmss")
    private val segments = LinkedHashMap<Long, Long>()
    private var highWater = Long.MIN_VALUE
    private var allocations = 0L

    /** Callers with a registry must hold its lock while checking and publishing the returned ID. */
    @Synchronized
    fun next(isReserved: (String) -> Boolean = { false }): String {
        val instant = clock.instant()
        val second = instant.epochSecond
        val base = formatter.format(instant.atZone(clock.zone))
        // Keep recent rollback segments, not an unbounded set of every ID ever issued. An older
        // evicted segment starts above every suffix issued so far rather than reusing its zero.
        var suffix = segments[second] ?: if (second > highWater) 0L else allocations + 1
        highWater = maxOf(highWater, second)
        while (true) {
            val candidate = if (suffix == 0L) base else "$base-$suffix"
            suffix = Math.incrementExact(suffix)
            allocations = Math.incrementExact(maxOf(allocations, suffix))
            if (isReserved(candidate)) continue
            segments[second] = suffix
            while (segments.size > MAX_SEGMENTS) segments.remove(segments.keys.first())
            return candidate
        }
    }

    private companion object { const val MAX_SEGMENTS = 1_024 }
}

object ShortIds {
    private val generator = ShortIdGenerator()
    private val startup = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(6).also {
        SecureRandom().nextBytes(it)
    })
    private val wireSequence = AtomicLong()

    fun next(): String = generator.next()

    /** Internal only: late completions must not match a reused caller ID, even across month changes. */
    fun wire(): String = "${generator.next()}-$startup-${wireSequence.incrementAndGet().toString(36)}"
}
