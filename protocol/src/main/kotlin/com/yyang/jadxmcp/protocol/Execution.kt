package com.yyang.jadxmcp.protocol

import java.util.concurrent.atomic.AtomicBoolean

fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object { val NONE = CancellationSignal { false } }
}

class CancellationSource : CancellationSignal {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    override fun isCancelled(): Boolean = cancelled.get()
}

/** Epoch fences requests and late results when a GUI opens another project. Zero is a valid initial epoch. */
data class CallContext(
    val requestId: String,
    val projectEpoch: Long,
    val deadlineEpochMillis: Long,
    val cancellation: CancellationSignal = CancellationSignal.NONE
) {
    init {
        nonBlank(requestId, "requestId")
        if (projectEpoch < 0 || deadlineEpochMillis <= 0) invalid("Invalid epoch or deadline")
    }

    /** Cancellation is cooperative; this check is not a promise that JADX can be interrupted. */
    fun checkActive(nowEpochMillis: Long = System.currentTimeMillis()) {
        if (cancellation.isCancelled()) throw ProtocolException(ProtocolError(ErrorCode.CANCELLED, "Call cancelled"))
        if (nowEpochMillis >= deadlineEpochMillis) {
            throw ProtocolException(ProtocolError(ErrorCode.DEADLINE_EXCEEDED, "Call deadline exceeded"))
        }
    }

    fun requireEpoch(actual: Long) {
        if (projectEpoch != actual) throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "Project epoch changed"))
    }
}

data class RequestFrame(val request: OperationRequest, val context: CallContext)

/** Executors own scheduling and cancellation propagation, not these ports. */
interface BackendEndpoint : AutoCloseable {
    val capabilities: BackendCapabilities
    fun execute(request: BackendRequest, context: CallContext): OperationResult
    override fun close()
}
