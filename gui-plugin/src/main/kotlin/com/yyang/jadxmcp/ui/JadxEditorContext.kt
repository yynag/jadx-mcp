package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.protocol.ErrorCode
import jadx.api.JavaClass
import jadx.gui.ui.MainWindow
import jadx.gui.ui.codearea.AbstractCodeContentPanel
import jadx.gui.ui.codearea.ClassCodeContentPanel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/** 1.5.6 public GUI types only: ClassCodeContentPanel chooses Java versus Smali explicitly. */
class JadxEditorContext(private val window: MainWindow) : EditorContextFacade {
    override fun read(): EditorContext {
        check(SwingUtilities.isEventDispatchThread())
        val panel = window.tabbedPane.selectedContentPanel
            ?: guiFailure(ErrorCode.NOT_FOUND, "No editor tab is active")
        val area: Any = when (panel) {
            is ClassCodeContentPanel -> panel.currentCodeArea
            is AbstractCodeContentPanel -> panel.codeArea
            else -> null
        } ?: guiFailure(ErrorCode.UNSUPPORTED, "Active tab is not a text editor")
        val text = area as? JTextComponent ?: guiFailure(ErrorCode.UNSUPPORTED, "Editor is not a Swing text component")
        return EditorContext((panel.node.javaNode as? JavaClass)?.rawName, text.selectedText.orEmpty())
    }
}
