package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler

/** Native GUI borrows the same analysis workspace/services; no second decompiler or GUI reflection. */
class GuiQueryAdapter(
    private val workspace: WorkspaceAccess<JadxDecompiler>,
    private val metadata: MetadataService,
    private val source: SourceService,
    private val stats: () -> CacheStatsResult,
    private val epoch: () -> Long,
    private val invalidate: (CallContext) -> Unit
) : GuiQueryPort {
    override fun methodByName(request: MethodByNameRequest, context: CallContext): SourceResult {
        request.validate(); requireGuiWorker(); context.checkActive()
        // Named queries use exactly the decompile METHOD resolver (aliases, '$' inner classes,
        // overload descriptors and source generation). Only unqualified queries need discovery.
        request.className?.let { name ->
            return source.decompile(DecompileRequest(request.sessionId, SourceTarget.METHOD, name,
                request.methodName, request.methodSignature, request.slim), context)
        }
        val target = workspace.withDecompiler(context) { d ->
            val classes = d.classesWithInners
            val matches = classes.asSequence().flatMap { it.methods.asSequence() }.filter { m ->
                context.checkActive()
                val info = m.methodNode.methodInfo
                (m.name == request.methodName || info.name == request.methodName || m.fullName == request.methodName) &&
                    (request.methodSignature == null || request.methodSignature == info.shortId ||
                        request.methodSignature == info.shortId.substringAfter(info.name) ||
                        request.methodSignature == info.makeSignature(true, true))
            }.take(2).toList()
            if (matches.isEmpty()) guiFailure(ErrorCode.NOT_FOUND, "Method not found")
            if (matches.size != 1) guiFailure(ErrorCode.INVALID_ARGUMENT, "Ambiguous method; provide className/methodSignature")
            val m = matches.single()
            SymbolRef(m.declaringClass.rawName, m.methodNode.methodInfo.name, m.methodNode.methodInfo.shortId.substringAfter(m.methodNode.methodInfo.name))
        }
        // Release lease before invoking the shared source port (which acquires its own lease).
        return source.decompile(DecompileRequest(request.sessionId, SourceTarget.METHOD, target.className,
            target.memberName, target.descriptor, request.slim), context)
    }

    override fun appClassNames(request: AppClassNamesRequest, context: CallContext): AppClassNamesResult {
        request.validate(); requireGuiWorker(); context.checkActive()
        val pkg = metadata.summary(SummaryRequest(request.sessionId), context).mainPackage
            ?.takeIf { it.isNotBlank() } ?: guiFailure(ErrorCode.NOT_FOUND, "Manifest application package is unavailable")
        return workspace.withDecompiler(context) { d ->
            AppClassNamesResult(d.classesWithInners.filter {
                context.checkActive()
                val rawPkg = it.classNode.classInfo.`package`
                rawPkg == pkg || rawPkg.startsWith("$pkg.")
            }.map { it.rawName }.sorted())
        }
    }

    override fun appClassCode(request: AppClassCodeRequest, context: CallContext): AppClassCodeResult {
        request.validate(); requireGuiWorker(); context.checkActive()
        val names = appClassNames(AppClassNamesRequest(request.sessionId), context).classes
        val start = request.page.offset.coerceAtMost(names.size)
        val end = (start.toLong() + request.page.count).coerceAtMost(names.size.toLong()).toInt()
        val classes = names.subList(start, end).map {
            context.checkActive()
            source.decompile(DecompileRequest(request.sessionId, className = it, slim = request.slim), context)
        }
        return AppClassCodeResult(classes, PageInfo(request.page.offset, classes.size, names.size, end.takeIf { it < names.size }),
            classes.any { it.text.truncated })
    }

    override fun packageTree(request: PackageTreeRequest, context: CallContext): PackageTreeResult {
        request.validate(); requireGuiWorker(); context.checkActive()
        return workspace.withDecompiler(context) { d ->
            val counts = d.classesWithInners.groupingBy { context.checkActive(); it.`package` }.eachCount()
            PackageTreeResult(counts.entries.sortedBy { it.key }.map { PackageEntry(it.key, it.value) }, counts.size, counts.values.sum())
        }
    }

    override fun cacheStats(request: CacheStatsRequest, context: CallContext): CacheStatsResult {
        request.validate(); context.checkActive(); context.requireEpoch(epoch())
        return stats().also { context.checkActive(); context.requireEpoch(epoch()) }
    }

    override fun clearCache(request: ClearCacheRequest, context: CallContext): ClearCacheResult {
        request.validate(); requireGuiWorker(); context.checkActive(); context.requireEpoch(epoch())
        invalidate(context)
        context.checkActive(); context.requireEpoch(epoch())
        return ClearCacheResult(true, stats()).also { context.checkActive(); context.requireEpoch(epoch()) }
    }
}
