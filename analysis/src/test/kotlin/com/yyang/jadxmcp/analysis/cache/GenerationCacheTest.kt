package com.yyang.jadxmcp.analysis.cache

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class GenerationCacheTest {
    private fun context(signal: CancellationSignal = CancellationSignal.NONE) =
        CallContext("cache-test", 0, System.currentTimeMillis() + 30_000, signal)

    @Test
    fun sameKeySingleFlightAndRevisionInvalidation() {
        val cache = GenerationCache<String, String>(2)
        val count = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val calls = (1..6).map {
                executor.submit<String> { cache.get("key", "0", context()) {
                    count.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "old"
                } }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            calls.forEach { assertEquals("old", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, count.get())
            assertEquals("new", cache.get("key", "1", context()) { count.incrementAndGet(); "new" })
            assertEquals(2, count.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun invalidationFencesLateResultAndFailuresAreNotCached() {
        val cache = GenerationCache<String, String>()
        val error = assertThrows(ProtocolException::class.java) {
            cache.get("key", "0", context()) { cache.invalidate(); "stale" }
        }
        assertEquals(ErrorCode.CONFLICT, error.error.code)
        assertEquals("fresh", cache.get("key", "0", context()) { "fresh" })
        assertThrows(IllegalStateException::class.java) { cache.get("failure", "0", context()) { error("failed") } }
        assertEquals("retried", cache.get("failure", "0", context()) { "retried" })
        val cancellation = CancellationSource().apply { cancel() }
        assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
            cache.get("key", "0", context(cancellation)) { error("Must not run") }
        }.error.code)
    }

    @Test
    fun cancelledFollowerDoesNotCancelLeaderOrStartAnotherComputation() {
        val cache = GenerationCache<String, String>()
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checked = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        try {
            val leader = executor.submit<String> {
                cache.get("key", "0", context()) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "leader"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val follower = executor.submit<ErrorCode> {
                try {
                    cache.get("key", "0", context(CancellationSignal {
                        checked.countDown()
                        cancelled.get()
                    })) { error("Follower must not compute") }
                    error("Expected cancellation")
                } catch (failure: ProtocolException) { failure.error.code }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertEquals(ErrorCode.CANCELLED, follower.get(5, TimeUnit.SECONDS))
            assertFalse(leader.isDone)
            release.countDown()
            assertEquals("leader", leader.get(5, TimeUnit.SECONDS))
            assertEquals("leader", cache.get("key", "0", context()) { error("Must use cache") })
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
