package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuiProjectEditAdapterTest {
    private fun context() = CallContext("gui-edit", 2, System.currentTimeMillis() + 5000)
    @Test fun nativeRenameReceiptsPreserveAppliedVersusPersistedAndVariableIdentity() {
        val state = MutationState(true, false, "native-confirmed", null)
        val symbol = SymbolRef("raw.A", "work", "()V")
        var called = 0
        val native = object : GuiNativeRenameFacade {
            override fun rename(request: RenameRequest, context: CallContext) =
                NativeRenameReceipt("old", request.newName, state, request.target).also { called++ }
            override fun renamePackage(request: RenamePackageRequest, context: CallContext) =
                NativeRenameReceipt(request.oldPackageName, request.newPackageName, state, renamed = 3).also { called++ }
            override fun renameVariable(request: RenameVariableRequest, context: CallContext) =
                NativeRenameReceipt(request.variableName, request.newName, state, symbol, reg = 4, ssa = 5).also { called++ }
        }
        var commentCalls = 0
        val comments = object : ProjectEditPort {
            override fun rename(request: RenameRequest, context: CallContext): RenameResult = error("must be native")
            override fun renamePackage(request: RenamePackageRequest, context: CallContext): RenamePackageResult = error("must be native")
            override fun renameVariable(request: RenameVariableRequest, context: CallContext): RenameVariableResult = error("must be native")
            override fun comment(request: AddCommentRequest, context: CallContext) = CommentMutationResult(state = state).also { commentCalls++ }
            override fun comments(request: ListCommentsRequest, context: CallContext) = CommentsResult().also { commentCalls++ }
        }
        val port = GuiProjectEditAdapter(native, comments) { 2 }
        SymbolKind.entries.forEach { kind ->
            val result = port.rename(RenameRequest("s", kind, symbol, "renamed"), context())
            assertTrue(result.state.applied); assertFalse(result.state.persisted); assertNull(result.state.projectPath)
        }
        assertEquals(3, port.renamePackage(RenamePackageRequest("s", "raw", "newpkg"), context()).renamed)
        val variable = port.renameVariable(RenameVariableRequest("s", symbol, "v", "n", 4, 5), context())
        assertEquals(4, variable.reg); assertEquals(5, variable.ssa); assertEquals(symbol, variable.method)
        assertEquals(state, port.comment(AddCommentRequest("s", "raw.A", "hello"), context()).state)
        port.comments(ListCommentsRequest("s"), context())
        assertEquals(5, called); assertEquals(2, commentCalls)
        assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
            port.rename(RenameRequest("s", target = SymbolRef("raw.A"), newName = "x"), context().copy(projectEpoch = 3))
        }.error.code)
        assertEquals(5, called)
    }
}
