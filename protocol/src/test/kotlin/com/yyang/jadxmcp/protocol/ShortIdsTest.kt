package com.yyang.jadxmcp.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShortIdsTest {
    private class MutableClock(var time: Instant, private val timezone: ZoneId = ZoneId.of("Asia/Shanghai")) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = timezone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }

    @Test fun `uses supplied local timezone and incrementing collision suffixes`() {
        val clock = MutableClock(Instant.parse("2026-11-10T07:18:34Z"))
        val ids = ShortIdGenerator(clock)
        assertEquals("10151834", ids.next())
        assertEquals("10151834-1", ids.next())
        assertEquals("10151834-2", ids.next())
        assertEquals("10151834-5", ids.next { it.endsWith("-3") || it.endsWith("-4") })
    }

    @Test fun `concurrent calls in one second are unique`() {
        val ids = ShortIdGenerator(Clock.fixed(Instant.parse("2026-11-10T07:18:34Z"), ZoneId.of("Asia/Shanghai")))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..512).map { executor.submit<String> { ids.next() } }
            val values = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(512, values.toSet().size)
            assertTrue(values.contains("10151834"))
            assertTrue(values.contains("10151834-511"))
        } finally { executor.shutdownNow() }
    }

    @Test fun `rollback remembers suffixes and an evicted segment does not restart at zero`() {
        val original = Instant.parse("2026-11-10T07:18:34Z")
        val clock = MutableClock(original)
        val ids = ShortIdGenerator(clock)
        val first = ids.next()
        clock.time = original.plusSeconds(1)
        ids.next()
        clock.time = original
        assertEquals("$first-1", ids.next())
        repeat(1_100) { clock.time = original.plusSeconds(it + 2L); ids.next() }
        clock.time = original
        val returned = ids.next()
        assertNotEquals(first, returned)
        assertNotEquals("$first-1", returned)
    }

    @Test fun `month reuse checks live registry rather than retaining every past allocation`() {
        val clock = MutableClock(Instant.parse("2026-11-10T07:18:34Z"))
        val ids = ShortIdGenerator(clock)
        val retained = ids.next()
        clock.time = Instant.parse("2026-12-10T07:18:34Z")
        assertEquals("$retained-1", ids.next { it == retained })
    }

    @Test fun `internal wire identity always includes process marker and unique sequence`() {
        val first = ShortIds.wire()
        val second = ShortIds.wire()
        assertNotEquals(first, second)
        assertTrue(Regex("\\d{8}(?:-\\d+)?-[A-Za-z0-9_-]{8}-[a-z0-9]+").matches(first))
        assertTrue(Regex("\\d{8}(?:-\\d+)?").matches(ShortIds.next()))
    }
}
