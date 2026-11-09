package com.yyang.jadxmcp.analysis.search

import com.yyang.jadxmcp.protocol.*
import jadx.api.plugins.input.data.attributes.JadxAttrType
import jadx.api.plugins.input.insns.InsnData
import jadx.api.plugins.input.insns.InsnIndexType
import jadx.core.dex.nodes.ClassNode

internal object DexConstants {
    /** Input API has no format enum. Accept only the verified JADX 1.5.6 DEX reader,
     * not filenames (a JAR may be named .dex) or Java STRING_REF instructions. */
    fun supports(cls: ClassNode): Boolean =
        cls.clsData?.javaClass?.name == "jadx.plugins.input.dex.sections.DexClassData"

    fun scan(cls: ClassNode, context: CallContext, emit: (SearchHit) -> Unit) {
        for (field in cls.fields.sortedBy { it.fieldInfo.shortId }) {
            context.checkActive()
            val value = field.get(JadxAttrType.CONSTANT_VALUE)?.value as? String ?: continue
            emit(SearchHit(symbol(field), value, value.take(240)))
        }
        for (method in cls.methods.sortedBy { it.methodInfo.shortId }) {
            context.checkActive()
            if (method.isNoCode) continue
            method.codeReader?.visitInstructions { insn ->
                context.checkActive()
                stringValue(insn)?.let { emit(SearchHit(symbol(method), it, it.take(240))) }
            }
        }
    }

    /** Decode before reading the full string; previews must never be used for matching. */
    fun stringValue(insn: InsnData): String? {
        if (insn.indexType != InsnIndexType.STRING_REF) return null
        insn.decode()
        return insn.indexAsString
    }
}
