package com.yyang.jadxmcp.runtime

import jadx.api.JadxDecompiler
import jadx.gui.ui.MainWindow
import jadx.gui.utils.ILoadListener
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Actual 1.5.6 hook, not a timer. MainWindow.addLoadListener 1713-1722 immediately reports loaded
 * state and removes a listener only when update returns true; there is NO removeLoadListener API.
 * MainWindow.closeAll 606-620 calls false on EDT before wrapper.close, but cannot await a worker.
 * onLoaded MUST only enqueue factory/bridge work and return; it must never hash or wait on EDT.
 */
class JadxGuiLoadHook private constructor(private val callback: AtomicReference<((Boolean) -> Unit)?>) : AutoCloseable {
    /** Immediately release captured host/lifecycle/callbacks; the inert listener self-removes next notification. */
    override fun close() { callback.set(null) }

    companion object {
        fun install(
            window: MainWindow,
            lifecycle: GuiBackendLifecycle,
            onLoaded: (epoch: Long, decompiler: JadxDecompiler) -> Unit,
            onFailure: (Throwable) -> Unit,
            onProjectChange: (epoch: Long, loaded: Boolean) -> Unit = { _, _ -> }
        ): JadxGuiLoadHook {
            check(SwingUtilities.isEventDispatchThread()) { "Install native load listener on EDT" }
            val callback = AtomicReference<((Boolean) -> Unit)?>(null)
            callback.set { loaded ->
                try {
                    val epoch = lifecycle.beginProjectChange()
                    // Integration can disable/retire bridge leases immediately, BEFORE worker
                    // factory creation. Re-enable/register only after successful runtime install.
                    onProjectChange(epoch, loaded)
                    if (loaded) {
                        val decompiler = window.wrapper.currentDecompiler.orElseThrow {
                            IllegalStateException("JADX reported loaded without a decompiler")
                        }
                        onLoaded(epoch, decompiler)
                    }
                } catch (e: Exception) { onFailure(e) }
            }
            val hook = JadxGuiLoadHook(callback)
            val listener = ILoadListener { loaded ->
                val action = callback.get()
                if (action == null) true else { action(loaded); false }
            }
            try { window.addLoadListener(listener) }
            catch (e: Throwable) { hook.close(); throw e }
            return hook
        }
    }
}
