package com.yyang.jadxmcp.analysis.reference

import com.yyang.jadxmcp.protocol.CallContext
import jadx.core.dex.attributes.AType
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.MethodNode

/** Metadata-only graph, rebuilt inside each lease so aliases/project mutations cannot stale it.
 * Match original name AND argument descriptors; never searchMethodByShortName (overloads).
 * Native override groups take precedence, including bridge/generic relationships. */
internal class OverrideIndex(classes: List<ClassNode>, private val context: CallContext) {
    private val parents = mutableMapOf<ClassNode, List<ClassNode>>()
    private val children = mutableMapOf<ClassNode, MutableList<ClassNode>>()

    init {
        val byName = classes.associateBy { it.rawName }
        for (cls in classes) {
            context.checkActive()
            val supers = (listOfNotNull(cls.superClass) + cls.interfaces).mapNotNull { byName[it.`object`] }
            parents[cls] = supers
            for (parent in supers) children.getOrPut(parent) { mutableListOf() } += cls
        }
    }

    fun related(target: MethodNode): Set<MethodNode> {
        val result = linkedSetOf(target)
        if (!virtual(target)) return result
        val queue = ArrayDeque<MethodNode>()
        queue.add(target)
        while (queue.isNotEmpty()) {
            context.checkActive()
            val method = queue.removeFirst()
            fun add(candidate: MethodNode) { if (result.add(candidate)) queue.add(candidate) }
            method.get(AType.METHOD_OVERRIDE)?.relatedMthNodes?.forEach(::add)
            // Erased generic bridges can have different argument descriptors. Link only
            // compiler-marked bridges with a verified usage edge, never name/arity guesses.
            for (caller in method.useIn) {
                context.checkActive()
                if (caller.accessFlags.isBridge && caller.name == method.name && method in caller.used) add(caller)
            }
            if (method.accessFlags.isBridge) for (used in method.used) {
                context.checkActive()
                if (used.name == method.name && virtual(used)) add(used)
            }
            val visited = hashSetOf<ClassNode>()
            val classes = ArrayDeque<ClassNode>()
            classes.add(method.declaringClass)
            while (classes.isNotEmpty()) {
                context.checkActive()
                val cls = classes.removeFirst()
                if (!visited.add(cls)) continue
                for (candidate in cls.methods) {
                    if (virtual(candidate) && sameArguments(candidate, method) && compatibleReturn(candidate, method) &&
                        visible(candidate, method.declaringClass) && visible(method, cls)) add(candidate)
                }
                // Ancestor traversal and descendant traversal must not cross into unrelated
                // siblings unless a real common overriding declaration connects the methods.
                if (cls == method.declaringClass) {
                    parents[cls]?.let(classes::addAll)
                    children[cls]?.let(classes::addAll)
                } else if (isAncestor(cls, method.declaringClass)) parents[cls]?.let(classes::addAll)
                else children[cls]?.let(classes::addAll)
            }
        }
        return result
    }

    private fun isAncestor(ancestor: ClassNode, child: ClassNode): Boolean {
        val queue = ArrayDeque<ClassNode>()
        queue.add(child)
        val seen = hashSetOf<ClassNode>()
        while (queue.isNotEmpty()) {
            context.checkActive()
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            if (next == ancestor) return true
            parents[next]?.let(queue::addAll)
        }
        return false
    }

    private fun virtual(method: MethodNode) = !method.accessFlags.isStatic && !method.accessFlags.isPrivate &&
        !method.methodInfo.isConstructor && !method.methodInfo.isClassInit
    private fun sameArguments(a: MethodNode, b: MethodNode) = a.name == b.name &&
        a.methodInfo.argumentsTypes == b.methodInfo.argumentsTypes
    private fun compatibleReturn(a: MethodNode, b: MethodNode): Boolean {
        val left = a.methodInfo.returnType
        val right = b.methodInfo.returnType
        if (left == right) return true
        if (!left.isObject || !right.isObject) return false
        if (left.`object` == "java.lang.Object" || right.`object` == "java.lang.Object") return true
        val first = a.root().resolveClass(left) ?: return false
        val second = a.root().resolveClass(right) ?: return false
        return isAncestor(first, second) || isAncestor(second, first)
    }
    private fun visible(method: MethodNode, cls: ClassNode) = !method.accessFlags.isPackagePrivate ||
        method.declaringClass.classInfo.`package` == cls.classInfo.`package`
}
