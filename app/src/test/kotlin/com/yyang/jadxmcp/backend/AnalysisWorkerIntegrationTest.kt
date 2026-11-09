package com.yyang.jadxmcp.backend

import com.yyang.jadxmcp.process.*
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.*
import com.yyang.jadxmcp.worker.OwnedWorkerCache
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real sample.jar + real JADX services, including independent child JVMs and typed loopback RPC. */
class AnalysisWorkerIntegrationTest {
    private fun context(timeout: Long = 60_000, cancellation: CancellationSignal = CancellationSignal.NONE) =
        CallContext(UUID.randomUUID().toString(), 0, System.currentTimeMillis() + timeout, cancellation)
    private fun sandbox() = OwnedWorkerCache.create(Path.of("build", "real-analysis-tests"))
    private fun fixture(root: Path): Path {
        val input = Files.createDirectories(root.resolve("中文 inputs")).resolve("sample.jar")
        javaClass.getResourceAsStream("/sample.jar")!!.use { Files.copy(it, input) }
        return input.toAbsolutePath()
    }
    private fun classpath(): String {
        val urls = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }
        return (System.getProperty("java.class.path").split(File.pathSeparator).asSequence() + urls)
            .distinct().joinToString(File.pathSeparator)
    }
    private fun config() = WorkerProcessConfig(AnalysisWorkerEndpointFactory::class.java.name,
        classpath(), defaultMaxHeap = "256m", startupTimeoutMillis = 30_000,
        shutdownGraceMillis = 500)
    private fun error(code: ErrorCode, action: () -> Unit): ProtocolException =
        assertFailsWith<ProtocolException>(block = action).also { assertEquals(code, it.error.code) }
    private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
    private val hello = "com.yyang.sample.HelloSample"

    @Test fun `real sample child summary class source resource errors and owned clear cache through session`() {
        val root = sandbox()
        val input = fixture(root.directory)
        val original = sha(input)
        val edits = Files.writeString(input.resolveSibling("sample.jar.jadx"), "user codeData - do not overwrite")
        var worker: WorkerBackend? = null
        try {
            WorkerBackendFactory(config()).use { factory ->
                SessionService(HeadlessBackendFactory { request, context ->
                    factory.create(request, context).also { worker = it }
                }).use { sessions ->
                    val info = sessions.create(CreateSessionRequest(input.toString(), deobf = false), context())
                    val id = info.sessionId
                    assertTrue(worker!!.isAlive)
                    val summary = sessions.execute(SummaryRequest(id), context()) as SummaryResult
                    assertTrue(summary.classesCount >= 2)
                    assertNull(summary.mainActivity) // JAR has no Android launcher.
                    val classes = sessions.execute(ClassRequest(id), context()) as ClassResult
                    assertTrue(hello in classes.classes)
                    val details = sessions.execute(ClassRequest(id, className = hello), context()) as ClassResult
                    assertTrue(details.methods.any { it.name == "greet" })
                    val source = sessions.execute(DecompileRequest(id, className = hello), context()) as SourceResult
                    assertTrue(source.text.content.contains("JADX_CORE_MCP_SAMPLE_MAGIC"), source.text.content)
                    assertFalse(source.text.truncated)
                    val resources = sessions.execute(ResourceRequest(id), context()) as ResourceResult
                    assertEquals(ResourceAction.LIST, resources.action)
                    error(ErrorCode.NOT_FOUND) {
                        sessions.execute(ResourceRequest(id, ResourceAction.FILE, fileName = "missing-resource.xml"), context())
                    }
                    error(ErrorCode.NOT_FOUND) { sessions.execute(ManifestRequest(id), context()) }
                    error(ErrorCode.NOT_FOUND) {
                        sessions.execute(DecompileRequest(id, SourceTarget.MAIN_ACTIVITY), context())
                    }
                    val cache = worker!!.generatedCacheDirectory
                    assertEquals(input.resolveSibling("sample.jar.jadx_mcp_cache"), cache.parent)
                    assertTrue(Files.isDirectory(cache.resolve("plugin-cache")))
                    assertTrue(Files.isDirectory(cache.resolve("plugin-config")))
                    assertTrue(Files.isDirectory(cache.resolve("temp")))
                    assertTrue(Files.isDirectory(cache.resolve("jvm-temp")))
                    assertTrue(Files.isRegularFile(cache.resolve("logs/server.log")), "Native worker logs must stay inside its own session directory")
                    val sibling = factory.create(CreateSessionRequest(input.toString(), deobf = false), context())
                    try {
                        assertEquals(cache.parent, sibling.generatedCacheDirectory.parent)
                        assertNotEquals(cache, sibling.generatedCacheDirectory)
                        assertTrue(Files.isRegularFile(sibling.generatedCacheDirectory.resolve("logs/server.log")))
                        Files.writeString(sibling.generatedCacheDirectory.resolve("active-sentinel"), "active")
                        val result = sessions.execute(CloseSessionRequest(id, clearCache = true), context()) as CloseSessionResult
                        assertTrue(result.closed)
                        assertFalse(worker!!.isAlive)
                        assertFalse(Files.exists(cache), "clearCache must acknowledge actual owned deletion")
                        assertEquals("active", Files.readString(sibling.generatedCacheDirectory.resolve("active-sentinel")))
                        assertTrue((sibling.execute(SummaryRequest("sibling"), context()) as SummaryResult).classesCount > 0)
                    } finally { sibling.close(true) }
                    assertEquals(original, sha(input))
                    assertEquals("user codeData - do not overwrite", Files.readString(edits))
                    Files.list(input.parent).use { assertEquals(setOf("sample.jar", "sample.jar.jadx", "sample.jar.jadx_mcp_cache"), it.map { p -> p.fileName.toString() }.toList().toSet()) }
                }
            }
        } finally {
            worker?.close(true)
            root.clear()
        }
    }

    @Test fun `real child search xref and in memory rename preserve truthful capabilities and revision`() {
        val root = sandbox()
        val input = fixture(root.directory)
        try {
            WorkerBackendFactory(config()).use { factory ->
                val worker = factory.create(CreateSessionRequest(input.toString(), deobf = false), context())
                try {
                    val capabilities = worker.capabilities
                    assertEquals(setOf(StringSearchMode.SOURCE_TEXT), capabilities.stringSearchModes)
                    for (operation in listOf(Operation.SEARCH, Operation.XREF, Operation.RENAME))
                        assertTrue(capabilities.operations.single { it.operation == operation }.supported)
                    assertFalse(capabilities.operations.single { it.operation == Operation.ADD_COMMENT }.supported)
                    val names = worker.execute(SearchRequest("real", SearchScope.CLASS, "HelloSample"), context()) as SearchResult
                    assertTrue(names.hits.any { it.symbol.className == hello })
                    error(ErrorCode.UNSUPPORTED) {
                        worker.execute(SearchRequest("real", SearchScope.STRING, "JADX_CORE_MCP_SAMPLE_MAGIC"), context())
                    }
                    val strings = worker.execute(SearchRequest("real", SearchScope.STRING, "JADX_CORE_MCP_SAMPLE_MAGIC",
                        stringMode = StringSearchMode.SOURCE_TEXT), context()) as SearchResult
                    assertEquals(StringSearchMode.SOURCE_TEXT, strings.stringMode)
                    assertTrue(strings.hits.isNotEmpty())
                    val xref = worker.execute(XrefRequest("real", SymbolKind.METHOD,
                        SymbolRef("com.yyang.sample.Overloads", "echo", "(I)Ljava/lang/String;")), context()) as XrefResult
                    assertTrue(xref.references.any { it.source.memberName == "callBoth" && it.snippet?.contains("42") == true })
                    val before = worker.execute(DecompileRequest("real", className = hello), context()) as SourceResult
                    val rename = worker.execute(RenameRequest("real", target = SymbolRef(hello), newName = "WorkerRenamedHello"), context()) as RenameResult
                    assertTrue(rename.state.applied)
                    assertFalse(rename.state.persisted)
                    assertNull(rename.state.projectPath)
                    assertTrue(rename.state.revision.isNotBlank())
                    val after = worker.execute(DecompileRequest("real", className = hello), context()) as SourceResult
                    assertNotEquals(before.text.content, after.text.content)
                    assertTrue(after.text.content.contains("class WorkerRenamedHello"), after.text.content)
                    error(ErrorCode.UNSUPPORTED) {
                        worker.execute(AddCommentRequest("real", hello, "not a headless wire operation yet"), context())
                    }
                } finally { worker.close(true) }
            }
        } finally { root.clear() }
    }

    @Test fun `real local owner exposes snapshot CAS edits and regenerates source without claiming persistence`() {
        val root = sandbox()
        val input = fixture(root.directory)
        try {
            AnalysisWorkerEndpointFactory().open(CreateSessionRequest(input.toString(), deobf = false),
                context(), root.directory.resolve("owned-local")).use { endpoint ->
                val data = endpoint as ProjectDataPort
                val edits = endpoint as ProjectEditPort
                val before = data.snapshot(context())
                before.validate()
                val original = endpoint.execute(DecompileRequest("local", className = hello), context()) as SourceResult
                val mutation = edits.comment(AddCommentRequest("local", hello, "LOCAL_OWNER_COMMENT_MAGIC"), context())
                assertTrue(mutation.state.applied)
                assertFalse(mutation.state.persisted)
                assertNull(mutation.state.projectPath)
                assertEquals(1, edits.comments(ListCommentsRequest("local", hello), context()).comments.size)
                val after = data.snapshot(context())
                assertNotEquals(before.revision, after.revision)
                assertEquals(before.inputs, after.inputs)
                assertEquals("LOCAL_OWNER_COMMENT_MAGIC", after.data.comments.single().comment)
                val regenerated = endpoint.execute(DecompileRequest("local", className = hello), context()) as SourceResult
                assertNotEquals(original.text.content, regenerated.text.content)
                assertTrue(regenerated.text.content.contains("LOCAL_OWNER_COMMENT_MAGIC"))
                error(ErrorCode.CONFLICT) { data.importData(before, before.revision, context()) }
            }
        } finally { root.clear() }
    }

    @Test fun `real owned worker canceled after actual analysis is terminal and queued request never dispatches`() {
        val root = sandbox()
        val input = fixture(root.directory)
        val analyzed = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val requests = AtomicInteger()
        val jvm = JvmWorkerLauncher()
        var worker: WorkerBackend? = null
        try {
            val launcher = WorkerLauncher { spec ->
                val process = jvm.launch(spec)
                object : WorkerProcess by process {
                    override fun terminate(graceMillis: Long) {
                        try { process.terminate(graceMillis) } finally { terminated.countDown() }
                    }
                }
            }
            WorkerBackendFactory(config(), launcher, {
                val actual = HttpWorkerTransport()
                object : WorkerTransport, AutoCloseable {
                    override fun exchange(address: java.net.URI, token: String, frame: RequestFrame): CompletableFuture<CommandCompletion> {
                        val response = actual.exchange(address, token, frame)
                        if (frame.request !is BackendRequest) return response
                        requests.incrementAndGet()
                        // Fault injection delays publication AFTER real JADX/RPC succeeded, not fake analysis.
                        return response.thenCompose { analyzed.countDown(); CompletableFuture() }
                    }
                    override fun close() = actual.close()
                }
            }).use { factory ->
                SessionService(HeadlessBackendFactory { request, context -> factory.create(request, context).also { worker = it } }).use { sessions ->
                    val id = sessions.create(CreateSessionRequest(input.toString(), deobf = false), context()).sessionId
                    val cancel = CancellationSource()
                    val first = sessions.submit(SummaryRequest(id), context(cancellation = cancel))
                    assertTrue(analyzed.await(10, TimeUnit.SECONDS))
                    val queued = sessions.submit(SummaryRequest(id), context())
                    cancel.cancel()
                    val failure = assertFailsWith<ExecutionException> { first.get(3, TimeUnit.SECONDS) }
                    assertEquals(ErrorCode.CANCELLED, (failure.cause as ProtocolException).error.code)
                    assertTrue(terminated.await(5, TimeUnit.SECONDS))
                    assertFalse(worker!!.isAlive)
                    val rejected = assertFailsWith<ExecutionException> { queued.get(3, TimeUnit.SECONDS) }
                    assertEquals(ErrorCode.DISCONNECTED, (rejected.cause as ProtocolException).error.code)
                    assertEquals(1, requests.get())
                    sessions.sweep()
                    assertEquals(SessionState.CLOSED, sessions.get(id).state)
                    error(ErrorCode.DISCONNECTED) { sessions.submit(SummaryRequest(id), context()) }
                    assertTrue(sessions.closeSession(id, clearCache = true).closed)
                    assertFalse(Files.exists(worker!!.generatedCacheDirectory))
                    error(ErrorCode.CLOSED) { sessions.submit(SummaryRequest(id), context()) }
                }
            }
        } finally {
            worker?.close(true)
            root.clear()
        }
    }

    @Test fun `real child invalid input fails typed loading without orphan process`() {
        val root = sandbox()
        val jvm = JvmWorkerLauncher()
        val children = mutableListOf<WorkerProcess>()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { jvm.launch(it).also(children::add) }).use { factory ->
                error(ErrorCode.NOT_FOUND) {
                    factory.create(CreateSessionRequest(root.directory.resolve("missing.jar").toAbsolutePath().toString()), context())
                }
                assertTrue(children.none { it.isAlive })
            }
        } finally { root.clear() }
    }
}
