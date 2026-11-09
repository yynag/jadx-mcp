package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.*

/** Detached editor state; an empty selection is valid, a missing editor is not. */
data class EditorContext(val className: String?, val selection: String = "")
fun interface EditorContextFacade { fun read(): EditorContext }

class GuiContextAdapter(
    private val editor: EditorContextFacade,
    private val ui: UiExecutor,
    private val source: SourceService,
    private val epoch: () -> Long
) : GuiContextPort {
    override fun currentClass(request: CurrentClassRequest, context: CallContext): SourceResult {
        request.validate(); context.checkActive(); requireGuiWorker()
        val name = ui.readOrEdit(context) { context.requireEpoch(epoch()); editor.read().className }
            ?: guiFailure(ErrorCode.NOT_FOUND, "Active editor is not a class")
        val result = source.decompile(DecompileRequest(request.sessionId, className = name, slim = request.slim), context)
        context.checkActive(); context.requireEpoch(epoch())
        return result
    }

    override fun selectedText(request: SelectedTextRequest, context: CallContext): SelectionResult {
        request.validate(); context.checkActive()
        return ui.readOrEdit(context) {
            context.requireEpoch(epoch())
            editor.read().let { SelectionResult(it.selection, it.className) }
        }.also { context.checkActive(); context.requireEpoch(epoch()) }
    }
}
