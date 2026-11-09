package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.bridge.BridgeState
import com.yyang.jadxmcp.bridge.BridgeStatusSnapshot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import javax.swing.SwingUtilities

class BridgeStatusPanelTest {
    @Test fun compactPanelRefreshesCountersAndStateOnEdtOnly() {
        val sample = BridgeStatusSnapshot(BridgeState.CONNECTED, "Connected", 2, 3, 1, 2, 3)
        val panel = BridgeStatusPanel()
        assertThrows(IllegalStateException::class.java) { panel.render(sample) }
        SwingUtilities.invokeAndWait {
            panel.render(sample)
            assertEquals(2, panel.componentCount)
            assertEquals("Requests — 5s: 1   10s: 2   30s: 3", (panel.getComponent(1) as JLabel).text)
            panel.render(sample.copy(state = BridgeState.RECONNECTING, reason = "Retry in 250ms", requests5s = 0))
            assertTrue((panel.getComponent(0) as JLabel).text.contains("RECONNECTING"))
            assertTrue((panel.getComponent(1) as JLabel).text.contains("5s: 0"))
        }
    }
}
