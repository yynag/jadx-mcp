package com.yyang.jadxmcp.protocol

data class CreateSessionRequest(
    val apkPath: String = "",
    val maxHeap: String? = null,
    val deobf: Boolean = true,
    val type: BackendKind = BackendKind.HEADLESS
) : OperationRequest {
    override val operation get() = Operation.SESSION_CREATE
    override fun validate() {
        nonBlank(apkPath, "apkPath")
        if (type != BackendKind.HEADLESS) invalid("GUI sessions register through the plugin channel")
        if (maxHeap != null && !Regex("[1-9][0-9]*[kKmMgG]").matches(maxHeap)) invalid("Invalid maxHeap")
    }
}

class ListSessionsRequest : OperationRequest {
    override val operation get() = Operation.SESSION_LIST
    override fun validate() = Unit
    override fun equals(other: Any?) = other is ListSessionsRequest
    override fun hashCode() = operation.hashCode()
}

data class CloseSessionRequest(
    override val sessionId: String = "", val clearCache: Boolean = false
) : OperationRequest, SessionScoped {
    override val operation get() = Operation.SESSION_CLOSE
    override fun validate() = session(sessionId)
}

enum class SessionState { CONNECTING, READY, BUSY, DISCONNECTED, DISABLED, CLOSED }

data class SessionInfo(
    val sessionId: String = "",
    val backend: BackendKind = BackendKind.HEADLESS,
    val name: String = "",
    val state: SessionState = SessionState.CONNECTING,
    val projectEpoch: Long = 0,
    val createdAt: Long = 0,
    val lastSeenAt: Long = 0,
    val capabilities: BackendCapabilities? = null
) : OperationResult

data class SessionListResult(val sessions: List<SessionInfo> = emptyList()) : OperationResult
data class CloseSessionResult(val sessionId: String = "", val closed: Boolean = false) : OperationResult
