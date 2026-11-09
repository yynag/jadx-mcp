package com.yyang.jadxmcp.debug

import com.yyang.jadxmcp.protocol.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The ONLY version-sensitive/reflection boundary. Exact 1.5.6 names/types, no heuristic discovery.
 * Supply `{ window.debuggerPanel }` from the GUI host; the supplier runs on EDT.
 * Source evidence: JDebuggerPanel fields 73-84, ValueTreeNode 554-575;
 * IDebugController.isDebugging/isSuspended; DebugController.ThreadBoxElement 1252-1274.
 * Reads only currently materialized UI variables: no lazy expansion, JDWP calls, or evaluation.
 * Upstream workers also mutate these models, so this is best-effort, NOT an atomic VM snapshot.
 */
class Jadx156DebuggerFacade(private val panel: () -> Any?) : DebuggerFacade {
    override fun capture(context: CallContext): DebuggerModel {
        check(SwingUtilities.isEventDispatchThread()) { "Debugger models must be read on EDT" }
        context.checkActive()
        val instance = panel() ?: return DebuggerModel(DebuggerState.UI_NOT_READY)
        try {
            val api = Layout.verify(instance.javaClass)
            val controller = api.controller.get(instance) ?: return DebuggerModel(DebuggerState.UI_NOT_READY)
            fun state(): DebuggerState = when {
                !(api.attached.invoke(controller) as Boolean) -> DebuggerState.DISCONNECTED
                !(api.suspended.invoke(controller) as Boolean) -> DebuggerState.RUNNING
                else -> DebuggerState.PAUSED
            }
            val before = state()
            if (before != DebuggerState.PAUSED) return DebuggerModel(before)
            // A strict budget bounds EDT work independently of the transport's usually long deadline.
            val end = System.nanoTime() + TimeUnitMillis50
            var nodes = 0
            fun checkpoint() {
                context.checkActive()
                if (++nodes > 4096 || System.nanoTime() >= end) {
                    debugFailure(ErrorCode.BUSY, "Debugger model copy exceeds EDT budget", true)
                }
            }
            val combo = api.threads.get(instance) as? JComboBox<*>
                ?: return DebuggerModel(DebuggerState.UI_NOT_READY)
            val list = api.frames.get(instance) as? JList<*>
                ?: return DebuggerModel(DebuggerState.UI_NOT_READY)
            val selected = combo.selectedItem
            val threads = (0 until combo.itemCount).map { index ->
                checkpoint()
                val item = combo.getItemAt(index)
                require(api.threadType.isInstance(item)) { "Unexpected debugger thread element" }
                DebugThread(api.threadId.getLong(item).toString(), api.threadName.get(item) as? String ?: item.toString(), item === selected)
            }
            val frames = (0 until list.model.size).map { index ->
                checkpoint()
                val item = list.model.getElementAt(index)
                require(api.frameType.isInstance(item)) { "Unexpected debugger frame element" }
                // Display is upstream's materialized label; no decompiler resolution on EDT.
                DebugFrame(index, item.toString())
            }
            fun variables(root: DefaultMutableTreeNode, depth: Int = 0): List<DebugVariable> {
                if (depth > 16) debugFailure(ErrorCode.BUSY, "Debugger variable tree is too deep", true)
                return (0 until root.childCount).map { index ->
                    checkpoint()
                    val child = root.getChildAt(index)
                    require(api.valueType.isInstance(child)) { "Unexpected debugger variable node" }
                    DebugVariable(
                        api.name.invoke(child) as String,
                        api.value.invoke(child) as String?, api.type.invoke(child) as String?,
                        (api.typeId.invoke(child) as Long).toString(), api.updated.invoke(child) as Boolean,
                        variables(child as DefaultMutableTreeNode, depth + 1)
                    )
                }
            }
            val regs = api.registers.get(instance) as? DefaultMutableTreeNode
                ?: return DebuggerModel(DebuggerState.UI_NOT_READY)
            val thisObject = api.thisObject.get(instance) as? DefaultMutableTreeNode
                ?: return DebuggerModel(DebuggerState.UI_NOT_READY)
            val vars = VariablesResult(variables(regs), variables(thisObject))
            checkpoint()
            if (state() != before) debugFailure(ErrorCode.CONFLICT, "Debugger state changed during snapshot", true)
            return DebuggerModel(before, threads, frames, vars)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: ReflectiveOperationException) {
            debugFailure(ErrorCode.UNSUPPORTED, "JADX 1.5.6 debugger access is incompatible: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            debugFailure(ErrorCode.UNSUPPORTED, "JADX debugger private access is denied")
        } catch (e: java.lang.reflect.InaccessibleObjectException) {
            debugFailure(ErrorCode.UNSUPPORTED, "JADX debugger module is not open")
        } catch (e: IllegalArgumentException) {
            debugFailure(ErrorCode.UNSUPPORTED, "JADX debugger model type is incompatible: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            debugFailure(ErrorCode.BUSY, "Debugger models changed during snapshot", true)
        } catch (e: LinkageError) {
            debugFailure(ErrorCode.UNSUPPORTED, "JADX debugger classes are incompatible: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TimeUnitMillis50 = 50_000_000L

        /** Structural compatibility check, no GUI construction or device attachment required. */
        fun verifyCompatibility(panelType: Class<*>) { Layout.verify(panelType) }
    }

    private class Layout(panel: Class<*>) {
        private val loader = panel.classLoader
        private fun load(name: String) = Class.forName(name, false, loader)
        private fun field(owner: Class<*>, name: String, type: Class<*>): Field =
            owner.getDeclaredField(name).also {
                require(it.type == type) { "Changed field $name" }
                it.isAccessible = true
            }
        private fun method(owner: Class<*>, name: String, type: Class<*>): Method =
            owner.getMethod(name).also { require(it.returnType == type) { "Changed method $name" } }

        val controllerType = load("jadx.gui.ui.panel.IDebugController")
        val controller = field(panel, "controller", controllerType)
        val attached = method(controllerType, "isDebugging", java.lang.Boolean.TYPE)
        val suspended = method(controllerType, "isSuspended", java.lang.Boolean.TYPE)
        val threads = field(panel, "threadBox", JComboBox::class.java)
        val frames = field(panel, "stackFrameList", JList::class.java)
        val registers = field(panel, "regTreeNode", DefaultMutableTreeNode::class.java)
        val thisObject = field(panel, "thisTreeNode", DefaultMutableTreeNode::class.java)
        val threadType = load("jadx.gui.device.debugger.DebugController\$ThreadBoxElement")
        val threadId = field(threadType, "threadID", java.lang.Long.TYPE)
        val threadName = field(threadType, "name", String::class.java)
        val frameType = load("jadx.gui.device.debugger.DebugController\$FrameNode")
        val valueType = load("jadx.gui.ui.panel.JDebuggerPanel\$ValueTreeNode")
        val name = method(valueType, "getName", String::class.java)
        val value = method(valueType, "getValue", String::class.java)
        val type = method(valueType, "getType", String::class.java)
        val typeId = method(valueType, "getTypeID", java.lang.Long.TYPE)
        val updated = method(valueType, "isUpdated", java.lang.Boolean.TYPE)

        companion object {
            fun verify(panel: Class<*>): Layout {
                require(panel.name == "jadx.gui.ui.panel.JDebuggerPanel") { "Unsupported debugger panel ${panel.name}" }
                return Layout(panel)
            }
        }
    }
}
