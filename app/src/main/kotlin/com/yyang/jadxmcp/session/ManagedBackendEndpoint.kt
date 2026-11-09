package com.yyang.jadxmcp.session

import com.yyang.jadxmcp.protocol.BackendEndpoint
import com.yyang.jadxmcp.protocol.ProtocolError

/** Optional local lifecycle extension, not a wire/protocol change. Getters must never block. */
interface ManagedBackendEndpoint : BackendEndpoint {
    /** Non-null means permanently unavailable; a dead owned worker cannot be reconnected/reused. */
    val terminalError: ProtocolError?
    /** Only exclusively owned generated data may be deleted, never borrowed/user codeData. */
    fun close(clearCache: Boolean)
    override fun close() = close(false)
}
