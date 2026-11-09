package com.yyang.jadxmcp.runtime

import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Owns only the editable IP/port form. The plugin owns persistence and bridge replacement.
 * Validation is local/EDT-safe; [applySettings] must enqueue work and return without waiting.
 * Its callback may run on a worker, so acknowledgement is always marshalled back to the EDT.
 * "Applied!" means settings were saved and handed to the bridge, not that a remote server replied.
 */
internal class MasterConnectionPanel(
    current: MasterConnectionSettings,
    notice: String? = null,
    private val applySettings: (MasterConnectionSettings, (ConnectionSettingsApplyResult) -> Unit) -> Unit,
) : JPanel(BorderLayout(8, 8)) {
    internal val ip = JTextField(current.ip, 24)
    internal val port = JTextField(current.port.toString(), 6)
    internal val apply = JButton("Apply")
    internal val message = JLabel(notice ?: " ")

    init {
        border = BorderFactory.createEmptyBorder(0, 16, 12, 16)
        val fields = JPanel(FlowLayout(FlowLayout.LEFT))
        fields.add(JLabel("IP:"))
        fields.add(ip)
        fields.add(JLabel("Port:"))
        fields.add(port)
        add(fields, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttons.add(apply)
        val footer = JPanel(BorderLayout())
        footer.add(message, BorderLayout.CENTER)
        footer.add(buttons, BorderLayout.SOUTH)
        add(footer, BorderLayout.SOUTH)
        apply.addActionListener { applyInput() }
    }

    private fun applyInput() {
        val selected = try {
            MasterConnectionSettings.of(ip.text, port.text)
        } catch (failure: IllegalArgumentException) {
            message.text = failure.message
            return
        }
        setInputEnabled(false)
        message.text = "Applying…"
        applySettings(selected) { result ->
            SwingUtilities.invokeLater {
                if (result.applied) {
                    ip.text = selected.ip
                    port.text = selected.port.toString()
                }
                message.text = result.message
                setInputEnabled(true)
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        apply.isEnabled = enabled
        ip.isEnabled = enabled
        port.isEnabled = enabled
    }

    /** A saved-origin load can finish after the window opens. Do not overwrite user feedback. */
    fun reportNotice(value: String?) {
        if (value != null && message.text.isBlank()) message.text = value
    }
}
