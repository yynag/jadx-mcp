package com.yyang.jadxmcp.protocol

enum class WorkspaceOwnership { OWNED, BORROWED }

/**
 * Analysis binds D to jadx.api.JadxDecompiler. The generic boundary keeps the protocol free
 * of JADX dependencies while services share one native instance. The implementation owns
 * epoch checks and lifetime leases.
 * A borrowed GUI workspace MUST NOT close its host decompiler.
 */
interface WorkspaceAccess<D : Any> : AutoCloseable {
    val ownership: WorkspaceOwnership
    val projectEpoch: Long
    fun <T> withDecompiler(context: CallContext, action: (D) -> T): T
    override fun close()
}

interface MetadataService {
    fun summary(request: SummaryRequest, context: CallContext): SummaryResult
    fun classes(request: ClassRequest, context: CallContext): ClassResult
}
interface ManifestService {
    fun manifest(request: ManifestRequest, context: CallContext): ManifestResult
}
interface SourceService {
    fun decompile(request: DecompileRequest, context: CallContext): SourceResult
}
interface ResourceService {
    fun resource(request: ResourceRequest, context: CallContext): ResourceResult
}
interface SearchService {
    fun search(request: SearchRequest, context: CallContext): SearchResult
}
interface ReferenceService {
    fun references(request: XrefRequest, context: CallContext): XrefResult
}

/** GUI implementations marshal reads to the EDT; analysis and HTTP waits must stay off the EDT. */
interface GuiContextPort {
    fun currentClass(request: CurrentClassRequest, context: CallContext): SourceResult
    fun selectedText(request: SelectedTextRequest, context: CallContext): SelectionResult
}
interface UiExecutor {
    fun <T> readOrEdit(context: CallContext, action: () -> T): T
}
interface GuiQueryPort {
    fun methodByName(request: MethodByNameRequest, context: CallContext): SourceResult
    fun appClassNames(request: AppClassNamesRequest, context: CallContext): AppClassNamesResult
    fun appClassCode(request: AppClassCodeRequest, context: CallContext): AppClassCodeResult
    fun packageTree(request: PackageTreeRequest, context: CallContext): PackageTreeResult
    fun cacheStats(request: CacheStatsRequest, context: CallContext): CacheStatsResult
    fun clearCache(request: ClearCacheRequest, context: CallContext): ClearCacheResult
}
interface ProjectEditPort {
    fun rename(request: RenameRequest, context: CallContext): RenameResult
    fun renamePackage(request: RenamePackageRequest, context: CallContext): RenamePackageResult
    fun renameVariable(request: RenameVariableRequest, context: CallContext): RenameVariableResult
    fun comment(request: AddCommentRequest, context: CallContext): CommentMutationResult
    fun comments(request: ListCommentsRequest, context: CallContext): CommentsResult
}
interface DebuggerPort {
    fun snapshot(context: CallContext): DebuggerSnapshot
}

/** GUI control plane; poll and completion are independent of the analysis execution queue. */
data class GuiRegistration(
    val instanceId: String = "", val name: String = "", val projectEpoch: Long = 0,
    val capabilities: BackendCapabilities = BackendCapabilities(BackendKind.GUI)
)
data class GuiLease(val sessionId: String = "", val token: String = "")
enum class PollStatus { COMMAND, IDLE, CLOSED }
data class PollResult(val status: PollStatus, val command: RequestFrame? = null)
data class CommandCompletion(
    val requestId: String, val projectEpoch: Long, val operation: Operation,
    val result: OperationResult? = null, val error: ProtocolError? = null
) {
    init {
        nonBlank(requestId, "requestId")
        if (projectEpoch < 0 || (result == null) == (error == null)) invalid("Completion requires exactly one result/error and a valid epoch")
    }
}
interface GuiChannelPort {
    fun register(registration: GuiRegistration): GuiLease
    fun poll(lease: GuiLease, context: CallContext): PollResult
    fun complete(lease: GuiLease, completion: CommandCompletion)
    fun heartbeat(lease: GuiLease, projectEpoch: Long)
    fun unregister(lease: GuiLease)
}
