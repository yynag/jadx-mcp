package com.yyang.jadxmcp.protocol

enum class SourceTarget { JAVA, SMALI, METHOD, MAIN_ACTIVITY }

data class DecompileRequest(
    override val sessionId: String = "", val target: SourceTarget = SourceTarget.JAVA,
    val className: String? = null, val methodName: String? = null, val methodSignature: String? = null,
    val slim: Boolean = false, val withLineNumbers: Boolean = false
) : BackendRequest {
    override val operation get() = Operation.DECOMPILE
    override fun validate() {
        session(sessionId)
        if (target != SourceTarget.MAIN_ACTIVITY) nonBlank(className ?: "", "className")
        if (target == SourceTarget.METHOD) nonBlank(methodName ?: "", "methodName")
        if (methodSignature != null && target != SourceTarget.METHOD) invalid("methodSignature requires METHOD")
        if (target == SourceTarget.SMALI && (slim || withLineNumbers)) invalid("Source presentation options do not apply to Smali")
    }
}

data class SourceResult(
    val className: String = "", val methodName: String? = null, val methodSignature: String? = null,
    val text: TextContent = TextContent()
) : OperationResult

enum class SearchScope { CLASS, STRING, CODE, METHOD_NAME, FIELD, COMMENT }

data class SearchRequest(
    override val sessionId: String = "", val scope: SearchScope = SearchScope.CLASS,
    val term: String = "", val packageName: String? = null,
    val stringMode: StringSearchMode = StringSearchMode.DEX_CONSTANTS,
    val page: PageRequest = PageRequest(), val budget: SearchBudget = SearchBudget()
) : BackendRequest {
    override val operation get() = Operation.SEARCH
    override fun validate() { session(sessionId); nonBlank(term, "term"); page.validate(); budget.validate() }
}

data class SearchHit(
    val symbol: SymbolRef = SymbolRef(), val value: String = "", val preview: String? = null, val line: Int? = null
)

/** decompiled counts unique source-access attempts (cache hits included), not newly generated code. */
data class ScanStats(val scanned: Int = 0, val decompiled: Int = 0, val complete: Boolean = true)
data class SearchResult(
    val hits: List<SearchHit> = emptyList(), val page: PageInfo = PageInfo(),
    val stats: ScanStats = ScanStats(), val stringMode: StringSearchMode? = null
) : OperationResult

enum class SymbolKind { CLASS, METHOD, FIELD }

data class XrefRequest(
    override val sessionId: String = "", val targetType: SymbolKind = SymbolKind.CLASS,
    val target: SymbolRef = SymbolRef(), val page: PageRequest = PageRequest()
) : BackendRequest {
    override val operation get() = Operation.XREF
    override fun validate() {
        session(sessionId); nonBlank(target.className, "target.className"); page.validate()
        if (targetType != SymbolKind.CLASS) nonBlank(target.memberName ?: "", "target.memberName")
    }
}

data class ReferenceHit(
    val source: SymbolRef = SymbolRef(), val nodeType: String = "",
    val snippet: String? = null, val methodCode: TextContent? = null
)
data class XrefResult(val references: List<ReferenceHit> = emptyList(), val page: PageInfo = PageInfo()) : OperationResult
