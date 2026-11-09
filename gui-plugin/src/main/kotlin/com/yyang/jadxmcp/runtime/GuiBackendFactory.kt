package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.analysis.metadata.MetadataService as NativeMetadata
import com.yyang.jadxmcp.analysis.metadata.ManifestService as NativeManifest
import com.yyang.jadxmcp.analysis.resource.ResourceService as NativeResources
import com.yyang.jadxmcp.analysis.search.SearchService as NativeSearch
import com.yyang.jadxmcp.analysis.reference.ReferenceService as NativeReferences
import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.debug.GuiDebuggerPort
import com.yyang.jadxmcp.debug.Jadx156DebuggerFacade
import com.yyang.jadxmcp.project.*
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.*
import jadx.api.JadxDecompiler
import jadx.gui.ui.MainWindow
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class GuiRuntimeOptions(
    val sourceCacheEntries: Int = 64,
    val fingerprintTimeoutMillis: Long = 10_000,
    val debuggerEnabled: Boolean = true
) {
    init { require(sourceCacheEntries > 0); require(fingerprintTimeoutMillis in 1..300_000) }
}

/**
 * Worker-only assembly for ONE loaded project. Existing MainWindow/RenameService must be ready.
 * Host must advance currentEpoch on every close/reload/replacement; never reuse this binding for a
 * new native instance. Native project identity, file paths and codeData are checked on each call,
 * not by a timer or class scan. Input SHA-256 is read only at attach/snapshot/import, off EDT.
 * The returned runtime owns only borrowed wrappers/listeners; the host decompiler remains owned by
 * JADX, and construction failure must release every resource acquired before publication.
 */
object GuiBackendFactory {
    private data class HostProject(val identity: Any, val paths: List<Path>, val data: CodeData)

    fun create(
        window: MainWindow,
        decompiler: JadxDecompiler,
        context: CallContext,
        currentEpoch: () -> Long,
        options: GuiRuntimeOptions = GuiRuntimeOptions(),
        persistenceFailure: (Throwable) -> Unit = {},
        cleanupExecutor: Executor = ForkJoinPool.commonPool()
    ): GuiProjectRuntime {
        requireGuiWorker(); context.checkActive(); context.requireEpoch(currentEpoch())
        val ui = GuiEdtExecutor(currentEpoch)
        val workspace = JadxWorkspace.borrowed(decompiler, currentEpoch)
        val generation = GuiGeneration()
        val renameObserver = AtomicReference<AutoCloseable>()
        try {
            val codec = GuiProjectDataCodec(workspace)
            fun capture(ctx: CallContext): HostProject = ui.readOrEdit(ctx) {
                if (window.wrapper.currentDecompiler.orElse(null) !== decompiler)
                    runtimeFailure(ErrorCode.CONFLICT, "Host decompiler changed")
                val project = window.project
                HostProject(project, project.filePaths.toList(), codec.fromNative(project.codeData ?: jadx.api.data.impl.JadxCodeData()))
            }
            val host = capture(context)
            val observedData = AtomicReference(host.data)
            val files = host.paths.map { it.toFile() }
            val hashing = BoundedGuiFingerprints(options.fingerprintTimeoutMillis)
            val originalInputs = hashing.read(files, context)
            fun fingerprints(ctx: CallContext): List<InputFingerprint> {
                ctx.requireEpoch(currentEpoch())
                val result = hashing.read(files, ctx)
                if (result != originalInputs) runtimeFailure(ErrorCode.CONFLICT, "GUI input contents changed; reload/rebind required")
                ctx.requireEpoch(currentEpoch())
                return result
            }
            val nativeSource = JadxSourceService(workspace, revision = { generation.current().toString() })
            val source = RuntimeSourceCache(nativeSource, generation, options.sourceCacheEntries)
            val maintained = AtomicLong(generation.current())
            fun maintain(ctx: CallContext): Long {
                val stamp = capture(ctx)
                if (stamp.identity !== host.identity || stamp.paths != host.paths)
                    runtimeFailure(ErrorCode.CONFLICT, "GUI project/input owner changed; rebind required")
                if (observedData.getAndSet(stamp.data) != stamp.data) generation.invalidate()
                val token = generation.current()
                if (maintained.get() != token) {
                    // Generated source only: neither project.codeData nor args.codeData is cleared.
                    nativeSource.invalidate(ctx)
                    maintained.set(token)
                }
                if (generation.current() != token) runtimeFailure(ErrorCode.CONFLICT, "GUI generation changed during preparation")
                return token
            }
            fun clear(ctx: CallContext) {
                generation.invalidate()
                val token = generation.current()
                nativeSource.invalidate(ctx)
                maintained.set(token)
            }
            val metadata = NativeMetadata(workspace)
            val refresh = JadxGuiRefresh(window, workspace, ui, generation::invalidate)
            val project = GuiProjectDataOwner(JadxGuiProjectAccess(window, codec, persistenceFailure), codec,
                ::fingerprints, ui, currentEpoch, refresh::afterProjectEdit)
            val comments = GuiCommentEditor(workspace, project, project::replaceData)
            val edits = GuiProjectEditAdapter(JadxNativeRenameFacade(window, workspace, ui, project, currentEpoch,
                generation::invalidate), comments, currentEpoch)
            val queries = GuiQueryAdapter(workspace, metadata, source, source::stats, currentEpoch, ::clear)
            val debugger = if (options.debuggerEnabled)
                GuiDebuggerPort(Jadx156DebuggerFacade { window.debuggerPanel }, currentEpoch, ui) else null
            val modes = workspace.withDecompiler(context) { d ->
                // Inspect the native input format; do not infer DEX from filename or APK alone.
                if (d.classesWithInners.any { it.classNode.clsData?.javaClass?.name == "jadx.plugins.input.dex.sections.DexClassData" })
                    setOf(StringSearchMode.DEX_CONSTANTS, StringSearchMode.SOURCE_TEXT)
                else setOf(StringSearchMode.SOURCE_TEXT)
            }
            ui.readOrEdit(context) {
                // Publish inside EDT action: if checkActive fails after action, catch can still
                // release this listener rather than losing the returned handle on cancellation.
                renameObserver.set(refresh.observeNativeRenames())
            }
            val ports = GuiBackendPorts(metadata, NativeManifest(workspace), source, NativeResources(workspace),
                NativeSearch(workspace), NativeReferences(workspace), GuiContextAdapter(JadxEditorContext(window), ui, source, currentEpoch),
                queries, edits, project, debugger)
            val endpoint = GuiBackendEndpoint(ports, context.projectEpoch, currentEpoch, generation, modes, ::maintain)
            context.checkActive(); context.requireEpoch(currentEpoch())
            val observer = requireNotNull(renameObserver.get())
            return GuiProjectRuntime(endpoint, generation, {
                try { observer.close() } finally { workspace.close() } // BORROWED: never decompiler.close()
            }, cleanupExecutor, projectName = guiProjectName(host.paths))
        } catch (failure: Throwable) {
            try { renameObserver.get()?.close() } finally { workspace.close() }
            throw failure
        }
    }
}

/** Use the captured GUI input list; expose basenames, never parent directories or a project-save path. */
internal fun guiProjectName(inputs: List<Path>): String {
    val names = inputs.map { it.fileName?.toString().orEmpty() }
    val main = names.firstOrNull { it == "base.apk" } ?: names.firstOrNull()
    val name = main?.takeIf { it.isNotBlank() } ?: "jadx-gui"
    val extra = inputs.size - 1
    return if (extra > 0) "$name (+$extra ${if (extra == 1) "file" else "files"})" else name
}
