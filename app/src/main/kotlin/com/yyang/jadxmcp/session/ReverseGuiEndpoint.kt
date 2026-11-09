package com.yyang.jadxmcp.session

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** One outstanding remote command. All waits release this endpoint's short-lived monitor. */
internal class ReverseGuiEndpoint(
    val lease: GuiLease,
    registration: GuiRegistration,
    private val policy: SessionPolicy,
    private val now: () -> Long
) : BackendEndpoint {
    override val capabilities = registration.capabilities
    private val guard = Object()
    private var epoch = registration.projectEpoch
    private var state = SessionState.READY
    private var seen = now()
    private var heartbeat = seen
    private var disconnectedAt = 0L
    private var pending: RemoteCall? = null
    private var offered = false

    private class RemoteCall(val frame: RequestFrame, val result: CompletableFuture<OperationResult>)

    fun snapshot(): Triple<SessionState, Long, Long> = synchronized(guard) { Triple(state, epoch, seen) }
    fun authenticate(candidate: GuiLease) {
        if (candidate != lease) throw failure(ErrorCode.NOT_FOUND, "Unknown GUI lease")
    }

    override fun execute(request: BackendRequest, context: CallContext): OperationResult {
        val call = synchronized(guard) {
            requireConnected()
            context.checkActive(now())
            context.requireEpoch(epoch)
            check(pending == null) { "Session execution lane overlapped" }
            // Caller IDs may be reused after completion. A unique wire ID prevents an old response
            // from satisfying a later call with the same caller ID and project epoch.
            val wireContext = context.copy(requestId = ShortIds.wire())
            RemoteCall(RequestFrame(request, wireContext), CompletableFuture()).also {
                pending = it
                offered = false
                // Polls cannot keep a stuck command alive: busy liveness requires heartbeat.
                heartbeat = now()
                state = SessionState.BUSY
                guard.notifyAll()
            }
        }
        try {
            return call.result.get()
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            synchronized(guard) {
                if (pending === call) {
                    pending = null
                    offered = false
                    if (state == SessionState.BUSY) state = SessionState.READY
                }
                guard.notifyAll()
            }
        }
    }

    fun poll(context: CallContext): PollResult = synchronized(guard) {
        val end = minOf(context.deadlineEpochMillis, now() + policy.maxPollMillis)
        while (true) {
            if (state == SessionState.CLOSED) return@synchronized PollResult(PollStatus.CLOSED)
            requireConnected()
            context.checkActive(now())
            context.requireEpoch(epoch)
            seen = now()
            val call = pending
            if (call != null && !offered && !call.result.isDone) {
                // A canceled queued frame must never start on the remote backend.
                try { call.frame.context.checkActive(now()) } catch (e: ProtocolException) {
                    offered = true
                    call.result.completeExceptionally(e)
                    continue
                }
                offered = true
                return@synchronized PollResult(PollStatus.COMMAND, call.frame)
            }
            val remaining = end - now()
            if (remaining <= 0) return@synchronized PollResult(PollStatus.IDLE)
            guard.wait(minOf(remaining, policy.sweepMillis))
        }
        @Suppress("UNREACHABLE_CODE") PollResult(PollStatus.IDLE)
    }

    fun complete(completion: CommandCompletion) = synchronized(guard) {
        requireConnected()
        val call = pending ?: throw failure(ErrorCode.CONFLICT, "No command is pending")
        val frame = call.frame
        if (call.result.isDone || !offered || completion.requestId != frame.context.requestId ||
            completion.operation != frame.request.operation || completion.projectEpoch != epoch ||
            completion.projectEpoch != frame.context.projectEpoch) {
            throw failure(ErrorCode.CONFLICT, "Late or mismatched GUI completion")
        }
        // A late acknowledgement releases the serial lane, but can never become a successful result.
        try { frame.context.checkActive(now()) } catch (e: ProtocolException) {
            call.result.completeExceptionally(e)
            throw e
        }
        val result = completion.result
        if (result != null && !completion.operation.resultType.isInstance(result)) {
            throw failure(ErrorCode.INVALID_ARGUMENT, "Wrong result type for ${completion.operation.wireName}")
        }
        seen = now()
        val error = completion.error
        if (error != null) call.result.completeExceptionally(ProtocolException(error))
        else call.result.complete(requireNotNull(result))
        Unit
    }

    fun heartbeat(projectEpoch: Long) = synchronized(guard) {
        requireConnected()
        if (projectEpoch < epoch) throw failure(ErrorCode.CONFLICT, "Stale project epoch")
        if (projectEpoch > epoch) {
            epoch = projectEpoch
            pending?.result?.completeExceptionally(failure(ErrorCode.CONFLICT, "Project epoch changed"))
            // The lane still owns pending until its execute() finally block runs.
        }
        seen = now()
        heartbeat = seen
    }

    /** True once an unexpectedly disconnected record can be removed from the registry. */
    fun reap(): Boolean = synchronized(guard) {
        val time = now()
        if (state == SessionState.READY && time - seen >= policy.idleLeaseMillis ||
            state == SessionState.BUSY && time - heartbeat >= policy.busyHeartbeatMillis) {
            state = SessionState.DISCONNECTED
            disconnectedAt = time
            pending?.result?.completeExceptionally(failure(ErrorCode.DISCONNECTED, "GUI heartbeat expired"))
            guard.notifyAll()
        }
        state == SessionState.DISCONNECTED && time - disconnectedAt >= policy.disconnectedRetentionMillis
    }

    fun disconnected() = synchronized(guard) { state == SessionState.DISCONNECTED }

    override fun close() = synchronized(guard) {
        state = SessionState.CLOSED
        pending?.result?.completeExceptionally(failure(ErrorCode.CLOSED, "GUI channel closed"))
        guard.notifyAll()
    }

    private fun requireConnected() {
        if (state == SessionState.CLOSED) throw failure(ErrorCode.CLOSED, "GUI channel closed")
        if (state == SessionState.DISCONNECTED) throw failure(ErrorCode.DISCONNECTED, "GUI must register again")
    }
}
