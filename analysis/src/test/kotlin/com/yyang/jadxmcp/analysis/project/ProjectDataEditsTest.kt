package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectDataEditsTest {
    private val ref = NodeReference(NodeReferenceKind.CLASS, "sample.Raw")
    private val other = ref.copy(declaringClass = "sample.Other")
    private fun data(text: String) = CodeData(comments = listOf(CodeComment(ref, comment = text)))

    @Test
    fun commentUpsertAndDeletionAreIdempotentAndIdentityDoesNotIncludeTextOrStyle() {
        val (added, add) = ProjectDataEdits.comment(CodeData(), CodeComment(ref, comment = "first"))
        assertEquals(CommentAction.ADDED, add)
        val (updated, update) = ProjectDataEdits.comment(added, CodeComment(ref, comment = "second", style = CommentStyle.BLOCK))
        assertEquals(CommentAction.UPDATED, update)
        assertEquals(1, updated.comments.size)
        assertEquals(CommentStyle.BLOCK, updated.comments.single().style)
        val (removed, remove) = ProjectDataEdits.comment(updated, CodeComment(ref, comment = ""))
        assertEquals(CommentAction.REMOVED, remove)
        assertTrue(removed.comments.isEmpty())
        assertEquals(removed, ProjectDataEdits.comment(removed, CodeComment(ref, comment = "")).first)
    }

    @Test
    fun additionsNeverEraseAbsentKeysOrOverwriteConflictingValues() {
        assertEquals(data("local"), ProjectDataEdits.mergeAdditions(data("local"), CodeData()))
        assertEquals(data("same"), ProjectDataEdits.mergeAdditions(data("same"), data("same")))
        assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
            ProjectDataEdits.mergeAdditions(data("local"), data("incoming"))
        }.error.code)
    }

    @Test
    fun threeWayCombinesIndependentEditsAndSupportsDeletion() {
        val base = data("base")
        val local = base.copy(renames = listOf(CodeRename(other, newName = "OtherName")))
        val incoming = data("incoming")
        val merged = ProjectDataEdits.merge(base, local, incoming)
        assertEquals("incoming", merged.comments.single().comment)
        assertEquals(local.renames, merged.renames)
        assertTrue(ProjectDataEdits.merge(base, base, CodeData()).comments.isEmpty())
        assertTrue(ProjectDataEdits.merge(base, CodeData(), CodeData()).comments.isEmpty())
    }

    @Test
    fun threeWayDetectsEditEditEditDeleteAndRenameConflicts() {
        assertThrows(ProtocolException::class.java) { ProjectDataEdits.merge(data("base"), data("local"), data("incoming")) }
        assertThrows(ProtocolException::class.java) { ProjectDataEdits.merge(data("base"), data("local"), CodeData()) }
        val base = CodeData(renames = listOf(CodeRename(ref, newName = "Base")))
        val left = ProjectDataEdits.rename(base, CodeRename(ref, newName = "Left"))
        val right = ProjectDataEdits.rename(base, CodeRename(ref, newName = "Right"))
        assertThrows(ProtocolException::class.java) { ProjectDataEdits.merge(base, left, right) }
    }
}
