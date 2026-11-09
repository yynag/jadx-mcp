package com.yyang.jadxmcp.protocol

data class CurrentClassRequest(override val sessionId: String = "", val slim: Boolean = false) : BackendRequest {
    override val operation get() = Operation.FETCH_CURRENT_CLASS
    override fun validate() = session(sessionId)
}

data class SelectedTextRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.GET_SELECTED_TEXT
    override fun validate() = session(sessionId)
}

data class MethodByNameRequest(
    override val sessionId: String = "", val methodName: String = "", val className: String? = null,
    val methodSignature: String? = null, val slim: Boolean = false
) : BackendRequest {
    override val operation get() = Operation.GET_METHOD_BY_NAME
    override fun validate() { session(sessionId); nonBlank(methodName, "methodName") }
}

data class AppClassNamesRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.GET_MAIN_APPLICATION_CLASSES_NAMES
    override fun validate() = session(sessionId)
}

data class AppClassCodeRequest(
    override val sessionId: String = "", val page: PageRequest = PageRequest(), val slim: Boolean = false
) : BackendRequest {
    override val operation get() = Operation.GET_MAIN_APPLICATION_CLASSES_CODE
    override fun validate() { session(sessionId); page.validate() }
}

data class PackageTreeRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.GET_PACKAGE_TREE
    override fun validate() = session(sessionId)
}

data class CacheStatsRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.GET_CACHE_STATS
    override fun validate() = session(sessionId)
}

data class ClearCacheRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.CLEAR_CACHE
    override fun validate() = session(sessionId)
}

data class SelectionResult(val text: String = "", val className: String? = null) : OperationResult
data class AppClassNamesResult(val classes: List<String> = emptyList()) : OperationResult
data class AppClassCodeResult(
    val classes: List<SourceResult> = emptyList(), val page: PageInfo = PageInfo(), val truncated: Boolean = false
) : OperationResult
data class PackageEntry(val name: String = "", val classCount: Int = 0, val likelyLibrary: Boolean = false)
data class PackageTreeResult(
    val packages: List<PackageEntry> = emptyList(), val totalPackages: Int = 0,
    val totalClasses: Int = 0, val truncated: Boolean = false
) : OperationResult
data class CacheStatsResult(
    val entries: Int = 0, val hits: Long = 0, val misses: Long = 0,
    val storedBytes: Long = 0, val originalBytes: Long = 0
) : OperationResult
data class ClearCacheResult(val cleared: Boolean = false, val stats: CacheStatsResult = CacheStatsResult()) : OperationResult
