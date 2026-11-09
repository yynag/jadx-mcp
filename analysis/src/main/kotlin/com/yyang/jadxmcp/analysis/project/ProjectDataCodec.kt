package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*
import jadx.api.data.ICodeData
import jadx.api.data.IJavaNodeRef
import jadx.api.data.IJavaCodeRef
import jadx.api.data.CodeRefType
import jadx.api.data.impl.*
import javax.lang.model.SourceVersion

internal fun projectError(code: ErrorCode, message: String): Nothing =
    throw ProtocolException(ProtocolError(code, message))

/** Value identity is raw class + exact native shortId + optional code attachment, never alias/line. */
data class AttachmentIdentity(val node: NodeReference, val code: CodeReference? = null)

/** Native/DTO codec only. Transport serialization and owner-specific disk formats are not chosen here. */
object ProjectDataCodec {
    fun identity(comment: CodeComment) = AttachmentIdentity(comment.nodeRef, comment.codeRef)
    fun identity(rename: CodeRename) = AttachmentIdentity(rename.nodeRef, rename.codeRef)

    fun node(value: IJavaNodeRef): NodeReference = NodeReference(
        NodeReferenceKind.valueOf(value.type.name), value.declaringClass, value.shortId.orEmpty())
    fun code(value: IJavaCodeRef): CodeReference = CodeReference(
        CodeAttachmentKind.valueOf(value.attachType.name), value.index)
    fun node(value: NodeReference): JadxNodeRef {
        validate(value, null)
        return JadxNodeRef(IJavaNodeRef.RefType.valueOf(value.type.name), value.declaringClass,
            if (value.type == NodeReferenceKind.CLASS) null else value.shortId)
    }
    fun code(value: CodeReference): JadxCodeRef {
        if (value.index < 0) invalid("Code attachment index must be non-negative")
        return JadxCodeRef(CodeRefType.valueOf(value.attachType.name), value.index)
    }

    fun fromNative(value: ICodeData?): CodeData = canonical(CodeData(
        value?.comments.orEmpty().map { CodeComment(node(it.nodeRef), it.codeRef?.let(::code), it.comment,
            CommentStyle.valueOf(it.style.name)) },
        value?.renames.orEmpty().map { CodeRename(node(it.nodeRef), it.codeRef?.let(::code), it.newName) }
    ))

    fun toNative(value: CodeData): JadxCodeData {
        val data = canonical(value)
        return JadxCodeData().apply {
            comments = data.comments.map { JadxCodeComment(node(it.nodeRef), it.codeRef?.let(::code), it.comment,
                jadx.api.data.CommentStyle.valueOf(it.style.name)) }
            renames = data.renames.map { JadxCodeRename(node(it.nodeRef), it.codeRef?.let(::code), it.newName) }
        }
    }

    fun canonical(value: CodeData): CodeData {
        val comments = value.comments.map { it.copy() }
        val renames = value.renames.map { it.copy() }
        comments.forEach {
            validate(it.nodeRef, it.codeRef)
            if (it.comment.isBlank() || '\u0000' in it.comment) invalid("Stored comments must be nonblank and NUL-free")
        }
        renames.forEach {
            validate(it.nodeRef, it.codeRef)
            val code = it.codeRef
            if (code != null && code.attachType !in setOf(CodeAttachmentKind.VAR, CodeAttachmentKind.MTH_ARG))
                invalid("Only VAR and MTH_ARG can be renamed")
            if (it.nodeRef.type == NodeReferenceKind.PKG) packageName(it.newName) else identifier(it.newName)
        }
        if (comments.map(::identity).distinct().size != comments.size) invalid("Duplicate comment attachment")
        if (renames.map(::identity).distinct().size != renames.size) invalid("Duplicate rename attachment")
        val comparator = compareBy<AttachmentIdentity>({ it.node.type.name }, { it.node.declaringClass },
            { it.node.shortId }, { it.code?.attachType?.name.orEmpty() }, { it.code?.index ?: -1 })
        // Detached unmodifiable lists stop callers from changing a revision's contents via MutableList casts.
        return CodeData(java.util.Collections.unmodifiableList(comments.sortedWith(compareBy(comparator, ::identity))),
            java.util.Collections.unmodifiableList(renames.sortedWith(compareBy(comparator, ::identity))))
    }

    fun validate(node: NodeReference, code: CodeReference?) {
        if (node.declaringClass.isBlank() || node.declaringClass.any { it.isWhitespace() || it == '\u0000' })
            invalid("Raw declaring class/package is required")
        when (node.type) {
            NodeReferenceKind.CLASS, NodeReferenceKind.PKG -> if (node.shortId.isNotEmpty()) invalid("Class/package shortId must be empty")
            NodeReferenceKind.METHOD -> if (!node.shortId.contains('(') || !node.shortId.contains(')')) invalid("Exact method shortId is required")
            NodeReferenceKind.FIELD -> if (!node.shortId.contains(':')) invalid("Exact field shortId is required")
        }
        if (code != null) {
            if (node.type != NodeReferenceKind.METHOD) invalid("Code attachments require a method")
            if (code.index < 0) invalid("Code attachment index must be non-negative")
            // Native CodeRenameVisitor decodes reg with signed >>16, so regs above 32767 are not representable.
        }
    }

    fun variable(reg: Int, ssa: Int): CodeReference {
        if (reg !in 0..32767 || ssa !in 0..65535) invalid("reg must be 0..32767 and ssa 0..65535")
        return code(JadxCodeRef.forVar(reg, ssa))
    }

    fun identifier(name: String) {
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name) || name in setOf("true", "false", "null", "_"))
            invalid("Invalid Java identifier '$name'")
    }
    fun packageName(name: String) {
        if (name.isBlank()) invalid("Package name is required")
        name.split('.').forEach(::identifier)
    }
    private fun invalid(message: String): Nothing = projectError(ErrorCode.INVALID_ARGUMENT, message)
}
