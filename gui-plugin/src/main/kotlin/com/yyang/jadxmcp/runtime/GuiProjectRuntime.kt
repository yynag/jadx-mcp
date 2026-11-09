package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.awaitGuiFuture
import com.yyang.jadxmcp.ui.requireGuiWorker
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * One immutable project binding. Retirement never joins on EDT; resources close only after all
 * endpoint calls leave. closeResources must release BORROWED wrappers/listeners, NEVER host native
 * decompiler. Integration must drain before destroying the host where a pre-close route exists.
 */
class GuiProjectRuntime(
    val endpoint: GuiBackendEndpoint,
    val generation: GuiGeneration,
    private val closeResources: () -> Unit,
    private val cleanupExecutor: Executor = ForkJoinPool.commonPool(),
    val projectName: String = "jadx-gui"
) : AutoCloseable {
    val projectData: ProjectDataPort get() = endpoint
    val projectEpoch: Long get() = endpoint.projectEpoch
    private val retired = AtomicReference<CompletableFuture<Unit>>()

    fun retire(): CompletableFuture<Unit> {
        retired.get()?.let { return it }
        val completion = CompletableFuture<Unit>()
        if (!retired.compareAndSet(null, completion)) return requireNotNull(retired.get())
        generation.invalidate()
        endpoint.close()
        endpoint.drained.whenComplete { _, failure ->
            if (failure != null) completion.completeExceptionally(failure)
            else try {
                val cleanup = Runnable {
                    try { closeResources(); completion.complete(Unit) }
                    catch (e: Throwable) { completion.completeExceptionally(e) }
                }
                cleanupExecutor.execute {
                    // Even an accidentally inline injected executor must never drain native locks
                    // on EDT. Default/common-pool cleanup does not own/close the host decompiler.
                    if (SwingUtilities.isEventDispatchThread()) ForkJoinPool.commonPool().execute(cleanup)
                    else cleanup.run()
                }
            } catch (e: Exception) { completion.completeExceptionally(e) }
        }
        return completion
    }

    override fun close() { retire() }
}

/** Stable endpoint for the bridge; explicit epoch changes, no timer, polling or automatic sharing. */
class GuiBackendLifecycle(initialEpoch: Long = 0) : BackendEndpoint, ProjectDataPort {
    private val monitor = Any()
    private var epoch = initialEpoch
    private var active: GuiProjectRuntime? = null
    private var closed = false
    private val retiring = mutableListOf<CompletableFuture<Unit>>()
    init { require(initialEpoch >= 0) }
    val projectEpoch: Long get() = synchronized(monitor) { epoch }
    /** Admission and bridge activation share the project-change fence, not a racy epoch precheck.
     * The action may only update/enqueue bridge state; never perform I/O or wait for EDT here.
     */
    internal fun withCurrentProject(runtime: GuiProjectRuntime, action: () -> Unit): Boolean = synchronized(monitor) {
        if (closed || active !== runtime || runtime.projectEpoch != epoch) return@synchronized false
        action()
        true
    }
    override val capabilities: BackendCapabilities get() = synchronized(monitor) {
        active?.endpoint?.capabilities ?: BackendCapabilities(BackendKind.GUI,
            Operation.entries.filter { it.scope != OperationScope.MANAGEMENT }.map {
                OperationCapability(it, false, reason = "GUI project is not ready")
            })
    }

    /** EDT-safe admission fence. It cannot delay native MainWindow.closeAll/wrapper.close. */
    fun beginProjectChange(): Long {
        val previous: GuiProjectRuntime?
        val next: Long
        synchronized(monitor) {
            if (closed) runtimeFailure(ErrorCode.CLOSED, "GUI lifecycle is closed")
            if (epoch == Long.MAX_VALUE) runtimeFailure(ErrorCode.CLOSED, "GUI epoch exhausted")
            next = ++epoch; previous = active; active = null
        }
        previous?.let(::trackRetirement)
        return next
    }

    /** Factory construction happens on worker BEFORE install; a late binding is retired, not leaked. */
    fun install(runtime: GuiProjectRuntime, context: CallContext) {
        try {
            context.checkActive()
            synchronized(monitor) {
                if (closed) runtimeFailure(ErrorCode.CLOSED, "GUI lifecycle is closed")
                context.requireEpoch(epoch); context.requireEpoch(runtime.projectEpoch); context.checkActive()
                if (active != null) runtimeFailure(ErrorCode.CONFLICT, "Begin a project change before replacing a GUI binding")
                active = runtime
            }
        } catch (e: Throwable) {
            if (synchronized(monitor) { active !== runtime }) trackRetirement(runtime)
            throw e
        }
    }

    private fun binding(context: CallContext): GuiProjectRuntime = synchronized(monitor) {
        if (closed) runtimeFailure(ErrorCode.CLOSED, "GUI lifecycle is closed")
        context.requireEpoch(epoch); context.checkActive()
        active ?: runtimeFailure(ErrorCode.DISCONNECTED, "GUI project is not loaded")
    }
    override fun execute(request: BackendRequest, context: CallContext) = binding(context).endpoint.execute(request, context)
    override fun snapshot(context: CallContext) = binding(context).projectData.snapshot(context)
    override fun importData(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext) =
        binding(context).projectData.importData(snapshot, expectedRevision, context)

    private fun trackRetirement(runtime: GuiProjectRuntime) {
        val future = runtime.retire()
        synchronized(monitor) { retiring.add(future) }
        future.whenComplete { _, failure -> if (failure == null) synchronized(monitor) { retiring.remove(future) } }
    }

    /** Worker-only cooperative wait. Caller may close the native host ONLY after this succeeds. */
    fun drainRetired(context: CallContext) {
        requireGuiWorker()
        val pending = synchronized(monitor) { retiring.toList() }
        pending.forEach { awaitGuiFuture(it, context) { projectEpoch } }
        context.checkActive(); context.requireEpoch(projectEpoch)
    }

    override fun close() {
        val previous: GuiProjectRuntime?
        synchronized(monitor) {
            if (closed) return
            closed = true; if (epoch < Long.MAX_VALUE) epoch++
            previous = active; active = null
        }
        previous?.let(::trackRetirement)
    }
}
