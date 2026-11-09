package com.yyang.jadxmcp.process

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.worker.OwnedWorkerCache
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkerBackendTest {
    private class FakeProcess : WorkerProcess {
        @Volatile override var isAlive = true
        @Volatile override var exitCode: Int? = null
        @Volatile override var diagnostics = ""
        val terminations = AtomicInteger()
        val terminationGracePeriods = CopyOnWriteArrayList<Long>()
        override fun terminate(graceMillis: Long) {
            terminationGracePeriods += graceMillis
            terminations.incrementAndGet()
            isAlive = false
            if (exitCode == null) exitCode = 0
        }
    }
    private fun context(id: String = "call", timeout: Long = 3_000, cancellation: CancellationSignal = CancellationSignal.NONE) =
        CallContext(id, 0, System.currentTimeMillis() + timeout, cancellation)
    private fun completion(frame: RequestFrame, result: OperationResult) =
        CommandCompletion(frame.context.requestId, frame.context.projectEpoch, frame.request.operation, result)
    private fun ready(frame: RequestFrame) = completion(frame, SessionInfo("child-id", BackendKind.HEADLESS,
        "fake.apk", SessionState.READY, capabilities = BackendCapabilities(BackendKind.HEADLESS)))
    private fun transport(action: (RequestFrame) -> CompletableFuture<CommandCompletion>) = WorkerTransport { _, _, frame ->
        when (frame.request) {
            is CreateSessionRequest -> CompletableFuture.completedFuture(ready(frame))
            is CloseSessionRequest -> CompletableFuture.completedFuture(completion(frame, CloseSessionResult("child-id", true)))
            else -> action(frame)
        }
    }
    private fun sandbox() = OwnedWorkerCache.create(Path.of("build", "worker-test-sandboxes"))
    private fun config() = WorkerProcessConfig("test.Factory", shutdownGraceMillis = 20)
    private fun request(root: Path, name: String = "fake.apk", maxHeap: String? = null): CreateSessionRequest {
        val input = root.resolve(name)
        if (!Files.exists(input)) Files.writeString(input, "fake input")
        return CreateSessionRequest(input.toString(), maxHeap = maxHeap)
    }
    private fun assertNoSessions(root: Path) {
        Files.walk(root).use { paths -> assertFalse(paths.anyMatch {
            it != root && it.fileName.toString().startsWith("session-")
        }) }
    }
    private fun error(code: ErrorCode, action: () -> Unit): ProtocolException =
        assertFailsWith<ProtocolException>(block = action).also { assertEquals(code, it.error.code) }

    @Test fun `launch is per session authenticated loopback and typed ready before use`() {
        val root = sandbox()
        val specs = mutableListOf<WorkerLaunchSpec>()
        val processes = mutableListOf<FakeProcess>()
        val secrets = mutableListOf<String>()
        try {
            val relativeClasspath = listOf("relative.jar", "libs with space/*").joinToString(java.io.File.pathSeparator)
            WorkerBackendFactory(config().copy(classpath = relativeClasspath, logLevel = com.yyang.jadxmcp.bootstrap.LogLevel.DEBUG), WorkerLauncher { spec ->
                specs += spec
                FakeProcess().also { processes += it }
            }, { WorkerTransport { address, token, frame ->
                assertEquals("127.0.0.1", address.host)
                assertEquals("/rpc", address.path)
                if (frame.request is CreateSessionRequest) secrets += token
                CompletableFuture.completedFuture(when (frame.request) {
                    is CreateSessionRequest -> ready(frame)
                    is CloseSessionRequest -> completion(frame, CloseSessionResult("child-id", true))
                    else -> completion(frame, SummaryResult(classesCount = 42))
                })
            } }).use { factory ->
                val first = factory.create(request(root.directory, "one.apk", maxHeap = "128m"), context("create-one"))
                val second = factory.create(request(root.directory, "two.apk"), context("create-two"))
                assertEquals(42, (first.execute(SummaryRequest("session-one"), context()) as SummaryResult).classesCount)
                assertEquals(2, processes.size)
                assertNotEquals(secrets[0], secrets[1])
                assertTrue(secrets.all { it.length >= 32 })
                assertTrue(specs.first().command.contains("-Xmx128m"))
                assertTrue(specs.all { "-Djadx.mcp.logLevel=DEBUG" in it.command })
                assertEquals(first.generatedCacheDirectory, specs[0].workingDirectory)
                assertEquals(second.generatedCacheDirectory, specs[1].workingDirectory)
                assertNotEquals(specs[0].workingDirectory, specs[1].workingDirectory)
                val expectedClasspath = relativeClasspath.split(java.io.File.pathSeparator).joinToString(java.io.File.pathSeparator) {
                    Path.of(it).toAbsolutePath().normalize().toString()
                }
                assertEquals(expectedClasspath, specs.first().command[specs.first().command.indexOf("-cp") + 1])
                assertTrue(specs.none { spec -> secrets.any { it in spec.command.joinToString(" ") } })
                assertTrue(specs.all { it.command.last() == "com.yyang.jadxmcp.worker.WorkerMain" })
                first.close(true)
                first.close(true)
                assertEquals(1, processes[0].terminations.get())
                assertFalse(Files.exists(first.generatedCacheDirectory))
                second.close()
                assertTrue(Files.exists(second.generatedCacheDirectory))
            }
            assertTrue(processes.none { it.isAlive })
        } finally { root.clear() }
    }

    @Test fun `startup retries connection refusal but never claims ready before handshake`() {
        val root = sandbox()
        val process = FakeProcess()
        val attempts = AtomicInteger()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, {
                WorkerTransport { _, _, frame ->
                    if (frame.request is CreateSessionRequest && attempts.incrementAndGet() < 3)
                        CompletableFuture.failedFuture(ConnectException("not listening yet"))
                    else CompletableFuture.completedFuture(if (frame.request is CreateSessionRequest) ready(frame)
                        else completion(frame, CloseSessionResult("child-id", true)))
                }
            }).use { factory ->
                factory.create(request(root.directory), context()).close()
                assertEquals(3, attempts.get())
            }
        } finally { root.clear() }
    }

    @Test fun `load failure is typed and destroys failed child and owned cache`() {
        val root = sandbox()
        val process = FakeProcess()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, {
                WorkerTransport { _, _, frame -> CompletableFuture.completedFuture(CommandCompletion(
                    frame.context.requestId, 0, frame.request.operation,
                    error = ProtocolError(ErrorCode.INTERNAL, "WORKER_LOAD_FAILED: invalid APK"))) }
            }).use { factory ->
                val e = error(ErrorCode.INTERNAL) { factory.create(request(root.directory, "bad.apk"), context()) }
                assertTrue(e.message!!.contains("WORKER_LOAD_FAILED"))
                assertFalse(process.isAlive)
                assertNoSessions(root.directory)
            }
        } finally { root.clear() }
    }

    @Test fun `deadline hard kills backend and rejects subsequent calls`() {
        val root = sandbox()
        val process = FakeProcess()
        val waiting = CompletableFuture<CommandCompletion>()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, { transport { waiting } }).use { factory ->
                val backend = factory.create(request(root.directory), context())
                error(ErrorCode.DEADLINE_EXCEEDED) { backend.execute(SummaryRequest("session"), context(timeout = 60)) }
                assertFalse(process.isAlive)
                assertTrue(waiting.isCancelled)
                // Deadline teardown must not grant a graceful wait to non-cooperative analysis.
                assertEquals(listOf(0L), process.terminationGracePeriods.toList())
                error(ErrorCode.CLOSED) { backend.execute(SummaryRequest("session"), context()) }
            }
        } finally { root.clear() }
    }

    @Test fun `cancellation interrupts wait and hard kills noncooperative child`() {
        val root = sandbox()
        val process = FakeProcess()
        val source = CancellationSource()
        val entered = CountDownLatch(1)
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, {
                transport { entered.countDown(); CompletableFuture() }
            }).use { factory ->
                val backend = factory.create(request(root.directory), context())
                Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                    val call = threads.submit<OperationResult> {
                        backend.execute(SummaryRequest("session"), context(cancellation = source))
                    }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    source.cancel()
                    val e = assertFailsWith<ExecutionException> { call.get(2, TimeUnit.SECONDS) }
                    assertEquals(ErrorCode.CANCELLED, (e.cause as ProtocolException).error.code)
                    assertFalse(process.isAlive)
                    assertEquals(listOf(0L), process.terminationGracePeriods.toList())
                }
            }
        } finally { root.clear() }
    }

    @Test fun `confirmed OOM differs from uncertain OS kill and ordinary exit`() {
        for ((exit, expected) in listOf(73 to "WORKER_OOM", 137 to "possible OS memory pressure", 72 to "WORKER_EXIT")) {
            val root = sandbox()
            val process = FakeProcess()
            try {
                WorkerBackendFactory(config(), WorkerLauncher { process }, {
                    transport { process.exitCode = exit; process.isAlive = false; CompletableFuture() }
                }).use { factory ->
                    val backend = factory.create(request(root.directory), context())
                    val e = error(ErrorCode.DISCONNECTED) { backend.execute(SummaryRequest("session"), context()) }
                    assertTrue(e.message!!.contains(expected), e.message)
                }
            } finally { root.clear() }
        }
    }

    @Test fun `late or mismatched typed result is rejected`() {
        val root = sandbox()
        val process = FakeProcess()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, {
                transport { frame -> CompletableFuture.completedFuture(CommandCompletion("wrong-id", 0,
                    frame.request.operation, SummaryResult())) }
            }).use { factory ->
                val backend = factory.create(request(root.directory), context())
                error(ErrorCode.CONFLICT) { backend.execute(SummaryRequest("session"), context()) }
            }
        } finally { root.clear() }
    }

    @Test fun `close bypasses an unresponsive analysis transport`() {
        val root = sandbox()
        val process = FakeProcess()
        val entered = CountDownLatch(1)
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, {
                transport { entered.countDown(); CompletableFuture() }
            }).use { factory ->
                val backend = factory.create(request(root.directory), context())
                Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                    val call = threads.submit<OperationResult> { backend.execute(SummaryRequest("session"), context()) }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    backend.close()
                    val e = assertFailsWith<ExecutionException> { call.get(2, TimeUnit.SECONDS) }
                    assertTrue((e.cause as ProtocolException).error.code in setOf(ErrorCode.CLOSED, ErrorCode.DISCONNECTED))
                    assertFalse(process.isAlive)
                }
            }
        } finally { root.clear() }
    }

    @Test fun `clear owned cache never follows symlink into user codeData`() {
        val root = sandbox()
        try {
            val userData = Files.createDirectory(root.directory.resolve("user-codeData"))
            val file = Files.writeString(userData.resolve("comments.json"), "user edits")
            val owned = OwnedWorkerCache.create(root.directory)
            Files.writeString(owned.directory.resolve("generated.bin"), "cache")
            Files.createSymbolicLink(owned.directory.resolve("outside"), userData.toAbsolutePath())
            owned.clear()
            assertEquals("user edits", Files.readString(file))
            assertFalse(Files.exists(owned.directory))
        } finally { root.clear() }
    }

    @Test fun `startup timeout never leaks unready process or owned directory`() {
        val root = sandbox()
        val process = FakeProcess()
        try {
            WorkerBackendFactory(config().copy(startupTimeoutMillis = 50), WorkerLauncher { process }, {
                WorkerTransport { _, _, _ -> CompletableFuture() }
            }).use { factory ->
                error(ErrorCode.DEADLINE_EXCEEDED) { factory.create(request(root.directory), context()) }
                assertFalse(process.isAlive)
                assertNoSessions(root.directory)
            }
        } finally { root.clear() }
    }

    @Test fun `invalid ready handshake cannot expose a connecting backend`() {
        val root = sandbox()
        val process = FakeProcess()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, {
                WorkerTransport { _, _, frame -> CompletableFuture.completedFuture(completion(frame,
                    SessionInfo("child", BackendKind.HEADLESS, state = SessionState.CONNECTING))) }
            }).use { factory ->
                error(ErrorCode.INTERNAL) { factory.create(request(root.directory), context()) }
                assertFalse(process.isAlive)
            }
        } finally { root.clear() }
    }

    @Test fun `launch failure is typed and removes only its generated directory`() {
        val root = sandbox()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { throw java.io.IOException("fake exec failure") }).use { factory ->
                val e = error(ErrorCode.INTERNAL) { factory.create(request(root.directory), context()) }
                assertTrue(e.message!!.contains("WORKER_START_FAILED"))
                assertNoSessions(root.directory)
            }
        } finally { root.clear() }
    }

    @Test fun `invalid input and unsafe or unwritable adjacent cache fail typed before launching`() {
        val root = sandbox()
        val launches = AtomicInteger()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { launches.incrementAndGet(); FakeProcess() }).use { factory ->
                error(ErrorCode.NOT_FOUND) { factory.create(CreateSessionRequest(root.directory.resolve("missing.apk").toString()), context()) }
                error(ErrorCode.NOT_FOUND) { factory.create(CreateSessionRequest(root.directory.toString()), context()) }
                error(ErrorCode.INVALID_ARGUMENT) { factory.create(CreateSessionRequest("bad\u0000path"), context()) }
                val request = request(root.directory)
                val cacheRoot = Path.of(request.apkPath + OwnedWorkerCache.SUFFIX)
                val userData = Files.createDirectory(root.directory.resolve("user-data"))
                val sentinel = Files.writeString(userData.resolve("keep"), "user")
                Files.createSymbolicLink(cacheRoot, userData)
                error(ErrorCode.PERSISTENCE_FAILED) { factory.create(request, context()) }
                Files.delete(cacheRoot)
                Files.createDirectory(cacheRoot)
                val permissions = Files.getPosixFilePermissions(cacheRoot)
                try {
                    Files.setPosixFilePermissions(cacheRoot, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
                    error(ErrorCode.PERSISTENCE_FAILED) { factory.create(request, context()) }
                } finally { Files.setPosixFilePermissions(cacheRoot, permissions) }
                assertEquals("user", Files.readString(sentinel))
                assertEquals(0, launches.get())
            }
        } finally { root.clear() }
    }

    @Test fun `clear cache rejects changed ownership marker and only cleans after child termination`() {
        val root = sandbox()
        val process = FakeProcess()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { process }, { transport { CompletableFuture() } }).use { factory ->
                val request = request(root.directory)
                val worker = factory.create(request, context())
                val marker = worker.generatedCacheDirectory.resolve(".jadx-mcp-owner")
                val original = Files.readString(marker)
                Files.writeString(marker, "wrong owner")
                error(ErrorCode.PERSISTENCE_FAILED) { worker.close(true) }
                assertFalse(process.isAlive)
                assertTrue(Files.isRegularFile(Path.of(request.apkPath)))
                assertTrue(Files.isDirectory(worker.generatedCacheDirectory))
                Files.writeString(marker, original)
                worker.close(true)
                assertFalse(Files.exists(worker.generatedCacheDirectory))
            }
        } finally { root.clear() }
    }
}
