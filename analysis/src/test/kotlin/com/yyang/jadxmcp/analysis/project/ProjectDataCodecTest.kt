package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*
import jadx.api.data.impl.JadxCodeRef
import jadx.api.data.impl.JadxNodeRef
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectDataCodecTest {
    private val cls = NodeReference(NodeReferenceKind.CLASS, "sample.Raw")
    private val method = NodeReference(NodeReferenceKind.METHOD, "sample.Raw", "echo(I)I")

    @Test
    fun nativeRoundTripAllAttachmentKindsAndStyles() {
        val data = CodeData(
            CommentStyle.entries.mapIndexed { index, style -> CodeComment(method,
                CodeReference(CodeAttachmentKind.entries[index % 4], index), "comment-$index", style) } + CodeComment(cls, comment = "class"),
            listOf(CodeRename(cls, newName = "Renamed"),
                CodeRename(NodeReference(NodeReferenceKind.PKG, "sample"), newName = "other.pkg"),
                CodeRename(method, ProjectDataCodec.variable(7, 23), "variable")))
        assertEquals(ProjectDataCodec.canonical(data), ProjectDataCodec.fromNative(ProjectDataCodec.toNative(data)))
        assertNull(ProjectDataCodec.node(cls).shortId)
        assertEquals("", ProjectDataCodec.node(NodeReference(NodeReferenceKind.PKG, "sample")).shortId)
        assertEquals(ProjectDataCodec.node(JadxNodeRef.forCls("sample.Raw")), cls)
    }

    @Test
    fun variablePackingMatchesNativeSignedDecodeAndDoesNotAliasOutOfRangeValues() {
        val reference = ProjectDataCodec.variable(32767, 65535)
        assertEquals(JadxCodeRef.forVar(32767, 65535).index, reference.index)
        assertEquals(32767, reference.index shr 16)
        assertEquals(65535, reference.index and 65535)
        for ((reg, ssa) in listOf(-1 to 0, 32768 to 0, 0 to 65536, 0 to -1)) {
            assertThrows(ProtocolException::class.java) { ProjectDataCodec.variable(reg, ssa) }
        }
    }

    @Test
    fun duplicateAndMalformedDataAreRejectedBeforeNativeConversion() {
        val comment = CodeComment(cls, comment = "hello")
        val malformed = listOf(
            CodeData(comments = listOf(comment, comment)),
            CodeData(comments = listOf(comment.copy(comment = " "))),
            CodeData(comments = listOf(comment.copy(codeRef = CodeReference()))),
            CodeData(renames = listOf(CodeRename(cls, newName = "not valid"))),
            CodeData(renames = listOf(CodeRename(method, CodeReference(CodeAttachmentKind.INSN, 0), "bad"))),
            CodeData(renames = listOf(CodeRename(method.copy(shortId = "echo"), newName = "bad")))
        )
        malformed.forEach { data ->
            assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
                ProjectDataCodec.toNative(data)
            }.error.code)
        }
        val mutable = mutableListOf(comment)
        val detached = ProjectDataCodec.canonical(CodeData(comments = mutable))
        mutable.clear()
        assertEquals(1, detached.comments.size)
        assertThrows(UnsupportedOperationException::class.java) { (detached.comments as MutableList).clear() }
    }
}
