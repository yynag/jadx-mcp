package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.SwingUtilities

/** No analysis lease may be held while waiting here. Cancelled queued actions never touch Swing.
 * The 25 ms poll is a cancellation/deadline observation interval, not an EDT operation timeout;
 * the queued action is fenced before and after execution and may finish after its caller returns.
 */
class GuiEdtExecutor(private val epoch: () -> Long) : UiExecutor {
    override fun <T> readOrEdit(context: CallContext, action: () -> T): T {
        fun checked(): T {
            context.checkActive(); context.requireEpoch(epoch())
            return action().also { context.checkActive(); context.requireEpoch(epoch()) }
        }
        if (SwingUtilities.isEventDispatchThread()) return checked()
        val task = FutureTask<T> { checked() }
        SwingUtilities.invokeLater(task)
        try {
            while (true) {
                context.checkActive(); context.requireEpoch(epoch())
                try { return task.get(25, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { /* cooperative cancellation; never interrupt EDT */ }
            }
        } catch (e: ExecutionException) { throw (e.cause ?: e) }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            guiFailure(ErrorCode.CANCELLED, "GUI request interrupted")
        } finally { task.cancel(false) }
    }
}

internal fun guiFailure(code: ErrorCode, message: String): Nothing =
    throw ProtocolException(ProtocolError(code, message))

/** Entry points doing analysis or native event waits must never be dispatched from EDT. */
fun requireGuiWorker() {
    if (SwingUtilities.isEventDispatchThread()) guiFailure(ErrorCode.BUSY, "Dispatch GUI analysis/edit commands on a worker, not EDT")
}

/** Worker-only wait with transport cancellation and project fencing; queued repairs may outlive it. */
fun <T> awaitGuiFuture(future: Future<T>, context: CallContext, epoch: () -> Long): T {
    requireGuiWorker()
    while (true) {
        context.checkActive(); context.requireEpoch(epoch())
        try { return future.get(25, TimeUnit.MILLISECONDS).also { context.checkActive(); context.requireEpoch(epoch()) } }
        catch (_: TimeoutException) { }
        catch (e: ExecutionException) { throw (e.cause ?: e) }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); guiFailure(ErrorCode.CANCELLED, "GUI worker interrupted")
        }
    }
}
