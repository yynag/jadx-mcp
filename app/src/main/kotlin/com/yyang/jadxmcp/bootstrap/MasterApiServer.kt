package com.yyang.jadxmcp.bootstrap

import com.yyang.jadxmcp.api.ApiLimits
import com.yyang.jadxmcp.api.masterApi
import com.yyang.jadxmcp.session.SessionService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * HTTP server assembly; the caller owns session lifetime and selects the bind address.
 * There is no general authentication, so LAN bindings require a trusted network.
 * Plugin leases authenticate GUI control only.
 */
class MasterApiServer(
    sessions: SessionService,
    port: Int = 8650,
    // Direct embeddings must require the same explicit LAN opt-in as the CLI runtime.
    internal val host: String = "127.0.0.1",
    limits: ApiLimits = ApiLimits()
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(MasterApiServer::class.java)
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val server = embeddedServer(Netty, port = port, host = host) {
        monitor.subscribe(io.ktor.server.application.ApplicationStarted) { log.info("HTTP server started") }
        masterApi(sessions, limits)
    }
    fun start(wait: Boolean = false) { server.start(wait) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(500, 2_000)
        log.info("HTTP server stopped")
    }
}
