package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/** Fake PORT assembly only: no claim that native windows, event queues or device attach work. */
class GuiRuntimeTest {
    private fun context(epoch: Long = 1) = CallContext("runtime", epoch, System.currentTimeMillis() + 5000)
    private val inputs = listOf(InputFingerprint("a".repeat(64), 1))
    private val snapshot = ProjectDataSnapshot(inputs, "revision")

    private inline fun <reified T> fake(crossinline call: (String) -> Any): T = T::class.java.cast(
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) })

    private fun ports(): GuiBackendPorts = GuiBackendPorts(
        fake<MetadataService> { if (it == "summary") SummaryResult() else ClassResult() },
        fake<ManifestService> { ManifestResult() }, fake<SourceService> { SourceResult() },
        fake<ResourceService> { ResourceResult() }, fake<SearchService> { SearchResult() },
        fake<ReferenceService> { XrefResult() },
        fake<GuiContextPort> { if (it == "currentClass") SourceResult() else SelectionResult() },
        fake<GuiQueryPort> { when (it) {
            "methodByName" -> SourceResult(); "appClassNames" -> AppClassNamesResult()
            "appClassCode" -> AppClassCodeResult(); "packageTree" -> PackageTreeResult()
            "cacheStats" -> CacheStatsResult(); else -> ClearCacheResult()
        } },
        fake<ProjectEditPort> { when (it) {
            "rename" -> RenameResult(); "renamePackage" -> RenamePackageResult()
            "renameVariable" -> RenameVariableResult(); "comment" -> CommentMutationResult()
            else -> CommentsResult()
        } },
        fake<ProjectDataPort> { if (it == "snapshot") snapshot else MutationState(true) },
        fake<DebuggerPort> { DebuggerSnapshot(true, true, listOf(DebugThread("1", "main", true)),
            listOf(DebugFrame(0, "frame")), VariablesResult(listOf(DebugVariable("v", "1")))) }
    )

    private fun requests(): List<BackendRequest> = listOf(
        SummaryRequest("s"), ManifestRequest("s"), ClassRequest("s"), DecompileRequest("s", className = "A"),
        ResourceRequest("s"), SearchRequest("s", term = "A"), XrefRequest("s", target = SymbolRef("A")),
        RenameRequest("s", target = SymbolRef("A"), newName = "B"), CurrentClassRequest("s"), SelectedTextRequest("s"),
        MethodByNameRequest("s", "method"), AppClassNamesRequest("s"), AppClassCodeRequest("s"), PackageTreeRequest("s"),
        CacheStatsRequest("s"), ClearCacheRequest("s"), RenamePackageRequest("s", "old", "newpkg"),
        RenameVariableRequest("s", SymbolRef("A", "m"), "v", "n"), AddCommentRequest("s", "A", "comment"),
        ListCommentsRequest("s"), ThreadsRequest("s"), StackFramesRequest("s"), VariablesRequest("s")
    )

    @Test fun allAssembledGuiOperationsDispatchWithExactProtocolResultTypes() {
        val endpoint = GuiBackendEndpoint(ports(), 1, { 1 }, GuiGeneration(), setOf(StringSearchMode.SOURCE_TEXT))
        val calls = requests()
        assertEquals(Operation.entries.filter { it.scope != OperationScope.MANAGEMENT }.toSet(), calls.map { it.operation }.toSet())
        calls.forEach { assertTrue(it.operation.resultType.isInstance(endpoint.execute(it, context())), it.operation.name) }
        assertTrue(endpoint.capabilities.operations.all { it.supported })
        assertEquals(snapshot, endpoint.snapshot(context()))
        assertTrue(endpoint.importData(snapshot, "revision", context()).applied)
        endpoint.close()
        assertTrue(endpoint.drained.isDone)
    }

    @Test fun optionalDebuggerEpochCancellationCloseAndEdtAreHonestFailures() {
        val endpoint = GuiBackendEndpoint(ports().copy(debugger = null), 1, { 1 }, GuiGeneration(), emptySet())
        fun code(expected: ErrorCode, action: () -> Unit) = assertEquals(expected,
            assertThrows(ProtocolException::class.java, action).error.code)
        assertFalse(endpoint.capabilities.debuggerAvailable)
        code(ErrorCode.UNSUPPORTED) { endpoint.execute(ThreadsRequest("s"), context()) }
        code(ErrorCode.CONFLICT) { endpoint.execute(SummaryRequest("s"), context(2)) }
        code(ErrorCode.CANCELLED) { endpoint.execute(SummaryRequest("s"), context().copy(cancellation = CancellationSignal { true })) }
        SwingUtilities.invokeAndWait { code(ErrorCode.BUSY) { endpoint.execute(SummaryRequest("s"), context()) } }
        endpoint.close()
        code(ErrorCode.CLOSED) { endpoint.execute(SummaryRequest("s"), context()) }
    }

    @Test fun lifecycleFencesOldBindingsAndNeverClosesHostThroughPortRetirement() {
        val lifecycle = GuiBackendLifecycle()
        val epoch = lifecycle.beginProjectChange()
        val generation = GuiGeneration()
        val closes = AtomicInteger()
        val endpoint = GuiBackendEndpoint(ports(), epoch, { lifecycle.projectEpoch }, generation, emptySet())
        val runtime = GuiProjectRuntime(endpoint, generation, { closes.incrementAndGet(); Unit }, Executor { it.run() })
        lifecycle.install(runtime, context(epoch))
        assertTrue(lifecycle.execute(SummaryRequest("s"), context(epoch)) is SummaryResult)
        val next = lifecycle.beginProjectChange()
        lifecycle.drainRetired(context(next))
        assertEquals(1, closes.get()) // Only injected borrowed-resource cleanup, no native owner.
        assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
            lifecycle.execute(SummaryRequest("s"), context(epoch))
        }.error.code)
        assertEquals(ErrorCode.DISCONNECTED, assertThrows(ProtocolException::class.java) {
            lifecycle.execute(SummaryRequest("s"), context(next))
        }.error.code)
        lifecycle.close()
    }

    @Test fun onlyTheInstalledCurrentBindingCanActivateItsImmutableNameAndEpoch() {
        val lifecycle = GuiBackendLifecycle()
        val epoch = lifecycle.beginProjectChange()
        val generation = GuiGeneration()
        val endpoint = GuiBackendEndpoint(ports(), epoch, { lifecycle.projectEpoch }, generation, emptySet())
        val binding = GuiProjectRuntime(endpoint, generation, {}, Executor { it.run() }, projectName = "app.apkm")
        var activations = 0
        assertFalse(lifecycle.withCurrentProject(binding) { activations++ })
        lifecycle.install(binding, context(epoch))
        assertTrue(lifecycle.withCurrentProject(binding) { activations++ })
        lifecycle.beginProjectChange()
        assertFalse(lifecycle.withCurrentProject(binding) { activations++ })
        assertEquals(1, activations)
        assertEquals(epoch to "app.apkm", binding.projectEpoch to binding.projectName)
        assertThrows(ProtocolException::class.java) { lifecycle.install(binding, context(epoch)) }
        lifecycle.close()
        assertFalse(lifecycle.withCurrentProject(binding) { activations++ })
    }

    @Test fun sourceCacheStatsGenerationAndClearNeverNeedProjectCodeData() {
        val count = AtomicInteger()
        val delegate = fake<SourceService> { count.incrementAndGet(); SourceResult("A", text = TextContent("abc")) }
        val generation = GuiGeneration()
        val cache = RuntimeSourceCache(delegate, generation, 2)
        val request = DecompileRequest("s", className = "A")
        cache.decompile(request, context()); cache.decompile(request.copy(sessionId = "another"), context())
        assertEquals(1, count.get())
        assertEquals(CacheStatsResult(1, 1, 1, 3, 3), cache.stats())
        generation.invalidate()
        assertEquals(CacheStatsResult(), cache.stats())
        cache.decompile(request, context())
        assertEquals(2, count.get())
    }

    @Test fun hashBudgetIsClampedAndEdtNeverHashes() {
        var deadline = 0L
        val fingerprints = BoundedGuiFingerprints(50, GuiInputFingerprinter { _, ctx -> deadline = ctx.deadlineEpochMillis; inputs })
        val start = System.currentTimeMillis()
        assertEquals(inputs, fingerprints.read(emptyList(), context()))
        assertTrue(deadline in (start + 1)..(System.currentTimeMillis() + 50))
        SwingUtilities.invokeAndWait {
            assertEquals(ErrorCode.BUSY, assertThrows(ProtocolException::class.java) {
                fingerprints.read(emptyList(), context())
            }.error.code)
        }
    }

    @Test fun inFlightCallDrainsBeforeBorrowedCleanupAndCannotPublishAfterRetirement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val blocking = fake<MetadataService> { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); SummaryResult() }
            val generation = GuiGeneration()
            val endpoint = GuiBackendEndpoint(ports().copy(metadata = blocking), 1, { 1 }, generation, emptySet())
            val runtime = GuiProjectRuntime(endpoint, generation, { cleaned.incrementAndGet(); Unit }, Executor { it.run() })
            val call = worker.submit<ErrorCode> {
                try { endpoint.execute(SummaryRequest("s"), context()); ErrorCode.INTERNAL }
                catch (e: ProtocolException) { e.error.code }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(ErrorCode.BUSY, assertThrows(ProtocolException::class.java) {
                endpoint.execute(SummaryRequest("s"), context())
            }.error.code)
            val retired = runtime.retire()
            assertFalse(retired.isDone); assertEquals(0, cleaned.get())
            release.countDown()
            assertEquals(ErrorCode.CANCELLED, call.get(2, TimeUnit.SECONDS))
            retired.get(2, TimeUnit.SECONDS)
            assertEquals(1, cleaned.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun preparationGenerationAndWrongRequestTypeCannotPublishFalseSuccess() {
        val generation = GuiGeneration()
        val endpoint = GuiBackendEndpoint(ports(), 1, { 1 }, generation, emptySet()) {
            val prepared = generation.current(); generation.invalidate(); prepared
        }
        assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
            endpoint.execute(SummaryRequest("s"), context())
        }.error.code)
        val wrong = object : BackendRequest {
            override val sessionId = "s"
            override val operation = Operation.META_SUMMARY
            override fun validate() = Unit
        }
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
            endpoint.execute(wrong, context())
        }.error.code)
    }

    @Test fun overBudgetHashResultIsRejectedEvenIfInjectedWorkerIgnoresItsContext() {
        var clock = 1000L
        val reader = BoundedGuiFingerprints(10, GuiInputFingerprinter { _, _ -> clock = 1011; inputs }, { clock })
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(ProtocolException::class.java) {
            reader.read(emptyList(), context().copy(deadlineEpochMillis = 2000))
        }.error.code)
    }
}
