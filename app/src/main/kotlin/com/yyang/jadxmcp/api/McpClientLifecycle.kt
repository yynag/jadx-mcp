package com.yyang.jadxmcp.api

import org.slf4j.LoggerFactory

/** SDK session IDs identify a protocol session, never an authenticated person. No idle
 * timeout is promised by SDK 0.14: a silent client remains until DELETE or server shutdown.
 */
internal class McpClientLifecycle(private val sdkSessionId: String) {
    private val log = LoggerFactory.getLogger(McpClientLifecycle::class.java)
    private var connected = false
    private var closed = false

    @Synchronized fun connected() {
        if (connected || closed) return
        connected = true
        log.info("MCP client connected sdkSessionId={} semantics=initialized-session", sdkSessionId)
    }

    @Synchronized fun closed() {
        if (closed) return
        closed = true
        if (connected) log.info("MCP client exited sdkSessionId={} semantics=transport-session-closed", sdkSessionId)
    }
}
