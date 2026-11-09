package com.yyang.jadxmcp.protocol

enum class OperationScope { MANAGEMENT, ANALYSIS, GUI }

/** Single registry for wire dispatch, result typing, tool names, and static backend scope. */
enum class Operation(
    val wireName: String,
    val requestType: Class<out OperationRequest>,
    val resultType: Class<out OperationResult>,
    val scope: OperationScope
) {
    SESSION_CREATE("session_create", CreateSessionRequest::class.java, SessionInfo::class.java, OperationScope.MANAGEMENT),
    SESSION_LIST("session_list", ListSessionsRequest::class.java, SessionListResult::class.java, OperationScope.MANAGEMENT),
    SESSION_CLOSE("session_close", CloseSessionRequest::class.java, CloseSessionResult::class.java, OperationScope.MANAGEMENT),
    META_SUMMARY("meta_summary", SummaryRequest::class.java, SummaryResult::class.java, OperationScope.ANALYSIS),
    META_MANIFEST("meta_manifest", ManifestRequest::class.java, ManifestResult::class.java, OperationScope.ANALYSIS),
    META_CLASS("meta_class", ClassRequest::class.java, ClassResult::class.java, OperationScope.ANALYSIS),
    DECOMPILE("decompile", DecompileRequest::class.java, SourceResult::class.java, OperationScope.ANALYSIS),
    RESOURCE("resource", ResourceRequest::class.java, ResourceResult::class.java, OperationScope.ANALYSIS),
    XREF("xref", XrefRequest::class.java, XrefResult::class.java, OperationScope.ANALYSIS),
    SEARCH("search", SearchRequest::class.java, SearchResult::class.java, OperationScope.ANALYSIS),
    RENAME("rename", RenameRequest::class.java, RenameResult::class.java, OperationScope.ANALYSIS),
    FETCH_CURRENT_CLASS("fetch_current_class", CurrentClassRequest::class.java, SourceResult::class.java, OperationScope.GUI),
    GET_SELECTED_TEXT("get_selected_text", SelectedTextRequest::class.java, SelectionResult::class.java, OperationScope.GUI),
    GET_METHOD_BY_NAME("get_method_by_name", MethodByNameRequest::class.java, SourceResult::class.java, OperationScope.GUI),
    GET_MAIN_APPLICATION_CLASSES_NAMES("get_main_application_classes_names", AppClassNamesRequest::class.java, AppClassNamesResult::class.java, OperationScope.GUI),
    GET_MAIN_APPLICATION_CLASSES_CODE("get_main_application_classes_code", AppClassCodeRequest::class.java, AppClassCodeResult::class.java, OperationScope.GUI),
    GET_PACKAGE_TREE("get_package_tree", PackageTreeRequest::class.java, PackageTreeResult::class.java, OperationScope.GUI),
    GET_CACHE_STATS("get_cache_stats", CacheStatsRequest::class.java, CacheStatsResult::class.java, OperationScope.GUI),
    CLEAR_CACHE("clear_cache", ClearCacheRequest::class.java, ClearCacheResult::class.java, OperationScope.GUI),
    RENAME_PACKAGE("rename_package", RenamePackageRequest::class.java, RenamePackageResult::class.java, OperationScope.GUI),
    RENAME_VARIABLE("rename_variable", RenameVariableRequest::class.java, RenameVariableResult::class.java, OperationScope.GUI),
    ADD_COMMENT("add_comment", AddCommentRequest::class.java, CommentMutationResult::class.java, OperationScope.GUI),
    LIST_COMMENTS("list_comments", ListCommentsRequest::class.java, CommentsResult::class.java, OperationScope.GUI),
    DEBUG_GET_STACK_FRAMES("debug_get_stack_frames", StackFramesRequest::class.java, StackFramesResult::class.java, OperationScope.GUI),
    DEBUG_GET_THREADS("debug_get_threads", ThreadsRequest::class.java, ThreadsResult::class.java, OperationScope.GUI),
    DEBUG_GET_VARIABLES("debug_get_variables", VariablesRequest::class.java, VariablesResult::class.java, OperationScope.GUI);

    fun allowsBackend(backend: BackendKind): Boolean = when {
        this == SESSION_CREATE -> backend == BackendKind.HEADLESS
        scope == OperationScope.GUI -> backend == BackendKind.GUI
        else -> true
    }

    companion object {
        fun fromWireName(name: String): Operation = entries.firstOrNull { it.wireName == name }
            ?: invalid("Unknown operation '$name'")
    }
}
