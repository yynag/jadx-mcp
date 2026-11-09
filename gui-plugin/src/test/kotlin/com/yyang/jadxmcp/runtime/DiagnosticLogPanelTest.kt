package com.yyang.jadxmcp.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

class DiagnosticLogPanelTest {
    @Test fun buttonReportsActualStateInsteadOfOptimisticallyClaimingEnable() {
        var enabled = false
        var notice = "Diagnostic log: OFF"
        var accept = false
        var completion: (() -> Unit)? = null
        lateinit var panel: DiagnosticLogPanel
        SwingUtilities.invokeAndWait {
            panel = DiagnosticLogPanel({ enabled to notice }) { value, done ->
                if (accept) { enabled = value; notice = if (value) "Diagnostic log: ON" else "Diagnostic log: OFF" }
                else notice = "Diagnostic log: OFF; enable failed (IOException)"
                completion = done
            }
            val button = panel.components.filterIsInstance<JButton>().single()
            assertEquals("Enable diagnostic log", button.text)
            button.doClick()
            assertFalse(button.isEnabled)
            completion!!()
        }
        SwingUtilities.invokeAndWait {
            val button = panel.components.filterIsInstance<JButton>().single()
            assertEquals("Enable diagnostic log", button.text)
            assertTrue(panel.components.filterIsInstance<JLabel>().single().text.contains("failed"))
            accept = true
            button.doClick(); completion!!()
        }
        SwingUtilities.invokeAndWait {
            val button = panel.components.filterIsInstance<JButton>().single()
            assertEquals("Disable diagnostic log", button.text)
            button.doClick(); completion!!()
        }
        SwingUtilities.invokeAndWait {
            assertEquals("Enable diagnostic log", panel.components.filterIsInstance<JButton>().single().text)
            assertFalse(enabled)
        }
    }
}
