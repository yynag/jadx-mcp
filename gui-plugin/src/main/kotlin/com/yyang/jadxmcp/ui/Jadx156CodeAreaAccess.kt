package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.*
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities

/**
 * Sole GUI refresh compatibility shim: CodeArea.java:428 exposes backgroundRefreshClass():void.
 * The published GUI compile artifact does not expose its RSyntaxTextArea superclass transitively;
 * isolate that missing compile-time hierarchy here, not in analysis/query/project algorithms.
 */
object Jadx156CodeAreaAccess {
    fun backgroundRefresh(area: Any) {
        check(SwingUtilities.isEventDispatchThread())
        if (generateSequence(area.javaClass as Class<*>?) { it.superclass }
                .none { it.name == "jadx.gui.ui.codearea.CodeArea" }) {
            guiFailure(ErrorCode.UNSUPPORTED, "JADX Java editor type is unsupported")
        }
        try {
            val method = area.javaClass.getMethod("backgroundRefreshClass")
            if (method.returnType != java.lang.Void.TYPE) guiFailure(ErrorCode.UNSUPPORTED, "JADX background refresh signature changed")
            method.invoke(area)
        } catch (e: InvocationTargetException) {
            throw (e.cause ?: e)
        } catch (e: ReflectiveOperationException) {
            guiFailure(ErrorCode.UNSUPPORTED, "JADX background refresh API is unavailable")
        } catch (e: SecurityException) {
            guiFailure(ErrorCode.UNSUPPORTED, "JADX background refresh access denied")
        }
    }
}
