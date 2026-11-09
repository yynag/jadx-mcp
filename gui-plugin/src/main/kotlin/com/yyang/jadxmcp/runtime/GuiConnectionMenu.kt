package com.yyang.jadxmcp.runtime

import javax.swing.SwingUtilities
import javax.swing.JMenu
import javax.swing.JMenuItem
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.util.concurrent.atomic.AtomicBoolean

/** JADX invokes menu callbacks on its background executor. Never wait for EDT from init:
 * JadxWrapper holds its decompiler monitor while loading plugins on a worker thread.
 * Register independently of settings/HTTP so connection failures cannot hide configuration.
 */
internal class GuiConnectionMenu(
    private val title: String = TITLE,
    private val dispatch: (() -> Unit) -> Unit = { task -> SwingUtilities.invokeLater(task) }
) {
    /** 1.5.6 resetPluginsMenu removes every plugin action. Observe its real popup container,
     * then restore on the next EDT turn, after removeAll and the host's Manage action finish.
     * No project/load event, preferences, or network is required for this restoration.
     */
    fun install(menu: JMenu, register: (String, Runnable) -> Unit, active: () -> Boolean, open: () -> Unit): AutoCloseable {
        // JadxWrapper can reset from a worker; callbacks and teardown can race its removals.
        val closed = AtomicBoolean()
        val queued = AtomicBoolean()
        var ownedItem: JMenuItem? = null // EDT-confined; never remove another plugin's action.
        fun restore() {
            if (closed.get() || !queued.compareAndSet(false, true)) return
            dispatch {
                queued.set(false)
                if (!closed.get() && active() && menu.menuComponents.none {
                    it is JMenuItem && it.text == title
                }) {
                    register(title, Runnable { dispatch { if (!closed.get() && active()) open() } })
                    ownedItem = menu.menuComponents.filterIsInstance<JMenuItem>().firstOrNull { it.text == title }
                }
            }
        }
        val listener = object : ContainerAdapter() {
            override fun componentRemoved(event: ContainerEvent) = restore()
        }
        dispatch {
            if (!closed.get()) { menu.popupMenu.addContainerListener(listener); restore() }
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) dispatch {
                menu.popupMenu.removeContainerListener(listener)
                ownedItem?.let(menu::remove)
                ownedItem = null
            }
        }
    }

    fun install(register: (String, Runnable) -> Unit, active: () -> Boolean, open: () -> Unit) {
        dispatch {
            if (active()) register(title, Runnable {
                dispatch { if (active()) open() }
            })
        }
    }

    companion object { const val TITLE = "Jadx MCP" }
}
