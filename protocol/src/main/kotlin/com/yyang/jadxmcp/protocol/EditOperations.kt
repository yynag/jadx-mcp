package com.yyang.jadxmcp.protocol

data class RenameRequest(
    override val sessionId: String = "", val targetType: SymbolKind = SymbolKind.CLASS,
    val target: SymbolRef = SymbolRef(), val newName: String = ""
) : BackendRequest {
    override val operation get() = Operation.RENAME
    override fun validate() {
        session(sessionId); nonBlank(target.className, "target.className"); nonBlank(newName, "newName")
        if (targetType != SymbolKind.CLASS) nonBlank(target.memberName ?: "", "target.memberName")
    }
}

data class RenamePackageRequest(
    override val sessionId: String = "", val oldPackageName: String = "", val newPackageName: String = ""
) : BackendRequest {
    override val operation get() = Operation.RENAME_PACKAGE
    override fun validate() {
        session(sessionId); nonBlank(oldPackageName, "oldPackageName"); nonBlank(newPackageName, "newPackageName")
    }
}

data class RenameVariableRequest(
    override val sessionId: String = "", val method: SymbolRef = SymbolRef(),
    val variableName: String = "", val newName: String = "", val reg: Int? = null, val ssa: Int? = null
) : BackendRequest {
    override val operation get() = Operation.RENAME_VARIABLE
    override fun validate() {
        session(sessionId); nonBlank(method.className, "method.className")
        nonBlank(method.memberName ?: "", "method.memberName")
        nonBlank(variableName, "variableName"); nonBlank(newName, "newName")
        if (reg != null && reg < 0 || ssa != null && ssa < 0) invalid("reg/ssa must be non-negative")
    }
}

enum class CommentStyle { LINE, BLOCK, BLOCK_CONDENSED, JAVADOC, JAVADOC_CONDENSED }

data class AddCommentRequest(
    override val sessionId: String = "", val className: String = "", val comment: String = "",
    val methodName: String? = null, val methodSignature: String? = null, val fieldName: String? = null,
    val line: Int? = null, val style: CommentStyle = CommentStyle.LINE
) : BackendRequest {
    override val operation get() = Operation.ADD_COMMENT
    override fun validate() {
        session(sessionId); nonBlank(className, "className")
        if (line != null && line < 1) invalid("line is 1-based")
        if (fieldName != null && (methodName != null || line != null)) invalid("fieldName conflicts with methodName/line")
        if (methodSignature != null && methodName == null) invalid("methodSignature requires methodName")
    }
}

data class ListCommentsRequest(override val sessionId: String = "", val className: String? = null) : BackendRequest {
    override val operation get() = Operation.LIST_COMMENTS
    override fun validate() = session(sessionId)
}

/** Persistence is reported separately: applying a GUI edit must not imply that an unsaved project is on disk. */
data class MutationState(
    val applied: Boolean = false, val persisted: Boolean = false,
    val revision: String = "", val projectPath: String? = null
)
data class RenameResult(
    val target: SymbolRef = SymbolRef(), val oldName: String = "", val newName: String = "",
    val state: MutationState = MutationState()
) : OperationResult
data class RenamePackageResult(val renamed: Int = 0, val state: MutationState = MutationState()) : OperationResult
data class RenameVariableResult(
    val method: SymbolRef = SymbolRef(), val oldName: String = "", val newName: String = "",
    val reg: Int = 0, val ssa: Int = 0, val state: MutationState = MutationState()
) : OperationResult
enum class CommentAction { ADDED, UPDATED, REMOVED }
data class CommentMutationResult(
    val action: CommentAction = CommentAction.ADDED, val state: MutationState = MutationState(),
    val renderedAtLine: Int? = null
) : OperationResult
data class CommentsResult(val comments: List<CodeComment> = emptyList()) : OperationResult
