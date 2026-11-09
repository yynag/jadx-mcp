package com.yyang.jadxmcp.protocol

/** Mirrors JADX 1.5.6 IJavaNodeRef/IJavaCodeRef, not generated source positions or GUI objects. */
enum class NodeReferenceKind { CLASS, FIELD, METHOD, PKG }
enum class CodeAttachmentKind { INSN, MTH_ARG, VAR, CATCH }
data class NodeReference(
    val type: NodeReferenceKind = NodeReferenceKind.CLASS, val declaringClass: String = "", val shortId: String = ""
)
data class CodeReference(val attachType: CodeAttachmentKind = CodeAttachmentKind.INSN, val index: Int = 0)
data class CodeComment(
    val nodeRef: NodeReference = NodeReference(), val codeRef: CodeReference? = null,
    val comment: String = "", val style: CommentStyle = CommentStyle.LINE
)
data class CodeRename(
    val nodeRef: NodeReference = NodeReference(), val codeRef: CodeReference? = null, val newName: String = ""
)
data class CodeData(val comments: List<CodeComment> = emptyList(), val renames: List<CodeRename> = emptyList())

/** Input identities are a sorted multiset of content SHA-256 values, never host-specific paths. */
data class InputFingerprint(val sha256: String = "", val sizeBytes: Long = 0)
data class ProjectDataSnapshot(
    val inputs: List<InputFingerprint> = emptyList(), val revision: String = "", val data: CodeData = CodeData()
) {
    fun validate() {
        nonBlank(revision, "revision")
        if (inputs.isEmpty()) invalid("Project snapshot requires input fingerprints")
        if (inputs.any { !Regex("[0-9a-f]{64}").matches(it.sha256) || it.sizeBytes < 0 }) {
            invalid("Invalid SHA-256 input fingerprint")
        }
    }
}

/** Explicit snapshot/import boundary; the receiving project owner controls persistence. */
interface ProjectDataPort {
    fun snapshot(context: CallContext): ProjectDataSnapshot
    fun importData(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext): MutationState
}
