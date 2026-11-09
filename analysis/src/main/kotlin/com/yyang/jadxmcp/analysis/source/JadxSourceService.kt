package com.yyang.jadxmcp.analysis.source

import com.yyang.jadxmcp.analysis.cache.GenerationCache
import com.yyang.jadxmcp.analysis.metadata.ClassIndex
import com.yyang.jadxmcp.analysis.metadata.ManifestModel
import com.yyang.jadxmcp.analysis.resource.ResourceCatalog
import com.yyang.jadxmcp.analysis.workspace.NativeClassInvalidation
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaMethod
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef
import jadx.core.dex.nodes.MethodNode

/** Source operations depend only on the workspace port; there is no GUI or transport dependency. */
class JadxSourceService(
    private val workspace: WorkspaceAccess<JadxDecompiler>,
    private val revision: () -> String = { "0" },
    private val presentation: SourcePresentation = SourcePresentation(),
    cacheEntries: Int = 64
) : SourceService {
    private data class SourceCacheKey(val className: String, val target: SourceTarget, val signature: String?)
    private data class SourceSlice(val text: String, val firstLine: Int = 1)
    private val cache = GenerationCache<SourceCacheKey, SourceSlice>(cacheEntries)
    private var observedRevision: String? = null // only accessed inside the workspace lease
    private val pendingNativeReloads = mutableSetOf<String>()

    /** Call after host edits. Native code is regenerated under the same exclusive lease. */
    fun invalidate(context: CallContext) = workspace.withDecompiler(context) { decompiler ->
        cache.invalidate()
        invalidateNative(decompiler)
    }

    override fun decompile(request: DecompileRequest, context: CallContext): SourceResult {
        request.validate()
        return workspace.withDecompiler(context) { decompiler ->
            val currentRevision = refreshRevision(decompiler)
            val index = ClassIndex.Snapshot(decompiler.classesWithInners)
            val cls = if (request.target == SourceTarget.MAIN_ACTIVITY) mainActivity(decompiler, index, context)
                else resolveClass(index, requireNotNull(request.className))
            val top = cls.topParentClass
            if (top.rawName in pendingNativeReloads) {
                context.checkActive()
                // An in-flight GUI refresh can refill the native cache after unload. Reload under
                // the native class monitor instead of accepting that previous generation's code.
                top.reload()
                context.checkActive()
                pendingNativeReloads.remove(top.rawName)
            }
            val method = if (request.target == SourceTarget.METHOD) resolveMethod(cls, request) else null
            val signature = method?.methodNode?.methodInfo?.shortId
            val source = cache.get(SourceCacheKey(cls.rawName, request.target, signature), currentRevision, context) {
                readSource(cls, method, request.target)
            }
            // An external edit must not publish an old generation, even on cache hits.
            if (revision() != currentRevision) fail(ErrorCode.CONFLICT, "Source revision changed")
            SourceResult(cls.fullName, method?.name, signature,
                presentation.render(source.text, request.slim, request.withLineNumbers, source.firstLine,
                    if (request.target == SourceTarget.SMALI) "text/x-smali" else "text/x-java-source"))
        }
    }

    /** Preserve initial host caches; later revision changes unload stale code under the same lease. */
    private fun refreshRevision(decompiler: JadxDecompiler): String {
        val currentRevision = revision()
        if (observedRevision != currentRevision) {
            cache.invalidate()
            if (observedRevision != null) invalidateNative(decompiler)
            observedRevision = currentRevision
        }
        return currentRevision
    }

    private fun invalidateNative(decompiler: JadxDecompiler) {
        pendingNativeReloads.clear()
        decompiler.classes.forEach {
            pendingNativeReloads.add(it.rawName)
            NativeClassInvalidation.unload(it)
        }
    }

    private fun readSource(cls: JavaClass, method: JavaMethod?, target: SourceTarget): SourceSlice = when (target) {
        SourceTarget.SMALI -> SourceSlice(cls.smali.takeIf { it.isNotBlank() }
            ?: fail(ErrorCode.UNSUPPORTED, "Smali unavailable for this input"))
        SourceTarget.METHOD -> methodSource(requireNotNull(method))
        // Nested Java classes share their top-level compilation unit; their own code can be empty.
        else -> SourceSlice(cls.topParentClass.code.takeIf { it.isNotBlank() }
            ?: fail(ErrorCode.NOT_FOUND, "Class has no generated source"))
    }

    private fun resolveClass(index: ClassIndex.Snapshot, name: String): JavaClass =
        index.find(name) ?: fail(ErrorCode.NOT_FOUND, "Class '$name' not found")

    private fun resolveMethod(cls: JavaClass, request: DecompileRequest): JavaMethod {
        val signature = request.methodSignature
        val matches = cls.methods.filter {
            (it.name == request.methodName || it.methodNode.methodInfo.name == request.methodName) &&
                (signature == null || matchesSignature(it, signature))
        }
        if (matches.isEmpty()) fail(ErrorCode.NOT_FOUND, "Method '${request.methodName}' not found")
        if (matches.size > 1) fail(ErrorCode.INVALID_ARGUMENT, "Overloaded method requires methodSignature")
        return matches.single()
    }

    private fun matchesSignature(method: JavaMethod, signature: String): Boolean {
        val info = method.methodNode.methodInfo
        return signature == info.shortId || signature == info.shortId.substringAfter('(').let { "($it" } ||
            signature == info.makeSignature(true, true)
    }

    private fun methodSource(method: JavaMethod): SourceSlice {
        val info = method.topParentClass.codeInfo
        val full = info.codeStr
        val annotations = info.codeMetadata.asMap.toSortedMap()
        // GUI disk-cache/reload can leave MethodNode.defPosition from another generation.
        // Resolve the exact declaration in THIS code-info snapshot, never use mutable defPos
        // to slice adjacent/overloaded methods. Both GUI queries and decompile share this path.
        val position = annotations.entries.singleOrNull { (_, annotation) ->
            val node = (annotation as? NodeDeclareRef)?.node as? MethodNode
            node?.methodInfo == method.methodNode.methodInfo
        }?.key ?: fail(ErrorCode.NOT_FOUND, "Method declaration is absent from current source metadata")
        var nested = 0
        val end = annotations.entries.firstOrNull { (offset, annotation) ->
            if (offset <= position) false else when (annotation.annType) {
                ICodeAnnotation.AnnType.DECLARATION -> {
                    val kind = (annotation as NodeDeclareRef).node.annType
                    if (kind == ICodeAnnotation.AnnType.CLASS || kind == ICodeAnnotation.AnnType.METHOD) nested++
                    false
                }
                ICodeAnnotation.AnnType.END -> if (nested == 0) true else { nested--; false }
                else -> false
            }
        }?.key ?: fail(ErrorCode.NOT_FOUND, "Method end is absent from current source metadata")
        // Match native extraction's comments/annotations boundary without regex parsing Java.
        val newLine = method.methodNode.root().args.codeNewLineStr
        val separator = newLine + newLine
        val previous = full.lastIndexOf(separator, position)
        val start = if (previous < 0) position else previous + separator.length
        if (start > end || end > full.length) fail(ErrorCode.INTERNAL, "Invalid method source metadata range")
        val text = full.substring(start, end)
        return SourceSlice(text, full.take(start).count { it == '\n' } + 1)
    }

    /** Share metadata's base/split selection, alias rules and resource-backed boolean resolution. */
    private fun mainActivity(decompiler: JadxDecompiler, index: ClassIndex.Snapshot, context: CallContext): JavaClass {
        val catalog = ResourceCatalog(decompiler, context)
        val xml = catalog.manifestXml()
            ?: fail(ErrorCode.NOT_FOUND, "Android manifest not found")
        val name = ManifestModel.parse(xml, catalog::boolean).mainActivity
            ?: fail(ErrorCode.NOT_FOUND, "Launcher activity not found")
        return resolveClass(index, name)
    }

    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(ProtocolError(code, message))
}
