package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.analysis.project.AttachmentIdentity
import com.yyang.jadxmcp.analysis.project.ProjectDataEdits
import com.yyang.jadxmcp.analysis.project.ProjectSymbols
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.requireGuiWorker
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaMethod
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.api.metadata.annotations.InsnCodeOffset

/** Plan comment attachments with shared analysis helpers and commit through the GUI project owner. */
class GuiCommentEditor(
    private val workspace: WorkspaceAccess<JadxDecompiler>,
    private val owner: ProjectDataPort,
    private val replace: (CodeData, String, CallContext) -> MutationState
) : ProjectEditPort {
    override fun comment(request: AddCommentRequest, context: CallContext): CommentMutationResult {
        request.validate(); requireGuiWorker(); context.checkActive()
        val before = owner.snapshot(context)
        val target = workspace.withDecompiler(context) { d ->
            val symbols = ProjectSymbols(d)
            val cls = symbols.clazz(request.className)
            val method = request.methodName?.let { symbols.method(SymbolRef(request.className, it, request.methodSignature)) }
            when {
                request.line != null -> lineAnchor(d, symbols, cls, method, requireNotNull(request.line))
                request.fieldName != null -> AttachmentIdentity(symbols.reference(symbols.field(SymbolRef(request.className, request.fieldName))))
                method != null -> AttachmentIdentity(symbols.reference(method))
                else -> AttachmentIdentity(symbols.reference(cls))
            }
        }
        val (after, action) = ProjectDataEdits.comment(before.data, CodeComment(target.node, target.code, request.comment, request.style))
        // No workspace lease survives across this GUI owner call / EDT handoff.
        return CommentMutationResult(action, replace(after, before.revision, context))
    }

    override fun comments(request: ListCommentsRequest, context: CallContext): CommentsResult {
        request.validate(); requireGuiWorker(); context.checkActive()
        val raw = request.className?.let { name -> workspace.withDecompiler(context) { ProjectSymbols(it).clazz(name).rawName } }
        val snapshot = owner.snapshot(context)
        return CommentsResult(snapshot.data.comments.filter { raw == null || it.nodeRef.declaringClass == raw })
    }

    /**
     * CommentAction.getCommentRef 140-164: prefer an exact instruction offset; otherwise a non-VAR
     * declaration on THIS line. A line-only request uses end-of-line as the native caret position;
     * multiple OFFSET annotations therefore choose the last, exactly like native searchUp.
     * Blank/braces and comments above declarations are rejected, not guessed from displayed text.
     */
    private fun lineAnchor(d: JadxDecompiler, symbols: ProjectSymbols, cls: JavaClass, method: JavaMethod?, line: Int): AttachmentIdentity {
        val info = cls.topParentClass.codeInfo
        val lines = info.codeStr.split('\n')
        if (line !in 1..lines.size) fail(ErrorCode.INVALID_ARGUMENT, "Source line is out of range")
        val start = lines.take(line - 1).sumOf { it.length + 1 }
        val end = start + lines[line - 1].length
        val caret = (end - 1).coerceAtLeast(start)
        val offset = info.codeMetadata.searchUp(caret, start, ICodeAnnotation.AnnType.OFFSET) as? InsnCodeOffset
        if (offset != null) {
            val owner = d.getEnclosingNode(info, caret) as? JavaMethod
            if (owner != null && owner.declaringClass == cls && (method == null || owner == method))
                return AttachmentIdentity(symbols.reference(owner), CodeReference(CodeAttachmentKind.INSN, offset.offset))
        }
        val declarations = info.codeMetadata.asMap.entries.filter { it.key in start until end }
            .mapNotNull { (it.value as? NodeDeclareRef)?.node }
            .filter { it.annType != ICodeAnnotation.AnnType.VAR }
            .mapNotNull(d::getJavaNodeByRef)
            .filter { it.declaringClass == cls || it == cls }
            .filter { method == null || it == method }.distinct()
        if (declarations.size != 1) fail(ErrorCode.NOT_FOUND, "Line has no unique instruction/declaration comment anchor")
        return AttachmentIdentity(symbols.reference(declarations.single()))
    }

    override fun rename(request: RenameRequest, context: CallContext): RenameResult = unsupported()
    override fun renamePackage(request: RenamePackageRequest, context: CallContext): RenamePackageResult = unsupported()
    override fun renameVariable(request: RenameVariableRequest, context: CallContext): RenameVariableResult = unsupported()
    private fun unsupported(): Nothing = fail(ErrorCode.UNSUPPORTED, "Route renames through GuiProjectEditAdapter/native GUI events")
    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(ProtocolError(code, message))
}
