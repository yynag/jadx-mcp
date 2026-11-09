package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import java.io.File
import java.util.UUID

/**
 * One in-memory state owner per workspace. No disk load, auto-save or synchronization is performed.
 * Ports without CAS fields serialize on the workspace; [replaceData]/[mergeData] expose explicit CAS.
 * Native GUI owners may reuse the codec/planners directly, and must marshal host edits themselves.
 * current is applied in native memory; persistedRevision/path are independent acknowledgements and
 * are never advanced merely because a mutation succeeded.
 */
class JadxProjectService private constructor(
    private val workspace: WorkspaceAccess<JadxDecompiler>,
    private val files: List<File>,
    initial: ProjectDataSnapshot,
    private val native: NativeProjectData,
    private val store: ProjectOwnerStore?
) : ProjectDataPort, ProjectEditPort {
    private val monitor = Any()
    @Volatile private var current = initial
    private val history = linkedMapOf(initial.revision to initial)
    private var persistedRevision: String? = null
    private var persistedPath: String? = null
    val revision: String get() = current.revision

    override fun snapshot(context: CallContext): ProjectDataSnapshot = access(context) { _, _ -> current }

    override fun importData(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext): MutationState =
        access(context) { _, symbols ->
            requireRevision(expectedRevision)
            validateSnapshot(snapshot)
            val merged = ProjectDataEdits.mergeAdditions(current.data, snapshot.data)
            commit(merged, symbols, context)
        }

    /** Explicit replacement is NOT the import/sync default. Deletions require a current CAS token. */
    fun replaceData(data: CodeData, expectedRevision: String, context: CallContext): MutationState = access(context) { _, symbols ->
        requireRevision(expectedRevision)
        commit(ProjectDataCodec.canonical(data), symbols, context)
    }

    /** Only retained, authentic local base revisions can authorize a three-way merge. */
    fun mergeData(base: ProjectDataSnapshot, incoming: ProjectDataSnapshot, expectedRevision: String,
                   context: CallContext): MutationState = access(context) { _, symbols ->
        requireRevision(expectedRevision)
        validateSnapshot(base)
        validateSnapshot(incoming)
        val known = history[base.revision] ?: projectError(ErrorCode.CONFLICT, "Unknown/expired merge base")
        if (known.data != ProjectDataCodec.canonical(base.data)) projectError(ErrorCode.CONFLICT, "Merge base contents do not match revision")
        commit(ProjectDataEdits.merge(base.data, current.data, incoming.data), symbols, context)
    }

    /** Explicit single-owner save. A native edit never implies persistence. BORROWED cannot use a file owner store. */
    fun persist(expectedRevision: String, context: CallContext): MutationState = access(context) { _, _ ->
        requireRevision(expectedRevision)
        if (workspace.ownership != WorkspaceOwnership.OWNED) projectError(ErrorCode.UNSUPPORTED, "GUI project persistence belongs to its native owner")
        val owner = store ?: projectError(ErrorCode.UNSUPPORTED, "No project owner store configured")
        context.checkActive()
        val receipt = try { owner.save(current, context) } catch (failure: ProtocolException) { throw failure }
        catch (failure: Exception) {
            throw ProtocolException(ProtocolError(ErrorCode.PERSISTENCE_FAILED, "Owner save failed: ${failure.message}"))
        }
        if (receipt.revision != current.revision) projectError(ErrorCode.PERSISTENCE_FAILED, "Owner acknowledged a different revision")
        persistedRevision = receipt.revision
        persistedPath = receipt.projectPath
        state(false)
    }

    /** Explicit adoption of an edit made by the host in native memory, not a read from any external file. */
    fun refreshFromHost(context: CallContext): ProjectDataSnapshot = workspace.withDecompiler(context) { decompiler ->
        synchronized(monitor) {
            verifyInputs(context)
            val data = native.read()
            native.symbols().validate(data, context)
            context.checkActive()
            if (data != current.data) advance(data)
            current
        }
    }

    override fun rename(request: RenameRequest, context: CallContext): RenameResult {
        request.validate()
        ProjectDataCodec.identifier(request.newName)
        return access(context) { _, symbols ->
            val node = when (request.targetType) {
                SymbolKind.CLASS -> symbols.clazz(request.target.className)
                SymbolKind.METHOD -> symbols.method(request.target)
                SymbolKind.FIELD -> symbols.field(request.target)
            }
            val ref = symbols.reference(node)
            symbols.requireRenameAvailable(ref, request.newName)
            // A single method edit changes the native override group. Retarget its existing
            // mappings too, so editing through another member cannot conflict with our prior edit.
            val before = if (ref.type == NodeReferenceKind.METHOD) {
                val group = symbols.methodRenameGroup(symbols.nativeMethod(ref)).mapTo(hashSetOf()) {
                    NodeReference(NodeReferenceKind.METHOD, it.declaringClass.classInfo.rawName, it.methodInfo.shortId)
                }
                current.data.copy(renames = current.data.renames.map {
                    if (it.codeRef == null && it.nodeRef in group) it.copy(newName = request.newName) else it
                })
            } else current.data
            val data = ProjectDataEdits.rename(before, CodeRename(ref, newName = request.newName))
            val oldName = node.name
            val result = commit(data, symbols, context)
            RenameResult(stableSymbol(ref), oldName, request.newName, result)
        }
    }

    override fun renamePackage(request: RenamePackageRequest, context: CallContext): RenamePackageResult {
        request.validate()
        ProjectDataCodec.packageName(request.newPackageName)
        return access(context) { decompiler, symbols ->
            val ref = symbols.packageReference(request.oldPackageName)
            symbols.requireRenameAvailable(ref, request.newPackageName)
            val count = decompiler.classesWithInners.count {
                val pkg = it.classNode.classInfo.`package`
                pkg == ref.declaringClass || pkg.startsWith(ref.declaringClass + ".")
            }
            val state = commit(ProjectDataEdits.rename(current.data, CodeRename(ref, newName = request.newPackageName)),
                symbols, context)
            RenamePackageResult(if (state.applied) count else 0, state)
        }
    }

    override fun renameVariable(request: RenameVariableRequest, context: CallContext): RenameVariableResult {
        request.validate()
        ProjectDataCodec.identifier(request.newName)
        return access(context) { _, symbols ->
            val method = symbols.method(request.method)
            val variable = symbols.variable(method, request.variableName, request.reg, request.ssa)
            if (symbols.variables(method).any { it != variable && it.name == request.newName })
                projectError(ErrorCode.CONFLICT, "Variable name already exists in method")
            val ref = symbols.reference(method)
            val rename = CodeRename(ref, ProjectDataCodec.variable(variable.reg, variable.ssa), request.newName)
            val state = commit(ProjectDataEdits.rename(current.data, rename), symbols, context)
            RenameVariableResult(stableSymbol(ref), request.variableName,
                request.newName, variable.reg, variable.ssa, state)
        }
    }

    override fun comment(request: AddCommentRequest, context: CallContext): CommentMutationResult {
        request.validate()
        return access(context) { _, symbols ->
            val cls = symbols.clazz(request.className)
            val method = request.methodName?.let {
                symbols.method(SymbolRef(request.className, it, request.methodSignature))
            }
            val target = when {
                request.line != null -> symbols.instructionAtLine(cls, method, requireNotNull(request.line))
                request.fieldName != null -> AttachmentIdentity(symbols.reference(symbols.field(SymbolRef(request.className, request.fieldName))))
                method != null -> AttachmentIdentity(symbols.reference(method))
                else -> AttachmentIdentity(symbols.reference(cls))
            }
            val (data, action) = ProjectDataEdits.comment(current.data,
                CodeComment(target.node, target.code, request.comment, request.style))
            // Regeneration can move the statement/comment. Do not echo the old line as a new rendered position.
            CommentMutationResult(action, commit(data, symbols, context))
        }
    }

    override fun comments(request: ListCommentsRequest, context: CallContext): CommentsResult {
        request.validate()
        return access(context) { _, symbols ->
            val raw = request.className?.let { symbols.reference(symbols.clazz(it)).declaringClass }
            CommentsResult(current.data.comments.filter { raw == null || it.nodeRef.declaringClass == raw })
        }
    }

    private fun <T> access(context: CallContext, action: (JadxDecompiler, ProjectSymbols) -> T): T =
        workspace.withDecompiler(context) { decompiler -> synchronized(monitor) {
            verifyInputs(context)
            if (native.read() != current.data) projectError(ErrorCode.CONFLICT, "Native codeData changed outside this owner; explicitly refresh first")
            context.checkActive()
            action(decompiler, native.symbols())
        } }

    private fun commit(data: CodeData, symbols: ProjectSymbols, context: CallContext): MutationState {
        val canonical = ProjectDataCodec.canonical(data)
        symbols.validate(canonical, context)
        context.checkActive()
        if (canonical == current.data) return state(false)
        applyWithRollback(canonical, context)
        advance(canonical)
        return state(true)
    }

    /**
     * Native application can fail partway through or finish after cancellation. Restore aliases
     * and codeData before exposing a new revision; a failed rollback stays attached to the cause.
     */
    private fun applyWithRollback(after: CodeData, context: CallContext) {
        val before = current.data
        try {
            native.apply(before, after)
            context.checkActive()
        } catch (failure: Throwable) {
            try { native.apply(after, before) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun advance(data: CodeData) {
        current = ProjectDataSnapshot(current.inputs, UUID.randomUUID().toString(), data)
        history[current.revision] = current
        if (history.size > 64) history.remove(history.keys.first())
    }

    private fun state(applied: Boolean) = MutationState(applied, persistedRevision == revision, revision,
        if (persistedRevision == revision) persistedPath else null)
    private fun stableSymbol(ref: NodeReference): SymbolRef = when (ref.type) {
        NodeReferenceKind.METHOD -> SymbolRef(ref.declaringClass, ref.shortId.substringBefore('('),
            ref.shortId.substring(ref.shortId.indexOf('(')))
        NodeReferenceKind.FIELD -> SymbolRef(ref.declaringClass, ref.shortId.substringBefore(':'), ref.shortId.substringAfter(':'))
        else -> SymbolRef(ref.declaringClass)
    }
    /** Compare under [access]'s lease and monitor so a stale snapshot cannot overwrite a newer edit. */
    private fun requireRevision(expected: String) {
        if (expected != revision) projectError(ErrorCode.CONFLICT, "Project revision changed")
    }
    private fun validateSnapshot(snapshot: ProjectDataSnapshot) {
        snapshot.validate()
        if (InputIdentity.canonical(snapshot.inputs) != current.inputs) projectError(ErrorCode.CONFLICT, "Input content SHA-256 identities differ")
        ProjectDataCodec.canonical(snapshot.data)
    }
    private fun verifyInputs(context: CallContext) {
        if (InputIdentity.fingerprint(files, context) != current.inputs) projectError(ErrorCode.CONFLICT, "Workspace input content changed")
    }

    companion object {
        fun open(workspace: WorkspaceAccess<JadxDecompiler>, context: CallContext,
                 store: ProjectOwnerStore? = null): JadxProjectService {
            if (store != null && workspace.ownership == WorkspaceOwnership.BORROWED)
                projectError(ErrorCode.INVALID_ARGUMENT, "An external owner store cannot write a native GUI project")
            return workspace.withDecompiler(context) { decompiler ->
                val files = decompiler.args.inputFiles.toList()
                val inputs = InputIdentity.fingerprint(files, context)
                val native = NativeProjectData(decompiler)
                val data = native.read()
                native.symbols().validate(data, context)
                JadxProjectService(workspace, files, ProjectDataSnapshot(inputs, UUID.randomUUID().toString(), data), native, store)
            }
        }
    }
}
