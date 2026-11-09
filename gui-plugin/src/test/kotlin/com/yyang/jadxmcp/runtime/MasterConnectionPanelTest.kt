package com.yyang.jadxmcp.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class MasterConnectionPanelTest {
    @Test fun unsupportedSavedOriginNoticeIsVisibleWithoutDisplayingAUrl() {
        SwingUtilities.invokeAndWait {
            val panel = MasterConnectionPanel(MasterConnectionSettings.of("127.0.0.1", "8650"), "Enter IP and port again") { _, _ -> fail("Must not apply automatically") }
            assertEquals("127.0.0.1", panel.ip.text)
            assertEquals("8650", panel.port.text)
            assertEquals("Enter IP and port again", panel.message.text)
            panel.ip.text = "localhost"; panel.apply.doClick()
            assertEquals("Enter a valid IPv4 or IPv6 address", panel.message.text)
        }
    }
    @Test fun applyValidatesAndAcknowledgesSuccessWithoutACancelButton() {
        lateinit var panel: MasterConnectionPanel
        var callback: ((Boolean) -> Unit)? = null
        var selected: MasterConnectionSettings? = null
        SwingUtilities.invokeAndWait {
            panel = MasterConnectionPanel(MasterConnectionSettings.of("127.0.0.1", "8650")) { value, done -> selected = value; callback = done }
            panel.ip.text = "bad://host"; panel.apply.doClick()
            assertNull(selected); assertTrue(panel.apply.isEnabled)
            panel.ip.text = "::1"; panel.port.text = "18650"; panel.apply.doClick()
            assertEquals("http://[::1]:18650", selected!!.baseUrl)
            assertFalse(panel.apply.isEnabled)
        }
        callback!!(true)
        SwingUtilities.invokeAndWait {
            assertTrue(panel.apply.isEnabled)
            assertEquals("::1", panel.ip.text); assertEquals("18650", panel.port.text)
            assertEquals("Applied!", panel.message.text)
            fun buttons(container: java.awt.Container): List<javax.swing.JButton> = container.components.flatMap {
                when (it) {
                    is javax.swing.JButton -> listOf(it)
                    is java.awt.Container -> buttons(it)
                    else -> emptyList()
                }
            }
            assertEquals(listOf("Apply"), buttons(panel).map { it.text })
        }
    }

    @Test fun failedApplyDoesNotClaimSuccessAndReenablesControls() {
        lateinit var panel: MasterConnectionPanel
        var callback: ((Boolean) -> Unit)? = null
        SwingUtilities.invokeAndWait {
            panel = MasterConnectionPanel(MasterConnectionSettings.of("127.0.0.1", "8650")) { _, done -> callback = done }
            panel.port.text = "18650"; panel.apply.doClick()
        }
        callback!!(false)
        SwingUtilities.invokeAndWait {
            assertTrue(panel.apply.isEnabled)
            assertEquals("Connection settings failed", panel.message.text)
            assertEquals("18650", panel.port.text)
        }
    }
}
