package com.yyang.jadxmcp.protocol

data class StackFramesRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.DEBUG_GET_STACK_FRAMES
    override fun validate() = session(sessionId)
}
data class ThreadsRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.DEBUG_GET_THREADS
    override fun validate() = session(sessionId)
}
data class VariablesRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.DEBUG_GET_VARIABLES
    override fun validate() = session(sessionId)
}

data class DebugThread(val id: String = "", val name: String = "", val selected: Boolean = false)
data class DebugFrame(val index: Int = 0, val display: String = "", val symbol: SymbolRef? = null)
data class DebugVariable(
    val name: String = "", val value: String? = null, val type: String? = null,
    val typeId: String? = null, val updated: Boolean = false, val children: List<DebugVariable> = emptyList()
)
data class ThreadsResult(val threads: List<DebugThread> = emptyList()) : OperationResult
data class StackFramesResult(val frames: List<DebugFrame> = emptyList()) : OperationResult
data class VariablesResult(
    val registers: List<DebugVariable> = emptyList(), val thisObject: List<DebugVariable> = emptyList()
) : OperationResult
data class DebuggerSnapshot(
    val attached: Boolean = false, val suspended: Boolean = false,
    val threads: List<DebugThread> = emptyList(), val frames: List<DebugFrame> = emptyList(),
    val variables: VariablesResult = VariablesResult()
)
