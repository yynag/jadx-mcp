package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.requireGuiWorker

data class NativeRenameReceipt(
    val oldName: String, val newName: String, val state: MutationState,
    val target: SymbolRef = SymbolRef(), val renamed: Int = 1, val reg: Int = 0, val ssa: Int = 0
)

/** May await native events, hence called only on a worker, never under an analysis lease. */
interface GuiNativeRenameFacade {
    fun rename(request: RenameRequest, context: CallContext): NativeRenameReceipt
    fun renamePackage(request: RenamePackageRequest, context: CallContext): NativeRenameReceipt
    fun renameVariable(request: RenameVariableRequest, context: CallContext): NativeRenameReceipt
}

/**
 * Renames follow native GUI events. The injected comment port plans line attachments and commits
 * through this GUI's ProjectDataPort; it also owns comment queries. Neither path creates another
 * project owner or infers disk persistence from an in-memory edit.
 */
class GuiProjectEditAdapter(
    private val native: GuiNativeRenameFacade,
    private val comments: ProjectEditPort,
    private val epoch: () -> Long
) : ProjectEditPort {
    private fun check(context: CallContext) { context.checkActive(); context.requireEpoch(epoch()) }
    override fun rename(request: RenameRequest, context: CallContext): RenameResult {
        request.validate(); requireGuiWorker(); check(context)
        val r = native.rename(request, context)
        check(context)
        return RenameResult(r.target, r.oldName, r.newName, r.state)
    }
    override fun renamePackage(request: RenamePackageRequest, context: CallContext): RenamePackageResult {
        request.validate(); requireGuiWorker(); check(context)
        return native.renamePackage(request, context).let { check(context); RenamePackageResult(it.renamed, it.state) }
    }
    override fun renameVariable(request: RenameVariableRequest, context: CallContext): RenameVariableResult {
        request.validate(); requireGuiWorker(); check(context)
        val r = native.renameVariable(request, context)
        check(context)
        return RenameVariableResult(r.target, r.oldName, r.newName, r.reg, r.ssa, r.state)
    }
    override fun comment(request: AddCommentRequest, context: CallContext): CommentMutationResult {
        request.validate(); requireGuiWorker(); check(context)
        return comments.comment(request, context).also { check(context) }
    }
    override fun comments(request: ListCommentsRequest, context: CallContext): CommentsResult {
        request.validate(); requireGuiWorker(); check(context)
        return comments.comments(request, context).also { check(context) }
    }
}
