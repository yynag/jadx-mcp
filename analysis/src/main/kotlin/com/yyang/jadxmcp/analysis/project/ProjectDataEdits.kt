package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*

/** Pure, detached transformations usable by a GUI owner without an analysis service or filesystem. */
object ProjectDataEdits {
    fun rename(data: CodeData, rename: CodeRename): CodeData {
        val before = ProjectDataCodec.canonical(data)
        val key = ProjectDataCodec.identity(rename)
        return ProjectDataCodec.canonical(before.copy(renames = before.renames.filterNot {
            ProjectDataCodec.identity(it) == key
        } + rename))
    }

    /** Blank edit text deletes the attachment. Blank entries are never serialized into native codeData. */
    fun comment(data: CodeData, comment: CodeComment): Pair<CodeData, CommentAction> {
        val before = ProjectDataCodec.canonical(data)
        ProjectDataCodec.validate(comment.nodeRef, comment.codeRef)
        val key = ProjectDataCodec.identity(comment)
        val exists = before.comments.any { ProjectDataCodec.identity(it) == key }
        val remaining = before.comments.filterNot { ProjectDataCodec.identity(it) == key }
        val result = if (comment.comment.isBlank()) remaining else remaining + comment
        val action = if (comment.comment.isBlank()) CommentAction.REMOVED else if (exists) CommentAction.UPDATED else CommentAction.ADDED
        return ProjectDataCodec.canonical(before.copy(comments = result)) to action
    }

    /** No base exists in ProjectDataPort: absent incoming keys do not delete, conflicting values fail. */
    fun mergeAdditions(local: CodeData, incoming: CodeData): CodeData {
        val left = ProjectDataCodec.canonical(local)
        val right = ProjectDataCodec.canonical(incoming)
        return ProjectDataCodec.canonical(CodeData(
            additions(left.comments, right.comments, ProjectDataCodec::identity),
            additions(left.renames, right.renames, ProjectDataCodec::identity)))
    }

    /** Explicit three-way merge supports deletion and detects edit/edit and edit/delete conflicts. */
    fun merge(base: CodeData, local: CodeData, incoming: CodeData): CodeData {
        val b = ProjectDataCodec.canonical(base)
        val l = ProjectDataCodec.canonical(local)
        val r = ProjectDataCodec.canonical(incoming)
        return ProjectDataCodec.canonical(CodeData(
            mergeValues(b.comments, l.comments, r.comments, ProjectDataCodec::identity),
            mergeValues(b.renames, l.renames, r.renames, ProjectDataCodec::identity)))
    }

    private fun <T> additions(local: List<T>, incoming: List<T>, key: (T) -> AttachmentIdentity): List<T> {
        val result = local.associateBy(key).toMutableMap()
        for (value in incoming) {
            val id = key(value)
            val old = result[id]
            if (old != null && old != value) conflict(id)
            result[id] = value
        }
        return result.values.toList()
    }

    private fun <T> mergeValues(base: List<T>, local: List<T>, incoming: List<T>, key: (T) -> AttachmentIdentity): List<T> {
        val b = base.associateBy(key)
        val l = local.associateBy(key)
        val r = incoming.associateBy(key)
        return (b.keys + l.keys + r.keys).mapNotNull { id ->
            when {
                l[id] == r[id] -> l[id]
                l[id] == b[id] -> r[id]
                r[id] == b[id] -> l[id]
                else -> conflict(id)
            }
        }
    }

    private fun conflict(id: AttachmentIdentity): Nothing = projectError(ErrorCode.CONFLICT, "Concurrent changes at $id")
}
