package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.bridge.BridgeStatusModel
import com.yyang.jadxmcp.bridge.ExecutorBridgeRuntime
import com.yyang.jadxmcp.bridge.GuiBridgeClient
import com.yyang.jadxmcp.bridge.HttpGuiChannelPort
import com.yyang.jadxmcp.bridge.GuiDiagnosticLog
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
 * ServiceLoader entry for the GUI bridge. Native GUI ownership stays in JADX: this plugin owns
 * listeners, bridge workers, and borrowed runtime wrappers. unload is the terminal fence; bridge and hook
 * retirement are idempotent so repeated host teardown cannot double-release them.
 */
class JadxMcpGuiPlugin : JadxPlugin {
    private val closed = AtomicBoolean()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jadx-mcp-gui-runtime").apply { isDaemon = true }
    }
    private val lifecycle = GuiBackendLifecycle()
    private val bridge = AtomicReference<GuiBridgeClient?>()
    // A project gets a fresh instance identity; the previous registration must retire before handoff.
    private val retiredBridge = AtomicReference<GuiBridgeClient?>()
    private val projectInstanceId = AtomicReference<String?>()
    private val hook = AtomicReference<AutoCloseable?>()
    private val runtime = AtomicReference<GuiProjectRuntime?>()
    private val menu = AtomicReference<AutoCloseable?>()
    private val statusWindow = AtomicReference<GuiBridgeStatusWindow?>()
    private val status = AtomicReference(BridgeStatusModel())
    private val settings = AtomicReference<MasterConnectionSettings?>()
    private val settingsNotice = AtomicReference<String?>()
    @Volatile private var diagnostics: GuiDiagnosticLog? = null

    override fun getPluginInfo(): JadxPluginInfo = JadxPluginInfoBuilder.pluginId("jadx-mcp-gui")
        .name("JADX MCP GUI bridge")
        .description("Expose the current JADX GUI project through the jadx-mcp master")
        .build()

    override fun init(context: JadxPluginContext) {
        require(!closed.get()) { "GUI plugin is already unloaded" }
        val gui = context.guiContext ?: return
        val window = gui.mainFrame as? MainWindow ?: return
        // JADX 1.5.6 owns this plugin-specific cache path. Resolve lazily: OFF creates nothing.
        val diagnosticLog = GuiDiagnosticLog(directory = { context.files().pluginCacheDir.resolve("logs") })
        diagnostics = diagnosticLog
        val liveStatus = GuiBridgeStatusWindow(
            window,
            snapshot = { status.get().snapshot() },
            currentSettings = { settings.get() ?: MasterConnectionSettings.parse(MasterConnectionSettings.DEFAULT_URL) },
            applySettings = { selected, done -> applyConnectionSettings(selected, done) },
            reconnect = { reconnectBridge() },
            settingsNotice = settingsNotice::get,
            diagnosticState = { diagnosticLog.enabled to diagnosticLog.notice },
            toggleDiagnostics = { enabled, done ->
                // Stop/close now, even if project binding has queued work ahead of Preferences.flush.
                if (!enabled) diagnosticLog.setEnabled(false)
                try {
                    worker.execute {
                        diagnosticLog.setEnabled(enabled) { MasterConnectionSettings.saveDiagnostics(it) }
                        done()
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) { done() }
            },
        )
        statusWindow.set(liveStatus)
        menu.set(GuiConnectionMenu().install(
            window.pluginsMenu, gui::addMenuAction, { !closed.get() }, liveStatus::open))
        worker.submit {
            try {
                diagnosticLog.setEnabled(MasterConnectionSettings.loadDiagnostics())
                diagnosticLog.event("plugin_initialized")
            } catch (failure: Exception) {
                settingsNotice.set("Diagnostic setting load failed (${failure.javaClass.simpleName})")
            }
            val result = loadSavedConnectionSettings { MasterConnectionSettings.load() }
            if (result.notice != null) settingsNotice.set(result.notice)
            val loaded = result.settings ?: return@submit
            settings.set(loaded)
            try {
                reconfigureBridge(loaded, "initial_settings")
            } catch (failure: LinkageError) {
                diagnosticLog.event("bridge_initialization_failed", failure)
                settingsNotice.set(connectionRuntimeFailure(failure).message)
            } catch (failure: Exception) {
                diagnosticLog.event("bridge_initialization_failed", failure)
                settingsNotice.set("Bridge initialization failed (${failure.javaClass.simpleName})")
            }
        }
        // Queue settings first: addLoadListener may immediately enqueue a loaded project's binding.
        SwingUtilities.invokeLater {
            if (closed.get()) return@invokeLater
            val installedHook = JadxGuiLoadHook.install(window, lifecycle,
                onLoaded = { epoch, loaded -> loadProject(context, window, loaded, epoch) },
                onProjectChange = { _, _ -> retireProjectBinding() },
                onFailure = { diagnosticLog.event("gui_lifecycle_failed", it) })
            hook.set(installedHook)
            if (closed.get()) hook.getAndSet(null)?.close()
        }
    }

    /** Keep Preferences.flush and bridge retirement off the EDT. The form owns callback dispatch.
     * A saved setting is not evidence of a connected server; the bridge state reports that separately.
     */
    private fun applyConnectionSettings(
        selected: MasterConnectionSettings,
        done: (ConnectionSettingsApplyResult) -> Unit,
    ) {
        if (closed.get()) {
            done(ConnectionSettingsApplyResult.UNLOADED)
            return
        }
        try {
            worker.execute {
                if (closed.get()) {
                    done(ConnectionSettingsApplyResult.UNLOADED)
                    return@execute
                }
                val result = applySavedConnectionSettings(selected,
                    save = { MasterConnectionSettings.save(it) },
                    activate = {
                        reconfigureBridge(it, "settings_apply")
                        settings.set(it)
                        settingsNotice.set(null)
                    })
                diagnostics?.event("connection_settings_applied", null, "applied" to result.applied)
                done(result)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Unload may fence the executor between the closed check and enqueueing this task.
            done(ConnectionSettingsApplyResult.WORKER_CLOSED)
        }
    }

    private fun createBridge(settings: MasterConnectionSettings, instanceId: String, model: BridgeStatusModel,
                             binding: GuiProjectRuntime, previous: GuiBridgeClient?): GuiBridgeClient =
        GuiBridgeClient(HttpGuiChannelPort(java.net.URI.create(settings.baseUrl), diagnostics = diagnostics), lifecycle, instanceId, binding.projectName,
            initialProjectEpoch = binding.projectEpoch, runtime = ExecutorBridgeRuntime(), observer = model,
            previousRetired = { previous?.isRegistrationRetired() != false }, diagnostics = diagnostics)

    private fun reconfigureBridge(settings: MasterConnectionSettings, source: String) {
        if (closed.get()) return
        diagnostics?.event("bridge_reconfigure", null, "source" to source,
            "instance" to projectInstanceId.get()?.let(GuiDiagnosticLog::id))
        val model = BridgeStatusModel()
        // Saving an address is local configuration, not a request to connect without a project.
        // Defer bridge ownership until the host lends us a successfully bound endpoint.
        val binding = runtime.get()
        val instanceId = projectInstanceId.get()
        if (binding == null || instanceId == null || binding.projectEpoch != lifecycle.projectEpoch) {
            bridge.getAndSet(null)?.let { previous ->
                previous.close()
                retiredBridge.set(previous)
            }
            status.set(model)
            return
        }
        val replacement = createBridge(settings, instanceId, model, binding, bridge.get() ?: retiredBridge.get())
        val previous = bridge.getAndSet(replacement)
        // Bridge close is fenced on this worker, never in the menu's EDT callback.
        previous?.close()
        retiredBridge.set(null)
        status.set(model)
        if (closed.get()) {
            bridge.compareAndSet(replacement, null)
            replacement.close()
        } else lifecycle.withCurrentProject(binding) { replacement.enable() }
    }

    /** User-triggered recovery only; no loaded project means no bridge and therefore no HTTP call. */
    private fun reconnectBridge() {
        if (closed.get()) return
        try {
            worker.execute {
                if (closed.get()) return@execute
                val activeRuntime = runtime.get()
                if (activeRuntime == null || activeRuntime.projectEpoch != lifecycle.projectEpoch) {
                    status.set(BridgeStatusModel())
                    return@execute
                }
                bridge.get()?.reconnect()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Unload raced the click; there is no live plugin left to reconnect.
        }
    }

    private fun loadProject(
        context: JadxPluginContext,
        window: MainWindow,
        decompiler: JadxDecompiler,
        epoch: Long,
    ) {
        if (closed.get()) return
        worker.submit {
            try {
                val call = CallContext("gui-load-$epoch", epoch, System.currentTimeMillis() + 120_000)
                val binding = GuiBackendFactory.create(window, decompiler, call, lifecycle::projectEpoch,
                    persistenceFailure = { diagnostics?.event("project_persistence_failed", it) })
                lifecycle.install(binding, call)
                runtime.getAndSet(binding)?.retire()
                // A native project is a new MCP session, not a reconnect/update of the old project.
                val previous = bridge.getAndSet(null)
                previous?.close()
                if (previous != null) retiredBridge.set(previous)
                val instanceId = UUID.randomUUID().toString()
                projectInstanceId.set(instanceId)
                diagnostics?.event("project_bound", null, "instance" to GuiDiagnosticLog.id(instanceId), "epoch" to epoch)
                val activated = !closed.get() && lifecycle.withCurrentProject(binding) {
                    settings.get()?.let { reconfigureBridge(it, "project_load") }
                }
                if (!activated) binding.retire()
            } catch (failure: LinkageError) {
                diagnostics?.event("project_binding_failed", failure)
                settingsNotice.set(connectionRuntimeFailure(failure).message)
            } catch (failure: Throwable) {
                diagnostics?.event("project_binding_failed", failure)
            }
        }
    }

    /** Close/unregister the prior project lease before a newly bound project gets its own identity. */
    private fun retireProjectBinding() {
        diagnostics?.event("project_retiring", null, "source" to "native_project_change",
            "instance" to projectInstanceId.get()?.let(GuiDiagnosticLog::id))
        projectInstanceId.set(null)
        status.set(BridgeStatusModel())
        bridge.getAndSet(null)?.let { previous ->
            previous.close()
            retiredBridge.set(previous)
        }
        runtime.getAndSet(null)?.retire()
    }

    override fun unload() {
        if (!closed.compareAndSet(false, true)) return
        try {
            diagnostics?.event("plugin_unload")
            // Unregister/HTTP work never runs on the EDT. Native decompiler remains host-owned.
            // Retirement may complete asynchronously; unload only fences admission.
            bridge.getAndSet(null)?.close()
            retiredBridge.getAndSet(null)?.close()
            projectInstanceId.set(null)
            hook.getAndSet(null)?.close()
            menu.getAndSet(null)?.close()
            statusWindow.getAndSet(null)?.close()
            lifecycle.close()
            runtime.getAndSet(null)?.retire()
            worker.shutdown()
            if (!SwingUtilities.isEventDispatchThread()) {
                runCatching { worker.awaitTermination(5, TimeUnit.SECONDS) }
            }
        } finally {
            // Do not wait for late transport cleanup just to collect its diagnostics.
            diagnostics?.close()
        }
    }
}
