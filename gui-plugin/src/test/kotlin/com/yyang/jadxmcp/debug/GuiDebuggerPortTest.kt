package com.yyang.jadxmcp.debug

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class GuiDebuggerPortTest {
    private fun context(epoch: Long = 3) = CallContext("debug-test", epoch, System.currentTimeMillis() + 5000)
    private val direct = object : UiExecutor {
        override fun <T> readOrEdit(context: CallContext, action: () -> T): T = action()
    }
    private fun paused() = DebuggerModel(
        DebuggerState.PAUSED,
        listOf(DebugThread("42", "main", true), DebugThread("84", "worker", false)),
        listOf(DebugFrame(0, "Example.main:12"), DebugFrame(1, "Launcher.run:2")),
        VariablesResult(
            listOf(DebugVariable("v0", "7", "int", "0", true)),
            listOf(DebugVariable("this", "@123", "Example", "123", children = listOf(DebugVariable("field", "hello", "String"))))
        )
    )
    private fun port(model: DebuggerModel) = GuiDebuggerPort(DebuggerFacade { model }, { 3 }, direct)
    private fun error(code: ErrorCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ProtocolException::class.java, action).error.code)
    }

    @Test fun copiesThreadsFramesRegistersAndNestedThisFields() {
        val expected = paused()
        val result = port(expected).snapshot(context())
        assertTrue(result.attached && result.suspended)
        assertEquals(expected.threads, result.threads)
        assertEquals(expected.frames, result.frames)
        assertEquals(expected.variables, result.variables)
        assertEquals("hello", result.variables.thisObject.single().children.single().value)
    }

    @Test fun statesHaveExplicitDistinctFailures() {
        val codes = mapOf(
            DebuggerState.UI_NOT_READY to ErrorCode.BUSY,
            DebuggerState.UNSUPPORTED to ErrorCode.UNSUPPORTED,
            DebuggerState.DISCONNECTED to ErrorCode.DISCONNECTED,
            DebuggerState.RUNNING to ErrorCode.CONFLICT
        )
        codes.forEach { (state, code) -> error(code) { port(DebuggerModel(state)).snapshot(context()) } }
    }

    @Test fun incompletePausedModelsAreNeverSuccessfulEmptyArrays() {
        listOf(
            DebuggerModel(DebuggerState.PAUSED),
            paused().copy(threads = emptyList()),
            paused().copy(threads = listOf(DebugThread("1", "main"))),
            paused().copy(frames = emptyList()),
            paused().copy(variables = VariablesResult())
        ).forEach { model -> error(ErrorCode.BUSY) { port(model).snapshot(context()) } }
    }

    @Test fun cancellationDeadlineAndEpochFenceBeforeAndAfterCapture() {
        var calls = 0
        val source = CancellationSource()
        val facade = DebuggerFacade { calls++; paused() }
        val port = GuiDebuggerPort(facade, { 3 }, direct)
        source.cancel()
        error(ErrorCode.CANCELLED) { port.snapshot(context().copy(cancellation = source)) }
        error(ErrorCode.DEADLINE_EXCEEDED) { port.snapshot(context().copy(deadlineEpochMillis = 1)) }
        error(ErrorCode.CONFLICT) { port.snapshot(context(4)) }
        assertEquals(0, calls)
        val lateCancel = CancellationSource()
        error(ErrorCode.CANCELLED) {
            GuiDebuggerPort(DebuggerFacade { lateCancel.cancel(); paused() }, { 3 }, direct)
                .snapshot(context().copy(cancellation = lateCancel))
        }
        var epoch = 3L
        error(ErrorCode.CONFLICT) {
            GuiDebuggerPort(DebuggerFacade { epoch++; paused() }, { epoch }, direct).snapshot(context())
        }
    }

    @Test fun defaultExecutorMarshalsReadsToEdtAndPropagatesTypedErrors() {
        val port = GuiDebuggerPort(DebuggerFacade {
            assertTrue(SwingUtilities.isEventDispatchThread())
            paused()
        }, { 3 })
        assertEquals("42", port.snapshot(context()).threads.first().id)
        error(ErrorCode.DISCONNECTED) {
            GuiDebuggerPort(DebuggerFacade { DebuggerModel(DebuggerState.DISCONNECTED) }, { 3 }).snapshot(context())
        }
        SwingUtilities.invokeAndWait { assertTrue(port.snapshot(context()).attached) }
    }

    @Test fun queuedCancelledOrExpiredActionsNeverReadGuiModels() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            SwingUtilities.invokeLater { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val call = context().copy(deadlineEpochMillis = System.currentTimeMillis() + 75)
            val result = caller.submit<ErrorCode> {
                try {
                    DebuggerEdtExecutor().readOrEdit(call) { fail<Unit>("Expired action executed") }
                    ErrorCode.INTERNAL
                } catch (e: ProtocolException) { e.error.code }
            }
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, result.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            caller.shutdownNow()
            SwingUtilities.invokeAndWait { }
        }
    }

    @Test fun versionFacadeRejectsForeignPanelAndRequiresEdt() {
        assertThrows(IllegalStateException::class.java) { Jadx156DebuggerFacade { null }.capture(context()) }
        SwingUtilities.invokeAndWait {
            assertEquals(DebuggerState.UI_NOT_READY, Jadx156DebuggerFacade { null }.capture(context()).state)
            error(ErrorCode.UNSUPPORTED) { Jadx156DebuggerFacade { Any() }.capture(context()) }
        }
    }
}
