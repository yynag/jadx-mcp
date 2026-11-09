package com.yyang.jadxmcp.analysis.search

import com.yyang.jadxmcp.protocol.*
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.core.dex.nodes.ClassNode
import jadx.core.dex.nodes.FieldNode
import jadx.core.dex.nodes.MethodNode

/** Use raw identities in results; aliases are presentation/search terms, never cache keys. */
internal fun symbol(node: ClassNode) = SymbolRef(node.rawName)
internal fun symbol(node: MethodNode) = SymbolRef(node.declaringClass.rawName,
    node.methodInfo.name, node.methodInfo.shortId.substringAfter(node.methodInfo.name))
internal fun symbol(node: FieldNode) = SymbolRef(node.declaringClass.rawName,
    node.fieldInfo.name, node.fieldInfo.shortId.substringAfter(':'))
internal fun symbol(node: JavaNode): SymbolRef = when (node) {
    is JavaMethod -> symbol(node.methodNode)
    is JavaField -> symbol(node.fieldNode)
    is JavaClass -> symbol(node.classNode)
    else -> SymbolRef(node.fullName)
}

internal fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(ProtocolError(code, message))

/** Unknown totals are intentional: a budget-limited prefix is not the whole result set. */
internal fun <T> resultPage(items: List<T>, page: PageRequest, complete: Boolean = true): Pair<List<T>, PageInfo> {
    val start = page.offset.coerceAtMost(items.size)
    val end = (start.toLong() + page.count).coerceAtMost(items.size.toLong()).toInt()
    return items.subList(start, end).toList() to PageInfo(page.offset, end - start,
        items.size.takeIf { complete }, end.takeIf { it < items.size })
}
