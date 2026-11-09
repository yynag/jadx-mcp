package com.yyang.jadxmcp.analysis.search

import com.yyang.jadxmcp.analysis.metadata.ClassIndex
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import jadx.api.JavaClass

/**
 * All native reads stay in one workspace lease. Name searches deliberately use ClassNode
 * members: JavaClass.getMethods/getFields can silently trigger full decompilation.
 * Scan budget counts candidate classes; source budget counts unique top-level text reads,
 * including cache hits. Stopping at either limit marks the result incomplete, not exhausted.
 */
class SearchService(private val workspace: WorkspaceAccess<JadxDecompiler>) : com.yyang.jadxmcp.protocol.SearchService {
    override fun search(request: SearchRequest, context: CallContext): SearchResult {
        request.validate()
        context.checkActive()
        return workspace.withDecompiler(context) { decompiler ->
            val index = ClassIndex.Snapshot(decompiler.classesWithInners)
            val usesDexConstants = request.scope == SearchScope.STRING && request.stringMode == StringSearchMode.DEX_CONSTANTS
            if (usesDexConstants && index.classes.none { DexConstants.supports(it.classNode) }) {
                fail(ErrorCode.UNSUPPORTED, "DEX_CONSTANTS requires native DEX input; use SOURCE_TEXT explicitly for JAR/Java input")
            }
            val candidates = request.packageName?.let(index::inPackage) ?: index.classes
            val candidateNames = candidates.mapTo(hashSetOf()) { it.rawName }
            val classes = if (usesDexConstants) candidates.filter { DexConstants.supports(it.classNode) } else candidates
            val hits = mutableListOf<SearchHit>()
            var scannedClasses = 0
            var sourceReads = 0
            var complete = true
            val sourceParents = hashSetOf<String>()
            val needsSource = request.scope == SearchScope.CODE || request.scope == SearchScope.COMMENT ||
                (request.scope == SearchScope.STRING && !usesDexConstants)
            fun matches(value: String) = value.contains(request.term, ignoreCase = true)
            for (cls in classes) {
                context.checkActive()
                if (scannedClasses >= request.budget.maxScan) { complete = false; break }
                if (needsSource && cls.topParentClass.rawName !in sourceParents && sourceReads >= request.budget.maxDecompile) {
                    complete = false
                    break
                }
                scannedClasses++
                when (request.scope) {
                    SearchScope.CLASS -> if (matches(cls.rawName) || matches(cls.fullName))
                        hits += SearchHit(symbol(cls.classNode), cls.fullName)
                    SearchScope.METHOD_NAME -> for (method in cls.classNode.methods.sortedBy { it.methodInfo.shortId }) {
                        context.checkActive()
                        if (matches(method.name) || matches(method.alias)) hits += SearchHit(symbol(method), method.alias)
                    }
                    SearchScope.FIELD -> for (field in cls.classNode.fields.sortedBy { it.fieldInfo.shortId }) {
                        context.checkActive()
                        if (matches(field.name) || matches(field.alias)) hits += SearchHit(symbol(field), field.alias)
                    }
                    SearchScope.STRING, SearchScope.CODE, SearchScope.COMMENT -> {
                        if (usesDexConstants) DexConstants.scan(cls.classNode, context) { if (matches(it.value)) hits += it }
                        else {
                            val parent = cls.topParentClass
                            if (!sourceParents.add(parent.rawName)) continue
                            // Budget unique top-level source reads, including cache hits: an inner
                            // class shares its parent's code and must not consume another read.
                            sourceReads++
                            hits += sourceHits(decompiler, parent, request, candidateNames, context)
                        }
                    }
                }
            }
            context.checkActive()
            val (paged, page) = resultPage(hits, request.page, complete)
            SearchResult(paged, page, ScanStats(scannedClasses, sourceReads, complete),
                request.stringMode.takeIf { request.scope == SearchScope.STRING })
        }
    }

    /** Read and attribute one parent's text inside the caller's workspace lease. */
    private fun sourceHits(
        decompiler: JadxDecompiler,
        parent: JavaClass,
        request: SearchRequest,
        candidateNames: Set<String>,
        context: CallContext
    ): List<SearchHit> {
        context.checkActive()
        val code = parent.codeInfo
        context.checkActive()
        val text = code.codeStr
        val ranges = if (request.scope == SearchScope.COMMENT) commentRanges(text) { context.checkActive() }
            else listOf(text.indices)
        // Index once: repeatedly counting prefixes becomes quadratic for many matching lines.
        val lineStarts = mutableListOf(0)
        text.forEachIndexed { index, char ->
            if (index % 4096 == 0) context.checkActive()
            if (char == '\n') lineStarts += index + 1
        }
        val hits = mutableListOf<SearchHit>()
        for (range in ranges) {
            var offset = range.first
            var lineNumber = lineStarts.binarySearch(offset).let { if (it >= 0) it + 1 else -it - 1 }
            for (line in text.substring(range).split('\n')) {
                context.checkActive()
                if (line.contains(request.term, ignoreCase = true)) {
                    val enclosing = decompiler.getEnclosingNode(code, offset)
                    val ref = enclosing?.let(::symbol) ?: symbol(parent.classNode)
                    val inPackage = request.packageName == null || ref.className in candidateNames
                    if (inPackage) hits += SearchHit(ref, line, line.trim().take(240), lineNumber)
                }
                offset += line.length + 1
                lineNumber++
            }
        }
        return hits
    }
}
