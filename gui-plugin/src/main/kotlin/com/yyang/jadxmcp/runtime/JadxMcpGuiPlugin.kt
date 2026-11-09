package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.bridge.BridgeStatusModel
import com.yyang.jadxmcp.bridge.ExecutorBridgeRuntime
import com.yyang.jadxmcp.bridge.GuiBridgeClient
import com.yyang.jadxmcp.bridge.HttpGuiChannelPort
import com.yyang.jadxmcp.protocol.CallContext
import jadx.api.JadxDecompiler
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.JadxPluginInfoBuilder
import jadx.gui.ui.MainWindow
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * New plugin entry. The old adapter remains in the tree for rollback, but is no longer registered
 * by META-INF/services. Native GUI ownership stays in JADX: this plugin only owns listeners,
 * bridge workers, and borrowed runtime wrappers. unload is the terminal fence; bridge and hook
 * retirement are idempotent so repeated host teardown cannot double-release them.
 */
class JadxMcpGuiPlugin : JadxPlugin {
    private val closed = AtomicBoolean()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jadx-mcp-gui-runtime").apply { isDaemon = true }
    }
    private val lifecycle = GuiBackendLifecycle()
    private val bridge = AtomicReference<GuiBridgeClient?>()
    private val hook = AtomicReference<AutoCloseable?>()
    private val runtime = AtomicReference<GuiProjectRuntime?>()
    private val menu = AtomicReference<AutoCloseable?>()
    private val statusWindow = AtomicReference<GuiBridgeStatusWindow?>()
    private val status = AtomicReference(BridgeStatusModel())
    private val settings = AtomicReference(MasterConnectionSettings.parse(MasterConnectionSettings.DEFAULT_URL))
    private val settingsNotice = AtomicReference<String?>()

    override fun getPluginInfo(): JadxPluginInfo = JadxPluginInfoBuilder.pluginId("jadx-mcp-gui")
        .name("JADX MCP GUI bridge")
        .description("Expose the current JADX GUI project through the rewritten jadx-mcp master")
        .build()

    override fun init(context: JadxPluginContext) {
        require(!closed.get()) { "GUI plugin is already unloaded" }
        val gui = context.guiContext ?: return
        val window = gui.mainFrame as? MainWindow ?: return
        val instanceId = UUID.randomUUID().toString()
        val liveStatus = GuiBridgeStatusWindow(
            window,
            snapshot = { status.get().snapshot() },
            currentSettings = settings::get,
            applySettings = { selected, done -> applyConnectionSettings(selected, instanceId, done) },
            settingsNotice = settingsNotice::get,
        )
        statusWindow.set(liveStatus)
        menu.set(GuiConnectionMenu().install(
            window.pluginsMenu, gui::addMenuAction, { !closed.get() }, liveStatus::open))
        SwingUtilities.invokeLater {
            if (closed.get()) return@invokeLater
            val installedHook = JadxGuiLoadHook.install(window, lifecycle,
                onLoaded = { epoch, loaded -> loadProject(context, window, loaded, epoch) },
                onProjectChange = { epoch, _ -> bridge.get()?.let { it.disable(); it.updateProject(epoch) } },
                onFailure = { System.err.println("jadx-mcp GUI lifecycle failure: ${it.message}") })
            hook.set(installedHook)
            if (closed.get()) hook.getAndSet(null)?.close()
        }
        worker.submit {
            runCatching {
                val loaded = MasterConnectionSettings.load()
                settings.set(loaded)
                reconfigureBridge(loaded, instanceId)
            }
                .onFailure {
                    settingsNotice.set("Saved address unsupported: enter IP and port again (HTTP only)")
                    System.err.println("jadx-mcp connection initialization failed: $it")
                }
        }
    }

    /** Keep Preferences.flush and bridge retirement off the EDT. The form owns callback dispatch.
     * A saved setting is not evidence of a connected server; the bridge state reports that separately.
     */
    private fun applyConnectionSettings(
        selected: MasterConnectionSettings,
        instanceId: String,
        done: (Boolean) -> Unit,
    ) {
        if (closed.get()) {
            done(false)
            return
        }
        try {
            worker.execute {
                val success = runCatching {
                    MasterConnectionSettings.save(selected)
                    reconfigureBridge(selected, instanceId)
                    settings.set(selected)
                    settingsNotice.set(null)
                }.isSuccess
                done(success)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Unload may fence the executor between the closed check and enqueueing this task.
            done(false)
        }
    }

    private fun createBridge(settings: MasterConnectionSettings, instanceId: String, model: BridgeStatusModel): GuiBridgeClient =
        GuiBridgeClient(HttpGuiChannelPort(java.net.URI.create(settings.baseUrl)), lifecycle, instanceId, "jadx-gui",
            runtime = ExecutorBridgeRuntime(), observer = model)

    private fun reconfigureBridge(settings: MasterConnectionSettings, instanceId: String) {
        if (closed.get()) return
        val model = BridgeStatusModel()
        val replacement = createBridge(settings, instanceId, model)
        val previous = bridge.getAndSet(replacement)
        // Bridge close is fenced on this worker, never in the menu's EDT callback.
        previous?.close()
        status.set(model)
        replacement.updateProject(lifecycle.projectEpoch)
        if (closed.get()) {
            bridge.compareAndSet(replacement, null)
            replacement.close()
        } else if (runtime.get()?.projectEpoch == lifecycle.projectEpoch) replacement.enable()
    }

    private fun loadProject(
        context: JadxPluginContext,
        window: MainWindow,
        decompiler: JadxDecompiler,
        epoch: Long,
    ) {
        if (closed.get()) return
        bridge.get()?.disable()
        worker.submit {
            try {
                val call = CallContext("gui-load-$epoch", epoch, System.currentTimeMillis() + 120_000)
                val binding = GuiBackendFactory.create(window, decompiler, call, lifecycle::projectEpoch,
                    persistenceFailure = { System.err.println("jadx-mcp project persistence failure: ${it.message}") })
                lifecycle.install(binding, call)
                runtime.getAndSet(binding)?.retire()
                if (!closed.get() && lifecycle.projectEpoch == epoch) bridge.get()?.let {
                    it.updateProject(epoch)
                    it.enable()
                }
                else binding.retire()
            } catch (failure: Throwable) {
                System.err.println("jadx-mcp GUI project binding failed: ${failure.message}")
            }
        }
    }

    override fun unload() {
        if (!closed.compareAndSet(false, true)) return
        // Unregister/HTTP work never runs on the EDT. Native decompiler remains host-owned.
        // Retirement may complete asynchronously; unload only fences admission.
        bridge.getAndSet(null)?.close()
        hook.getAndSet(null)?.close()
        menu.getAndSet(null)?.close()
        statusWindow.getAndSet(null)?.close()
        lifecycle.close()
        runtime.getAndSet(null)?.retire()
        worker.shutdown()
        if (!SwingUtilities.isEventDispatchThread()) {
            runCatching { worker.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }
}
