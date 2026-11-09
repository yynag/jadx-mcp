package com.yyang.jadxmcp.analysis.reference

import com.yyang.jadxmcp.analysis.metadata.ClassIndex
import com.yyang.jadxmcp.analysis.search.fail
import com.yyang.jadxmcp.analysis.search.resultPage
import com.yyang.jadxmcp.analysis.search.symbol
import com.yyang.jadxmcp.protocol.*
import jadx.api.ICodeInfo
import jadx.api.JadxDecompiler
import jadx.api.utils.CodeUtils
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.ICodeNode
import jadx.core.dex.nodes.MethodNode

/** Native usage indexes supply callers. Only the requested page is materialized as source;
 * snippets use exact metadata references and enclosing signatures, not keyword guesses. */
class ReferenceService(private val workspace: WorkspaceAccess<JadxDecompiler>) : com.yyang.jadxmcp.protocol.ReferenceService {
    override fun references(request: XrefRequest, context: CallContext): XrefResult {
        request.validate()
        context.checkActive()
        return workspace.withDecompiler(context) { decompiler ->
            val index = ClassIndex.Snapshot(decompiler.classesWithInners)
            val cls = index.find(request.target.className)?.classNode
                ?: fail(ErrorCode.NOT_FOUND, "Class not found: ${request.target.className}")
            val targets: Set<ICodeNode> = when (request.targetType) {
                SymbolKind.CLASS -> setOf(cls)
                SymbolKind.FIELD -> {
                    val fields = cls.fields.filter { (it.name == request.target.memberName || it.alias == request.target.memberName) &&
                        (request.target.descriptor == null || symbol(it).descriptor == request.target.descriptor) }
                    if (fields.isEmpty()) fail(ErrorCode.NOT_FOUND, "Field not found")
                    if (fields.size > 1) fail(ErrorCode.CONFLICT, "Ambiguous field; supply descriptor")
                    fields.toSet()
                }
                SymbolKind.METHOD -> {
                    val methods = cls.methods.filter { (it.name == request.target.memberName || it.alias == request.target.memberName) &&
                        (request.target.descriptor == null || symbol(it).descriptor == request.target.descriptor) }
                    if (methods.isEmpty()) fail(ErrorCode.NOT_FOUND, "Method not found")
                    if (methods.size > 1) fail(ErrorCode.CONFLICT, "Ambiguous method; supply exact descriptor")
                    OverrideIndex(index.classes.map { it.classNode }, context).related(methods.single())
                }
            }
            val callers = linkedSetOf<ICodeNode>()
            for (target in targets) {
                context.checkActive()
                when (target) {
                    is ClassNode -> { callers.addAll(target.useIn); callers.addAll(target.useInMth) }
                    is FieldNode -> callers.addAll(target.useIn)
                    is MethodNode -> {
                        callers.addAll(target.useIn)
                        if (target.callsSelf()) callers.add(target)
                    }
                }
            }
            val sorted = callers.sortedWith(compareBy({ ref(it).className }, { ref(it).memberName ?: "" },
                { ref(it).descriptor ?: "" }))
            val (selected, page) = resultPage(sorted, request.page)
            val sources = mutableMapOf<ClassNode, ICodeInfo>()
            val references = selected.map { caller ->
                context.checkActive()
                val parent = when (caller) {
                    is ClassNode -> caller.topParentClass
                    is MethodNode -> caller.topParentClass
                    is FieldNode -> caller.topParentClass
                    else -> fail(ErrorCode.INTERNAL, "Unsupported native reference node")
                }
                val code = sources.getOrPut(parent) {
                    context.checkActive()
                    val result = parent.code
                    context.checkActive()
                    result
                }
                val sourceRef = ref(caller)
                val position = code.codeMetadata.asMap.entries.asSequence().sortedBy { it.key }.firstOrNull { (pos, annotation) ->
                    context.checkActive()
                    annotation in targets && (caller is ClassNode ||
                        decompiler.getEnclosingNode(code, pos)?.let(::symbol) == sourceRef)
                }?.key
                val snippet = position?.let { pos ->
                    val start = code.codeStr.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)) + 1
                    val end = code.codeStr.indexOf('\n', pos).let { if (it < 0) code.codeStr.length else it }
                    "Line ${code.codeStr.take(pos).count { it == '\n' } + 1}: ${code.codeStr.substring(start, end).trim().take(400)}"
                }
                val methodCode = if (caller is MethodNode) {
                    val text = CodeUtils.extractMethodCode(caller, code)
                    text.takeIf { it.isNotBlank() }?.let { boundedText(it) }
                } else null
                context.checkActive()
                ReferenceHit(sourceRef, when (caller) { is MethodNode -> "method"; is FieldNode -> "field"; else -> "class" },
                    snippet, methodCode)
            }
            context.checkActive()
            XrefResult(references, page)
        }
    }

    private fun ref(node: ICodeNode): SymbolRef = when (node) {
        is ClassNode -> symbol(node)
        is MethodNode -> symbol(node)
        is FieldNode -> symbol(node)
        else -> fail(ErrorCode.INTERNAL, "Unsupported native reference node")
    }
}

internal fun boundedText(text: String, maxChars: Int = 4_000): TextContent {
    var end = minOf(text.length, maxChars)
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return TextContent(text.substring(0, end), end < text.length, text.length)
}
