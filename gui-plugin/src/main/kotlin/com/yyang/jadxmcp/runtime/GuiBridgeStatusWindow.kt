package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.bridge.BridgeStatusSnapshot
import java.awt.Dialog
import java.awt.Frame
import java.awt.GridLayout
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Owns one non-modal dialog and its sampling timer, never the host frame or a bridge worker.
 * All Swing state below is EDT-confined; [closed] only fences unload from another thread.
 * Closing the dialog stops its timer; unloading also fences queued menu-open callbacks. Each
 * timer belongs to its specific dialog, so a late close event cannot stop a reopened window.
 */
internal class GuiBridgeStatusWindow(
    private val owner: Frame,
    private val snapshot: () -> BridgeStatusSnapshot,
    private val currentSettings: () -> MasterConnectionSettings,
    private val applySettings: (MasterConnectionSettings, (ConnectionSettingsApplyResult) -> Unit) -> Unit,
    private val reconnect: () -> Unit,
    private val settingsNotice: () -> String? = { null },
    private val diagnosticState: () -> Pair<Boolean, String> = { false to "Diagnostic log: OFF" },
    private val toggleDiagnostics: (Boolean, () -> Unit) -> Unit = { _, done -> done() },
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var window: JDialog? = null
    private var timer: Timer? = null

    fun open() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed.get()) return
        window?.let {
            it.isVisible = true
            it.toFront()
            return
        }
        val panel = BridgeStatusPanel(reconnect)
        val dialog = JDialog(owner, TITLE, Dialog.ModalityType.MODELESS)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        val content = JPanel(BorderLayout(8, 8))
        content.add(panel, BorderLayout.NORTH)
        val configuration = MasterConnectionPanel(currentSettings(), settingsNotice(), applySettings)
        content.add(configuration, BorderLayout.CENTER)
        val diagnosticPanel = DiagnosticLogPanel(diagnosticState, toggleDiagnostics)
        content.add(diagnosticPanel, BorderLayout.SOUTH)
        dialog.contentPane = content
        val refresh = Timer(1_000) {
            panel.render(snapshot())
            configuration.reportNotice(settingsNotice())
            diagnosticPanel.render()
        }
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                refresh.stop()
                if (window === dialog) {
                    window = null
                    timer = null
                }
            }
        })
        panel.render(snapshot())
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        window = dialog
        timer = refresh
        refresh.start()
        dialog.isVisible = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        SwingUtilities.invokeLater {
            timer?.stop()
            timer = null
            window?.dispose()
            window = null
        }
    }

    companion object { const val TITLE = "Jadx MCP" }
}

/** Only the setting callback performs I/O, on the plugin's existing worker. */
internal class DiagnosticLogPanel(
    private val state: () -> Pair<Boolean, String>,
    private val toggle: (Boolean, () -> Unit) -> Unit,
) : JPanel(GridLayout(0, 1, 4, 6)) {
    private val status = JLabel()
    private var pending = false
    private val button = JButton().apply {
        addActionListener {
            pending = true
            isEnabled = false
            toggle(!state().first) {
                SwingUtilities.invokeLater { pending = false; render() }
            }
        }
    }
    init { border = BorderFactory.createEmptyBorder(8, 16, 12, 16); add(status); add(button); render() }
    fun render() {
        val (enabled, notice) = state()
        status.text = notice
        button.text = if (enabled) "Disable diagnostic log" else "Enable diagnostic log"
        button.isEnabled = !pending
    }
}

/** Rendering is deliberately pure metadata; sample on EDT, perform no I/O or waits. */
internal class BridgeStatusPanel(private val reconnect: () -> Unit = {}) : JPanel(GridLayout(0, 1, 4, 6)) {
    private val connection = JLabel()
    private val counts = JLabel()
    private val reconnectButton = JButton("Reconnect").apply { addActionListener { reconnect() } }
    init {
        border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        add(connection); add(counts); add(reconnectButton)
        toolTipText = "Unique business commands received; excludes poll / heartbeat / registration. " +
            "Windows reset on reconnect / project change; idle windows return to zero."
    }

    fun render(value: BridgeStatusSnapshot) {
        check(SwingUtilities.isEventDispatchThread())
        connection.text = "${value.state}: ${value.reason}"
        counts.text = "Requests — 5s: ${value.requests5s}   10s: ${value.requests10s}   30s: ${value.requests30s}"
        reconnectButton.isEnabled = value.state == com.yyang.jadxmcp.bridge.BridgeState.DISABLED &&
            value.reason.startsWith("Error:")
    }
}
