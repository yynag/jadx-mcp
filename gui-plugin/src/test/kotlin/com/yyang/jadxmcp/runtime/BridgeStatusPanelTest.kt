package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.bridge.BridgeState
import com.yyang.jadxmcp.bridge.BridgeStatusSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import javax.swing.JButton
import javax.swing.SwingUtilities

class BridgeStatusPanelTest {
    @Test fun compactPanelRefreshesCountersAndStateOnEdtOnly() {
        val sample = BridgeStatusSnapshot(BridgeState.CONNECTED, "Connected", 2, 3, 1, 2, 3)
        val panel = BridgeStatusPanel()
        assertThrows(IllegalStateException::class.java) { panel.render(sample) }
        SwingUtilities.invokeAndWait {
            panel.render(sample)
            assertEquals(3, panel.componentCount)
            assertEquals("Requests — 5s: 1   10s: 2   30s: 3", (panel.getComponent(1) as JLabel).text)
            panel.render(sample.copy(state = BridgeState.RECONNECTING, reason = "Retry in 250ms", requests5s = 0))
            assertTrue((panel.getComponent(0) as JLabel).text.contains("RECONNECTING"))
            assertTrue((panel.getComponent(1) as JLabel).text.contains("5s: 0"))
        }
    }

    @Test fun reconnectIsOnlyEnabledForTerminalErrorAndDispatchesExplicitAction() {
        var clicks = 0
        val panel = BridgeStatusPanel { clicks++ }
        SwingUtilities.invokeAndWait {
            val button = panel.getComponent(2) as JButton
            panel.render(BridgeStatusSnapshot(BridgeState.CONNECTED, "Connected", 0, 1, 0, 0, 0))
            assertFalse(button.isEnabled)
            panel.render(BridgeStatusSnapshot(BridgeState.DISABLED, "Waiting for project", 0, 1, 0, 0, 0))
            assertFalse(button.isEnabled)
            panel.render(BridgeStatusSnapshot(BridgeState.DISABLED, "Actively closed by master", 0, 1, 0, 0, 0))
            assertFalse(button.isEnabled)
            panel.render(BridgeStatusSnapshot(BridgeState.DISABLED, "Error: INVALID_ARGUMENT — rejected", 0, 1, 0, 0, 0))
            assertTrue(button.isEnabled)
            button.doClick()
            assertEquals(1, clicks)
            panel.render(BridgeStatusSnapshot(BridgeState.CONNECTING, "Registering", 0, 2, 0, 0, 0))
            assertFalse(button.isEnabled)
        }
    }
}
