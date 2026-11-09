package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

class GuiContextAdapterTest {
    private fun context() = CallContext("gui-context", 3, System.currentTimeMillis() + 5000)
    @Test fun editorReadIsOnEdtButSourceGenerationIsNotAndSlimIsForwarded() {
        var received: DecompileRequest? = null
        val source = object : SourceService {
            override fun decompile(request: DecompileRequest, context: CallContext): SourceResult {
                assertFalse(SwingUtilities.isEventDispatchThread())
                received = request
                return SourceResult(request.className!!, text = TextContent("class A {}"))
            }
        }
        val facade = EditorContextFacade {
            assertTrue(SwingUtilities.isEventDispatchThread())
            EditorContext("raw.A", "selected")
        }
        val port = GuiContextAdapter(facade, GuiEdtExecutor { 3 }, source) { 3 }
        assertEquals("raw.A", port.currentClass(CurrentClassRequest("session", true), context()).className)
        assertTrue(received!!.slim)
        assertEquals(SelectionResult("selected", "raw.A"), port.selectedText(SelectedTextRequest("session"), context()))
    }

    @Test fun nonClassEmptySelectionAndEpochChangeAreNotMisreported() {
        var epoch = 3L
        val calls = AtomicInteger()
        val source = object : SourceService {
            override fun decompile(request: DecompileRequest, context: CallContext): SourceResult {
                calls.incrementAndGet(); epoch++
                return SourceResult("A")
            }
        }
        val empty = GuiContextAdapter(EditorContextFacade { EditorContext(null) }, GuiEdtExecutor { epoch }, source) { epoch }
        assertEquals("", empty.selectedText(SelectedTextRequest("s"), context()).text)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProtocolException::class.java) {
            empty.currentClass(CurrentClassRequest("s"), context())
        }.error.code)
        assertEquals(0, calls.get())
        val changed = GuiContextAdapter(EditorContextFacade { EditorContext("A") }, GuiEdtExecutor { epoch }, source) { epoch }
        assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
            changed.currentClass(CurrentClassRequest("s"), context())
        }.error.code)
    }

    @Test fun cancelledAndExpiredQueuedActionsDoNotReadUiAndErrorsPropagate() {
        val executor = GuiEdtExecutor { 3 }
        val calls = AtomicInteger()
        assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
            executor.readOrEdit(context().copy(cancellation = CancellationSignal { true })) { calls.incrementAndGet() }
        }.error.code)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(ProtocolException::class.java) {
            executor.readOrEdit(context().copy(deadlineEpochMillis = 1)) { calls.incrementAndGet() }
        }.error.code)
        assertEquals(0, calls.get())
        assertThrows(IllegalStateException::class.java) { executor.readOrEdit(context()) { error("model failure") } }
    }
}
