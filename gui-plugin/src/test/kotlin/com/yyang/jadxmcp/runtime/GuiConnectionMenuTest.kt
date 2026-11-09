package com.yyang.jadxmcp.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import javax.swing.JMenu

class GuiConnectionMenuTest {
    @Test fun unifiedMenuRestoresExactlyOneActionAndUnloadRemovesIt() {
        val menu = JMenu("Plugins")
        fun drain() { repeat(5) { SwingUtilities.invokeAndWait { } } }
        val config = GuiConnectionMenu().install(menu, { title, _ -> menu.add(title) }, { true }) { }
        drain(); assertEquals(1, menu.menuComponentCount)
        assertEquals("Jadx MCP", (menu.getMenuComponent(0) as javax.swing.JMenuItem).text)
        SwingUtilities.invokeAndWait { menu.removeAll() }
        drain(); assertEquals(1, menu.menuComponentCount)
        config.close(); drain(); assertEquals(0, menu.menuComponentCount)
        assertEquals(0, menu.popupMenu.containerListeners.size)
    }
    @Test fun popupResetRestoresExactlyOneActionAndUnloadRemovesObserver() {
        val menu = JMenu("Plugins")
        var registrations = 0
        val registration = GuiConnectionMenu().install(menu, { title, _ ->
            assertTrue(SwingUtilities.isEventDispatchThread())
            registrations++
            menu.add(title)
        }, { true }) { }
        fun drain() { repeat(4) { SwingUtilities.invokeAndWait { } } }
        drain()
        assertEquals(1, registrations)
        SwingUtilities.invokeAndWait { menu.removeAll(); menu.add("Manage plugins") }
        drain()
        assertEquals(2, registrations)
        SwingUtilities.invokeAndWait { menu.removeAll(); menu.removeAll(); menu.add("Manage plugins") }
        drain()
        assertEquals(3, registrations)
        registration.close()
        drain()
        SwingUtilities.invokeAndWait { menu.removeAll() }
        drain()
        assertEquals(3, registrations)
        assertEquals(0, menu.menuComponentCount)
    }

    @Test fun backgroundInitRegistersAndBackgroundCallbackDispatchesToEdt() {
        var action: Runnable? = null
        var opened = false
        GuiConnectionMenu().install({ title, callback ->
            assertTrue(SwingUtilities.isEventDispatchThread())
            assertEquals("Jadx MCP", title)
            action = callback
        }, { true }) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            opened = true
        }
        SwingUtilities.invokeAndWait { }
        assertNotNull(action)
        action!!.run()
        SwingUtilities.invokeAndWait { }
        assertTrue(opened)
    }

    @Test fun unloadFencesQueuedRegistrationAndExistingCallback() {
        val queue = mutableListOf<() -> Unit>()
        var active = true
        var callback: Runnable? = null
        var opened = false
        val menu = GuiConnectionMenu { queue.add(it) }
        menu.install({ _, action -> callback = action }, { active }) { opened = true }
        queue.removeAt(0)()
        callback!!.run()
        active = false
        queue.removeAt(0)()
        assertFalse(opened)
        menu.install({ _, _ -> fail("Unloaded registration") }, { active }) { }
        queue.removeAt(0)()
    }
}
