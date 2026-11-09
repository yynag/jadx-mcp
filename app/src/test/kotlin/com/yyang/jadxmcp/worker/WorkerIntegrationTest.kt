package com.yyang.jadxmcp.worker

import com.yyang.jadxmcp.process.*
import com.yyang.jadxmcp.protocol.*
import java.io.File
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Public no-arg test assembly is loaded inside a REAL child JVM; no APK/JADX/GUI is claimed. */
class FakeChildEndpointFactory : WorkerEndpointFactory {
    override fun open(request: CreateSessionRequest, context: CallContext, generatedCache: Path): BackendEndpoint {
        if (Path.of(request.apkPath).fileName.toString() == "load-failure") throw IllegalArgumentException("fake invalid APK")
        if (Path.of(request.apkPath).fileName.toString() == "load-oom") throw OutOfMemoryError("fake OOM")
        if (Path.of(request.apkPath).fileName.toString() == "cwd.apk") {
            check(Path.of("").toAbsolutePath().normalize() == generatedCache.toAbsolutePath().normalize())
            Files.writeString(Path.of("relative-worker-output.txt"), "exclusive cwd")
        }
        Files.writeString(generatedCache.resolve("generated.cache"), "owned")
        return object : BackendEndpoint {
            override val capabilities = BackendCapabilities(BackendKind.HEADLESS)
            override fun execute(request: BackendRequest, context: CallContext): OperationResult {
                if (request.sessionId == "hang") Thread.sleep(60_000)
                if (request.sessionId == "oom") throw OutOfMemoryError("fake runtime OOM")
                return SummaryResult(classesCount = 123)
            }
            override fun close() = Unit
        }
    }
}

class WorkerIntegrationTest {
    private fun context(id: String = "test", timeout: Long = 15_000) = CallContext(id, 0, System.currentTimeMillis() + timeout)
    private fun sandbox() = OwnedWorkerCache.create(Path.of("build", "worker-integration-sandboxes"))
    private fun classpath(): String {
        // Gradle test workers often expose only gradle-worker.jar in java.class.path.
        val urls = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }
        return (System.getProperty("java.class.path").split(File.pathSeparator).asSequence() + urls)
            .distinct().joinToString(File.pathSeparator)
    }
    private fun config() = WorkerProcessConfig(FakeChildEndpointFactory::class.java.name,
        classpath = classpath(), defaultMaxHeap = "128m", startupTimeoutMillis = 10_000, shutdownGraceMillis = 100)
    private fun request(root: Path, name: String = "fake.apk") =
        CreateSessionRequest(Files.writeString(root.resolve(name), "fake input").toString())

    @Test fun `real child JVM typed startup execute and close leaves no live worker`() {
        val root = sandbox()
        try {
            WorkerBackendFactory(config()).use { factory ->
                val worker = factory.create(request(root.directory, "cwd.apk"), context("startup"))
                try {
                    assertTrue(worker.isAlive)
                    assertEquals(123, (worker.execute(SummaryRequest("session"), context("analysis")) as SummaryResult).classesCount)
                    assertTrue(Files.exists(worker.generatedCacheDirectory.resolve("generated.cache")))
                    assertEquals("exclusive cwd", Files.readString(worker.generatedCacheDirectory.resolve("relative-worker-output.txt")))
                } finally { worker.close(clearCache = true) }
                assertFalse(worker.isAlive)
                assertFalse(Files.exists(worker.generatedCacheDirectory))
            }
        } finally { root.clear() }
    }

    @Test fun `real child loading failure and OOM both fail startup without orphan JVM`() {
        val root = sandbox()
        val children = mutableListOf<WorkerProcess>()
        val delegate = JvmWorkerLauncher()
        try {
            WorkerBackendFactory(config(), WorkerLauncher { delegate.launch(it).also(children::add) }).use { factory ->
                val load = assertFailsWith<ProtocolException> { factory.create(request(root.directory, "load-failure"), context("bad")) }
                assertTrue(load.message!!.contains("WORKER_LOAD_FAILED"), load.message)
                val oom = assertFailsWith<ProtocolException> { factory.create(request(root.directory, "load-oom"), context("oom")) }
                assertTrue(oom.message!!.contains("WORKER_OOM"), oom.message)
                assertTrue(children.none { it.isAlive })
                Files.walk(root.directory).use { paths -> assertFalse(paths.anyMatch {
                    it != root.directory && it.fileName.toString().startsWith("session-")
                }) }
            }
        } finally { root.clear() }
    }

    @Test fun `real child noncooperative command deadline destroys JVM`() {
        val root = sandbox()
        try {
            WorkerBackendFactory(config()).use { factory ->
                val worker = factory.create(request(root.directory), context())
                try {
                    val e = assertFailsWith<ProtocolException> {
                        worker.execute(SummaryRequest("hang"), context("slow", timeout = 200))
                    }
                    assertEquals(ErrorCode.DEADLINE_EXCEEDED, e.error.code)
                    assertFalse(worker.isAlive)
                } finally { worker.close(true) }
            }
        } finally { root.clear() }
    }

    @Test fun `internal HTTP server binds loopback rejects bad auth and uses strict typed frames`() {
        val root = sandbox()
        val token = "0123456789abcdef0123456789abcdef"
        try {
            WorkerServer(0, token, FakeChildEndpointFactory(), root.directory).use { server ->
                server.start()
                assertEquals("127.0.0.1", server.address.host)
                HttpClient.newHttpClient().use { client ->
                    val unauthorized = HttpRequest.newBuilder(server.address).timeout(Duration.ofSeconds(2))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                    assertEquals(401, client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode())
                    val malformed = HttpRequest.newBuilder(server.address).timeout(Duration.ofSeconds(2))
                        .header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                    assertEquals(400, client.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode())
                }
                HttpWorkerTransport().use { transport ->
                    val frame = RequestFrame(CreateSessionRequest("fake.apk"), context("init"))
                    val completion = transport.exchange(server.address, token, frame).get(3, TimeUnit.SECONDS)
                    assertEquals(SessionState.READY, (completion.result as SessionInfo).state)
                    val bad = assertFailsWith<java.util.concurrent.ExecutionException> {
                        transport.exchange(server.address, "wrong-token", frame).get(3, TimeUnit.SECONDS)
                    }
                    assertEquals(ErrorCode.INVALID_ARGUMENT, (bad.cause as ProtocolException).error.code)
                }
            }
        } finally { root.clear() }
    }
}
