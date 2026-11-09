package com.yyang.jadxmcp.analysis.cache

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded process-local LRU. The leader computes inline; followers never spawn native work. */
class GenerationCache<K : Any, V : Any>(private val capacity: Int = 64) {
    init { require(capacity > 0) }
    private val monitor = Any()
    private var revision: String? = null
    private var generation = 0L
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private val flights = mutableMapOf<Pair<Long, K>, CompletableFuture<V>>()

    fun invalidate() = synchronized(monitor) {
        generation++
        values.clear()
        // Old waiters retain their future, but new calls can never join an old generation.
        flights.clear()
    }

    fun get(key: K, revision: String, context: CallContext, compute: () -> V): V {
        context.checkActive()
        val flight: CompletableFuture<V>
        val token: Pair<Long, K>
        val leader: Boolean
        synchronized(monitor) {
            if (this.revision != revision) {
                invalidate()
                this.revision = revision
            }
            values[key]?.let { return it }
            token = generation to key
            val existing = flights[token]
            leader = existing == null
            flight = existing ?: CompletableFuture<V>().also { flights[token] = it }
        }
        if (leader) {
            try {
                val value = compute()
                context.checkActive()
                synchronized(monitor) {
                    if (token.first == generation) {
                        values[key] = value
                        if (values.size > capacity) values.remove(values.keys.first())
                    }
                }
                flight.complete(value)
            } catch (failure: Throwable) {
                flight.completeExceptionally(failure)
            } finally {
                synchronized(monitor) { flights.remove(token, flight) }
            }
        }
        while (true) {
            context.checkActive()
            try {
                val value = flight.get(25, TimeUnit.MILLISECONDS)
                context.checkActive()
                synchronized(monitor) {
                    if (token.first != generation) throw ProtocolException(
                        ProtocolError(ErrorCode.CONFLICT, "Source generation changed")
                    )
                }
                return value
            } catch (_: TimeoutException) {
                // Cooperative follower cancellation does not cancel the leader or its native lease.
            } catch (failure: ExecutionException) {
                throw (failure.cause ?: failure)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProtocolException(ProtocolError(ErrorCode.CANCELLED, "Cache wait interrupted"))
            }
        }
    }
}
