package com.yyang.jadxmcp.bootstrap

import com.yyang.jadxmcp.api.ApiLimits
import com.yyang.jadxmcp.api.masterApi
import com.yyang.jadxmcp.session.SessionService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Opt-in assembly only: the legacy Main is unchanged until full integration.
 * Like the existing deployment, this binds to the LAN without general authentication;
 * operators must restrict it to a trusted network. Plugin leases authenticate GUI control only.
 */
class MasterApiServer(
    sessions: SessionService,
    port: Int = 8650,
    host: String = "0.0.0.0",
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
