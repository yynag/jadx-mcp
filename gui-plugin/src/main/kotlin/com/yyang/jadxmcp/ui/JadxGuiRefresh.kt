package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.project.NativeGuiCodeDataBinding
import jadx.api.JadxDecompiler
import jadx.api.plugins.events.JadxEvents
import jadx.api.plugins.events.types.NodeRenamedByUser
import jadx.gui.ui.MainWindow
import jadx.gui.ui.codearea.ClassCodeContentPanel
import java.util.function.Consumer
import javax.swing.SwingUtilities

/**
 * Import/comment refresh follows CommentDialog.updateCommentsData 47-68: reload code data and
 * background-refresh editors. Never use reloadAllTabs(): 1.5.6 implements it via refreshClass on EDT.
 * Host cache invalidation MUST be nonblocking and unconditional even after a caller is cancelled.
 */
class JadxGuiRefresh(
    private val window: MainWindow,
    private val workspace: WorkspaceAccess<JadxDecompiler>,
    private val ui: UiExecutor,
    private val invalidateGeneration: () -> Unit
) {
    fun afterProjectEdit(context: CallContext) {
        requireGuiWorker()
        invalidateGeneration()
        context.requireEpoch(workspace.projectEpoch)
        // An already-applied edit still needs repair/refresh after transport cancellation.
        val repair = context.copy(cancellation = CancellationSignal.NONE,
            deadlineEpochMillis = System.currentTimeMillis() + 30_000)
        val data = ui.readOrEdit(repair) {
            repair.requireEpoch(workspace.projectEpoch)
            window.project.codeData
        }
        workspace.withDecompiler(repair) { d ->
            NativeGuiCodeDataBinding.apply(d, data, repair)
        }
        ui.readOrEdit(repair) {
            repair.requireEpoch(workspace.projectEpoch)
            window.rebuildPackagesTree()
            window.reloadTreePreservingState()
            window.tabbedPane.tabs.filterIsInstance<ClassCodeContentPanel>().forEach { panel ->
                Jadx156CodeAreaAccess.backgroundRefresh(panel.codeArea as Any)
            }
        }
    }

    /** Host owns listener lifetime; this observes only local native edits, not cross-session sync. */
    fun observeNativeRenames(): AutoCloseable {
        check(SwingUtilities.isEventDispatchThread()) { "Capture native GUI event registry on EDT" }
        val events = window.events().global()
        val listener = Consumer<NodeRenamedByUser> { invalidateGeneration() }
        events.addListener(JadxEvents.NODE_RENAMED_BY_USER, listener)
        return AutoCloseable { events.removeListener(JadxEvents.NODE_RENAMED_BY_USER, listener) }
    }
}
