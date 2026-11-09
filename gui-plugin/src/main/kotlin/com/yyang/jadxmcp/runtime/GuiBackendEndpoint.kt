package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/** All injected ports share ONE borrowed native workspace/project. No transport or GUI discovery. */
data class GuiBackendPorts(
    val metadata: MetadataService,
    val manifest: ManifestService,
    val source: SourceService,
    val resources: ResourceService,
    val search: SearchService,
    val references: ReferenceService,
    val context: GuiContextPort,
    val queries: GuiQueryPort,
    val edits: ProjectEditPort,
    val project: ProjectDataPort,
    val debugger: DebuggerPort? = null
)

/**
 * Typed GUI command dispatch, including all protocol analysis + GUI request types. Management is
 * never tunneled through BackendRequest. close() only fences admission; drain completion includes
 * requests awaiting EDT/native events, not just requests currently holding a workspace lease.
 * Mutation results describe native application separately from persistence; this endpoint does not
 * infer disk durability from an in-memory GUI edit.
 */
class GuiBackendEndpoint(
    private val ports: GuiBackendPorts,
    val projectEpoch: Long,
    private val currentEpoch: () -> Long,
    private val generation: GuiGeneration,
    stringSearchModes: Set<StringSearchMode>,
    private val beforeRead: (CallContext) -> Long = { generation.current() }
) : BackendEndpoint, ProjectDataPort {
    private val monitor = Any()
    private var closed = false
    private var active = 0
    val drained = CompletableFuture<Unit>()

    override val capabilities = capabilities(ports.debugger != null, stringSearchModes)

    private fun check(context: CallContext) {
        context.requireEpoch(currentEpoch()); context.requireEpoch(projectEpoch); context.checkActive()
    }

    private fun <T> call(context: CallContext, mutation: Boolean, action: (CallContext) -> T): T {
        if (SwingUtilities.isEventDispatchThread()) runtimeFailure(ErrorCode.BUSY, "Dispatch backend commands on the bridge worker, not EDT")
        check(context)
        synchronized(monitor) {
            if (closed) runtimeFailure(ErrorCode.CLOSED, "GUI backend is closed")
            // The native GUI owner is not a multi-writer transactional store. The bridge has a
            // serial execution lane; reject accidental concurrent direct callers rather than hold
            // a runtime lock across EDT/native-event waits or race owner imports with reads.
            if (active != 0) runtimeFailure(ErrorCode.BUSY, "GUI backend already has an active command")
            active++
        }
        val effective = context.copy(cancellation = CancellationSignal {
            context.cancellation.isCancelled() || synchronized(monitor) { closed } || currentEpoch() != projectEpoch
        })
        try {
            check(effective)
            // Take the stamp returned by preparation, not a new stamp afterwards: an event in
            // that gap must invalidate the read rather than bless an unprepared new generation.
            val token = beforeRead(effective)
            val result = action(effective)
            check(effective)
            if (!mutation && token != generation.current()) runtimeFailure(ErrorCode.CONFLICT, "GUI generation changed during read")
            return result
        } finally {
            val finished = synchronized(monitor) { active--; closed && active == 0 }
            if (finished) drained.complete(Unit)
        }
    }

    override fun execute(request: BackendRequest, context: CallContext): OperationResult {
        request.validate()
        if (!request.operation.requestType.isInstance(request)) runtimeFailure(ErrorCode.INVALID_ARGUMENT, "Request type does not match operation")
        if (request.operation.scope == OperationScope.MANAGEMENT) runtimeFailure(ErrorCode.UNSUPPORTED, "Session management belongs to master")
        return call(context, request is RenameRequest || request is RenamePackageRequest || request is RenameVariableRequest ||
            request is AddCommentRequest || request is ClearCacheRequest) { effective ->
            val result: OperationResult = when (request) {
                is SummaryRequest -> ports.metadata.summary(request, effective)
                is ManifestRequest -> ports.manifest.manifest(request, effective)
                is ClassRequest -> ports.metadata.classes(request, effective)
                is DecompileRequest -> ports.source.decompile(request, effective)
                is ResourceRequest -> ports.resources.resource(request, effective)
                is SearchRequest -> ports.search.search(request, effective)
                is XrefRequest -> ports.references.references(request, effective)
                is RenameRequest -> ports.edits.rename(request, effective)
                is CurrentClassRequest -> ports.context.currentClass(request, effective)
                is SelectedTextRequest -> ports.context.selectedText(request, effective)
                is MethodByNameRequest -> ports.queries.methodByName(request, effective)
                is AppClassNamesRequest -> ports.queries.appClassNames(request, effective)
                is AppClassCodeRequest -> ports.queries.appClassCode(request, effective)
                is PackageTreeRequest -> ports.queries.packageTree(request, effective)
                is CacheStatsRequest -> ports.queries.cacheStats(request, effective)
                is ClearCacheRequest -> ports.queries.clearCache(request, effective)
                is RenamePackageRequest -> ports.edits.renamePackage(request, effective)
                is RenameVariableRequest -> ports.edits.renameVariable(request, effective)
                is AddCommentRequest -> ports.edits.comment(request, effective)
                is ListCommentsRequest -> ports.edits.comments(request, effective)
                is ThreadsRequest -> ThreadsResult(debugger().snapshot(effective).threads)
                is StackFramesRequest -> StackFramesResult(debugger().snapshot(effective).frames)
                is VariablesRequest -> debugger().snapshot(effective).variables
                else -> runtimeFailure(ErrorCode.UNSUPPORTED, "GUI operation is not assembled: ${request.operation.wireName}")
            }
            if (!request.operation.resultType.isInstance(result)) runtimeFailure(ErrorCode.INTERNAL, "Port returned wrong result type")
            result
        }
    }

    private fun debugger(): DebuggerPort = ports.debugger ?: runtimeFailure(ErrorCode.UNSUPPORTED, "Debugger adapter is disabled")
    override fun snapshot(context: CallContext): ProjectDataSnapshot = call(context, false) { ports.project.snapshot(it) }
    override fun importData(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext): MutationState =
        call(context, true) { ports.project.importData(snapshot, expectedRevision, it) }

    override fun close() {
        val finished = synchronized(monitor) { closed = true; active == 0 }
        if (finished) drained.complete(Unit)
    }

    companion object {
        private val debugOperations = setOf(Operation.DEBUG_GET_THREADS, Operation.DEBUG_GET_STACK_FRAMES, Operation.DEBUG_GET_VARIABLES)
        private val implemented = setOf(Operation.META_SUMMARY, Operation.META_MANIFEST, Operation.META_CLASS,
            Operation.DECOMPILE, Operation.RESOURCE, Operation.XREF, Operation.SEARCH, Operation.RENAME,
            Operation.FETCH_CURRENT_CLASS, Operation.GET_SELECTED_TEXT, Operation.GET_METHOD_BY_NAME,
            Operation.GET_MAIN_APPLICATION_CLASSES_NAMES, Operation.GET_MAIN_APPLICATION_CLASSES_CODE,
            Operation.GET_PACKAGE_TREE, Operation.GET_CACHE_STATS, Operation.CLEAR_CACHE, Operation.RENAME_PACKAGE,
            Operation.RENAME_VARIABLE, Operation.ADD_COMMENT, Operation.LIST_COMMENTS,
            Operation.DEBUG_GET_THREADS, Operation.DEBUG_GET_STACK_FRAMES, Operation.DEBUG_GET_VARIABLES)
        fun capabilities(debugger: Boolean, modes: Set<StringSearchMode>): BackendCapabilities = BackendCapabilities(
            BackendKind.GUI,
            Operation.entries.filter { it.scope != OperationScope.MANAGEMENT }.map { operation ->
                val supported = operation in implemented && (debugger || operation !in debugOperations)
                OperationCapability(operation, supported, reason = when {
                    !supported -> if (operation in debugOperations) "Debugger adapter disabled" else "Operation not assembled"
                    operation == Operation.GET_CACHE_STATS || operation == Operation.CLEAR_CACHE -> "Runtime source-result/generation caches only; never project codeData"
                    operation in debugOperations -> "Requires a connected, paused debugger and populated native models"
                    else -> null
                })
            }, modes.toSet(), debugger
        )
    }
}
