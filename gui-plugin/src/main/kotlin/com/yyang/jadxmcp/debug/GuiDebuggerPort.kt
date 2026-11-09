package com.yyang.jadxmcp.debug

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.SwingUtilities

/** Only detached values cross this boundary. Implementations must not perform device I/O. */
fun interface DebuggerFacade {
    /** Called inside UiExecutor, never from an analysis/decompiler lock. */
    fun capture(context: CallContext): DebuggerModel
}

enum class DebuggerState { UI_NOT_READY, UNSUPPORTED, DISCONNECTED, RUNNING, PAUSED }

data class DebuggerModel(
    val state: DebuggerState,
    val threads: List<DebugThread> = emptyList(),
    val frames: List<DebugFrame> = emptyList(),
    val variables: VariablesResult = VariablesResult()
)

/**
 * Detached debugger snapshots use the host project epoch and UiExecutor (or the bounded default).
 * No reflection, decompilation, transport waits, or device queries occur in this port.
 * Empty paused models are conservatively BUSY, not evidence of a successful attached debugger.
 */
class GuiDebuggerPort(
    private val facade: DebuggerFacade,
    private val projectEpoch: () -> Long,
    private val ui: UiExecutor = DebuggerEdtExecutor()
) : DebuggerPort {
    override fun snapshot(context: CallContext): DebuggerSnapshot {
        context.checkActive()
        val result = ui.readOrEdit(context) {
            context.checkActive()
            context.requireEpoch(projectEpoch())
            val model = facade.capture(context)
            context.checkActive()
            context.requireEpoch(projectEpoch())
            when (model.state) {
                DebuggerState.UI_NOT_READY -> debugFailure(ErrorCode.BUSY, "Debugger UI is not ready", true)
                DebuggerState.UNSUPPORTED -> debugFailure(ErrorCode.UNSUPPORTED, "Unsupported JADX debugger version")
                DebuggerState.DISCONNECTED -> debugFailure(ErrorCode.DISCONNECTED, "Debugger is not connected")
                DebuggerState.RUNNING -> debugFailure(ErrorCode.CONFLICT, "Debugger is running; pause the process first")
                DebuggerState.PAUSED -> Unit
            }
            if (model.threads.isEmpty() || model.threads.none { it.selected } || model.frames.isEmpty() ||
                (model.variables.registers.isEmpty() && model.variables.thisObject.isEmpty())) {
                debugFailure(ErrorCode.BUSY, "Paused debugger models are not populated yet", true)
            }
            DebuggerSnapshot(true, true, model.threads, model.frames, model.variables)
        }
        context.checkActive()
        context.requireEpoch(projectEpoch())
        return result
    }
}

/** Enqueue only short model copies. The caller waits off EDT and checks cancellation every 25ms. */
class DebuggerEdtExecutor : UiExecutor {
    override fun <T> readOrEdit(context: CallContext, action: () -> T): T {
        context.checkActive()
        if (SwingUtilities.isEventDispatchThread()) return action().also { context.checkActive() }
        val task = FutureTask<T> { context.checkActive(); action().also { context.checkActive() } }
        SwingUtilities.invokeLater(task)
        try {
            while (true) {
                context.checkActive()
                try {
                    return task.get(25, TimeUnit.MILLISECONDS).also { context.checkActive() }
                } catch (_: TimeoutException) {
                    // Cooperative checks also fence queued tasks; never interrupt Swing's EDT.
                }
            }
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            debugFailure(ErrorCode.CANCELLED, "Debugger request interrupted")
        } finally {
            task.cancel(false)
        }
    }
}

internal fun debugFailure(code: ErrorCode, message: String, retryable: Boolean = false): Nothing =
    throw ProtocolException(ProtocolError(code, message, retryable))
