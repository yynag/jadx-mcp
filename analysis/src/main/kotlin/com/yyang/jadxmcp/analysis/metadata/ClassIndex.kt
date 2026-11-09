package com.yyang.jadxmcp.analysis.metadata

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import jadx.api.JavaClass

/** Rebuild inside a workspace lease: aliases can change without a project epoch change. */
class ClassIndex(private val workspace: WorkspaceAccess<JadxDecompiler>) {
    fun <T> read(context: CallContext, action: (Snapshot) -> T): T =
        workspace.withDecompiler(context) { decompiler ->
            context.checkActive()
            action(Snapshot(decompiler.classesWithInners))
        }

    class Snapshot(classes: List<JavaClass>) {
        val classes: List<JavaClass> = classes.distinctBy { it.rawName }.sortedBy { it.rawName }

        fun find(name: String): JavaClass? {
            // A real p000/defpackage package must win before default-package spelling expansion.
            classes.firstOrNull { it.rawName == name.trim() }?.let { return it }
            val candidates = linkedSetOf(name.trim())
            val bare = name.trim().removePrefix("p000.").removePrefix("defpackage.")
            if (bare != name.trim() || '.' !in bare) {
                candidates += bare
                candidates += "p000.$bare"
                candidates += "defpackage.$bare"
            }
            // Exact raw identity wins over an alias belonging to a different class.
            classes.firstOrNull { it.rawName in candidates }?.let { return it }
            val hits = classes.filter { cls ->
                val info = cls.classNode.classInfo
                listOf(cls.fullName, info.fullName, info.aliasFullName, info.makeAliasRawFullName())
                    .any { it in candidates }
            }
            if (hits.size > 1) throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "Ambiguous class: $name"))
            return hits.singleOrNull()
        }

        fun inPackage(packageName: String): List<JavaClass> {
            val pkg = packageName.trim().trimEnd('.')
            return classes.filter {
                val info = it.classNode.classInfo
                matchesPackage(info.`package`, pkg) || matchesPackage(info.aliasPkg, pkg)
            }
        }
    }

    companion object {
        fun matchesPackage(actual: String, requested: String): Boolean =
            actual == requested || (requested.isNotEmpty() && actual.startsWith("$requested."))
    }
}

internal fun <T> page(items: List<T>, request: PageRequest): Pair<List<T>, PageInfo> {
    request.validate()
    val start = request.offset.coerceAtMost(items.size)
    val end = (start.toLong() + request.count).coerceAtMost(items.size.toLong()).toInt()
    return items.subList(start, end) to PageInfo(request.offset, end - start, items.size,
        end.takeIf { it < items.size })
}

internal fun missing(message: String): Nothing =
    throw ProtocolException(ProtocolError(ErrorCode.NOT_FOUND, message))
