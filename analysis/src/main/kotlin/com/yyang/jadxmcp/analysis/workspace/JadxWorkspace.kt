package com.yyang.jadxmcp.analysis.workspace

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * One synchronous, exclusive lease per native instance. No timeout worker is abandoned:
 * JADX is not reliably interruptible, so an active action keeps its lease until it exits.
 * Host code must also use this lease (or its own outer project lock) for GUI mutations.
 * Native objects must not escape [withDecompiler]. Epoch changes fence late results.
 */
class JadxWorkspace private constructor(
    private val decompiler: JadxDecompiler,
    override val ownership: WorkspaceOwnership,
    private val epoch: () -> Long
) : WorkspaceAccess<JadxDecompiler> {
    private val lock = ReentrantLock(true)
    private val closed = AtomicBoolean(false)
    // This wrapper is bound to one native project, not whichever project the host opens next.
    override val projectEpoch: Long = epoch()

    override fun <T> withDecompiler(context: CallContext, action: (JadxDecompiler) -> T): T {
        checkLeaseActive(context)
        acquireLease(context)
        try {
            checkLeaseActive(context)
            val result = action(decompiler)
            // Cancellation and project switches discard even successfully computed late results.
            checkLeaseActive(context)
            return result
        } finally {
            lock.unlock()
        }
    }

    /** Poll while waiting, but never abandon native work once the caller owns the lease. */
    private fun acquireLease(context: CallContext) {
        try {
            while (!lock.tryLock(25, TimeUnit.MILLISECONDS)) checkLeaseActive(context)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(ErrorCode.CANCELLED, "Interrupted while waiting for workspace")
        }
    }

    private fun checkLeaseActive(context: CallContext) {
        context.checkActive()
        if (closed.get()) fail(ErrorCode.CLOSED, "Workspace is closed")
        context.requireEpoch(projectEpoch)
        context.requireEpoch(epoch())
    }

    override fun close() {
        if (lock.isHeldByCurrentThread) fail(ErrorCode.BUSY, "Cannot close an active workspace lease")
        // Reject new leases immediately, then drain the existing lease before releasing native state.
        closed.set(true)
        lock.lock()
        try {
            if (!released) {
                released = true
                if (ownership == WorkspaceOwnership.OWNED) decompiler.close()
            }
        } finally {
            lock.unlock()
        }
    }

    private var released = false // protected by lock; close is idempotent

    companion object {
        /** Load on the caller's analysis thread; failures/cancellation always close the owned instance. */
        fun owned(args: JadxArgs, context: CallContext): JadxWorkspace {
            context.checkActive()
            val decompiler = JadxDecompiler(args)
            try {
                decompiler.load()
                context.checkActive()
                return JadxWorkspace(decompiler, WorkspaceOwnership.OWNED) { context.projectEpoch }
            } catch (failure: Throwable) {
                try { decompiler.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }

        /**
         * The host retains native ownership: closing this wrapper drains leases, not the decompiler.
         * Create a wrapper per host project; the epoch supplier must be thread-safe and nonblocking.
         */
        fun borrowed(decompiler: JadxDecompiler, epoch: () -> Long): JadxWorkspace =
            JadxWorkspace(decompiler, WorkspaceOwnership.BORROWED, epoch)

        private fun fail(code: ErrorCode, message: String): Nothing =
            throw ProtocolException(ProtocolError(code, message))
    }
}
