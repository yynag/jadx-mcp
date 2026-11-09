package com.yyang.jadxmcp.analysis.metadata

import com.yyang.jadxmcp.analysis.resource.ResourceCatalog
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler

class MetadataService(private val workspace: WorkspaceAccess<JadxDecompiler>) : com.yyang.jadxmcp.protocol.MetadataService {
    override fun summary(request: SummaryRequest, context: CallContext): SummaryResult {
        request.validate()
        return workspace.withDecompiler(context) { decompiler ->
            context.checkActive()
            val classes = ClassIndex.Snapshot(decompiler.classesWithInners).classes
            val catalog = ResourceCatalog(decompiler, context)
            val manifest = catalog.manifestXml()?.let { ManifestModel.parse(it, catalog::boolean) }
            val packages = classes.groupingBy { it.classNode.classInfo.`package` }.eachCount()
                .map { PackageCount(it.key, it.value) }
                .sortedWith(compareByDescending<PackageCount> { it.classes }.thenBy { it.name }).take(20)
            SummaryResult(classes.size, manifest?.mainActivity, manifest?.packageName,
                decompiler.args.isDeobfuscationOn, packages)
        }
    }

    override fun classes(request: ClassRequest, context: CallContext): ClassResult {
        request.validate()
        return workspace.withDecompiler(context) { decompiler ->
            context.checkActive()
            val index = ClassIndex.Snapshot(decompiler.classesWithInners)
            val name = request.className
            if (name != null) {
                val cls = index.find(name) ?: missing("Class not found: $name")
                val methods = cls.methods.map { method ->
                    context.checkActive()
                    val info = method.methodNode.methodInfo
                    // shortId uses original JVM types, unlike display arguments changed by inference.
                    MethodInfo(method.name, method.fullName, info.shortId.substringAfter('(').let { "($it" },
                        info.returnType.toString(), info.argumentsTypes.map { it.toString() }, method.accessFlags.toString())
                }.sortedWith(compareBy({ it.name }, { it.descriptor }))
                val fields = cls.fields.map { FieldInfo(it.name, it.fullName, it.type.toString(), it.accessFlags.toString()) }
                    .sortedWith(compareBy({ it.name }, { it.type }))
                ClassResult(className = cls.rawName, methods = methods, fields = fields)
            } else {
                val catalog = ResourceCatalog(decompiler, context)
                val mainPackage = if (request.mainApp) catalog.manifestXml()
                    ?.let { ManifestModel.parse(it, catalog::boolean) }?.packageName else null
                if (request.mainApp && mainPackage == null) missing("Manifest package unavailable for mainApp filtering")
                val candidates = request.packageName?.let(index::inPackage) ?: index.classes
                val names = candidates.filter { cls ->
                    context.checkActive()
                    mainPackage == null || ClassIndex.matchesPackage(cls.classNode.classInfo.`package`, mainPackage) ||
                        ClassIndex.matchesPackage(cls.classNode.classInfo.aliasPkg, mainPackage)
                }.map { it.rawName }.sorted()
                val (items, info) = page(names, request.page)
                ClassResult(classes = items, page = info)
            }
        }
    }
}
