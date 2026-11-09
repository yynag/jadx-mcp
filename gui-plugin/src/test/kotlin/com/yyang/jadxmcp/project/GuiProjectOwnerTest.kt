package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.GuiEdtExecutor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class GuiProjectOwnerTest {
    private fun context() = CallContext("gui-owner", 7, System.currentTimeMillis() + 5000)
    private val input = listOf(InputFingerprint("a".repeat(64), 123))
    private val comment = CodeComment(NodeReference(declaringClass = "sample.A"), comment = "hello")
    private val data = CodeData(comments = listOf(comment))

    /** Fake revision policy isolates owner behavior; the concrete codec has separate integration tests. */
    private val codec = object : GuiProjectSnapshotCodec {
        override fun build(inputs: List<InputFingerprint>, data: CodeData) = ProjectDataSnapshot(inputs, "fake-${data.hashCode()}", data)
        override fun validateImport(incoming: ProjectDataSnapshot, current: ProjectDataSnapshot,
                                    expectedRevision: String, context: CallContext): CodeData = incoming.data
        override fun validateReplacement(data: CodeData, context: CallContext) = data
    }
    private class FakeAccess(var policy: NativeSavePolicy = NativeSavePolicy.ASK, var path: String? = null) : GuiProjectAccess {
        var identity = Any()
        var data = CodeData()
        var writes = 0
        var saveFailures = false
        var onWrite: () -> Unit = {}
        override fun read(): GuiProjectView {
            assertTrue(SwingUtilities.isEventDispatchThread())
            return GuiProjectView(identity, data, path, policy)
        }
        override fun replace(expectedIdentity: Any, data: CodeData): NativeApplyResult {
            assertTrue(SwingUtilities.isEventDispatchThread())
            assertSame(identity, expectedIdentity)
            writes++; this.data = data; onWrite()
            return NativeApplyResult(true, policy == NativeSavePolicy.ALWAYS && path != null && !saveFailures)
        }
    }
    private fun owner(access: FakeAccess, codec: GuiProjectSnapshotCodec = this.codec,
                      invalidated: (CallContext) -> Unit = {}) = GuiProjectDataOwner(access, codec, { input }, GuiEdtExecutor { 7 }, { 7 }, invalidated)
    private fun error(code: ErrorCode, action: () -> Unit) =
        assertEquals(code, assertThrows(ProtocolException::class.java, action).error.code)

    @Test fun unsavedAskProjectAppliesOnlyInMemoryAndInvalidatesOffEdt() {
        val host = FakeAccess()
        val calls = AtomicInteger()
        val owner = owner(host) { assertFalse(SwingUtilities.isEventDispatchThread()); calls.incrementAndGet(); Unit }
        val before = owner.snapshot(context())
        val result = owner.importData(ProjectDataSnapshot(input, "origin", data), before.revision, context())
        assertTrue(result.applied); assertFalse(result.persisted); assertNull(result.projectPath)
        assertEquals(data, owner.snapshot(context()).data)
        assertEquals(1, calls.get())
        assertEquals(1, host.writes)
    }

    @Test fun savedAskStillDoesNotForceSaveButAlwaysReportsConfirmedSave() {
        listOf(NativeSavePolicy.ASK, NativeSavePolicy.ALWAYS, NativeSavePolicy.NEVER).forEach { policy ->
            val host = FakeAccess(policy, "selected-project.jadx")
            val owner = owner(host)
            val before = owner.snapshot(context())
            val result = owner.importData(ProjectDataSnapshot(input, "origin", data), before.revision, context())
            assertEquals(policy == NativeSavePolicy.ALWAYS, result.persisted)
            assertEquals("selected-project.jadx", result.projectPath)
        }
        val unsavedAlways = owner(FakeAccess(NativeSavePolicy.ALWAYS))
        assertFalse(unsavedAlways.importData(ProjectDataSnapshot(input, "origin", data), unsavedAlways.snapshot(context()).revision, context()).persisted)
        val failedSave = owner(FakeAccess(NativeSavePolicy.ALWAYS, "selected.jadx").apply { saveFailures = true })
        val result = failedSave.importData(ProjectDataSnapshot(input, "origin", data), failedSave.snapshot(context()).revision, context())
        assertTrue(result.applied); assertFalse(result.persisted)
    }

    @Test fun staleRevisionDifferentInputsAndCancellationDoNotWrite() {
        val host = FakeAccess()
        val owner = owner(host)
        val before = owner.snapshot(context())
        error(ErrorCode.CONFLICT) { owner.importData(ProjectDataSnapshot(input, "origin", data), "stale", context()) }
        error(ErrorCode.CONFLICT) {
            owner.importData(ProjectDataSnapshot(listOf(InputFingerprint("b".repeat(64), 123)), "origin", data), before.revision, context())
        }
        error(ErrorCode.CANCELLED) {
            owner.importData(ProjectDataSnapshot(input, "origin", data), before.revision,
                context().copy(cancellation = CancellationSignal { true }))
        }
        error(ErrorCode.DEADLINE_EXCEEDED) { owner.snapshot(context().copy(deadlineEpochMillis = 1)) }
        assertEquals(0, host.writes)
    }

    @Test fun hostChangeBetweenPlanningAndCommitFailsCasWithoutOverwritingUserData() {
        val host = FakeAccess()
        val policy = object : GuiProjectSnapshotCodec by codec {
            override fun validateImport(incoming: ProjectDataSnapshot, current: ProjectDataSnapshot,
                                        expectedRevision: String, context: CallContext): CodeData {
                SwingUtilities.invokeAndWait { host.identity = Any() }
                return incoming.data
            }
        }
        val owner = owner(host, policy)
        val before = owner.snapshot(context())
        error(ErrorCode.CONFLICT) { owner.importData(ProjectDataSnapshot(input, "origin", data), before.revision, context()) }
        assertEquals(0, host.writes)
    }

    @Test fun cancelAfterMemoryApplyStillSchedulesGenerationRepair() {
        val source = CancellationSource()
        val repair = CountDownLatch(1)
        val host = FakeAccess().apply { onWrite = { source.cancel() } }
        val owner = owner(host) { assertFalse(SwingUtilities.isEventDispatchThread()); repair.countDown() }
        val revision = owner.snapshot(context()).revision
        error(ErrorCode.CANCELLED) {
            owner.importData(ProjectDataSnapshot(input, "origin", data), revision, context().copy(cancellation = source))
        }
        assertTrue(repair.await(2, TimeUnit.SECONDS))
        assertEquals(data, host.data)
    }

    @Test fun guiProjectEpochAndWorkerOnlyBoundaryAreEnforced() {
        val owner = owner(FakeAccess())
        error(ErrorCode.CONFLICT) { owner.snapshot(context().copy(projectEpoch = 8)) }
        SwingUtilities.invokeAndWait { error(ErrorCode.BUSY) { owner.snapshot(context()) } }
    }
}
