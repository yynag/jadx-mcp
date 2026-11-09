package com.yyang.jadxmcp.bootstrap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/** Real HTTP -> SessionService -> real isolated JVM -> real JADX sample.jar analysis, not a fake endpoint. */
class MasterRuntimeIntegrationTest {
    @Test fun realMasterCompositionLoadsAnalyzesRenamesAndDisposesOwnedWorker() {
        val buildTemp = Files.createDirectories(Path.of("build", "tmp").toAbsolutePath())
        val root = Files.createTempDirectory(buildTemp, "real-master-")
        val input = root.resolve("sample.jar")
        javaClass.getResourceAsStream("/sample.jar")!!.use { Files.copy(it, input) }
        val original = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))
        val project = root.resolve("sample.jar.jadx")
        Files.writeString(project, "private fixture project: do not discover or overwrite")
        val port = ServerSocket(0).use { it.localPort }
        val options = MasterOptions(port, "256m", "127.0.0.1")
        val config = options.workerConfig().copy(classpath = childClasspath(), startupTimeoutMillis = 30_000)
        val codec = ProtocolCodec()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { client ->
            MasterRuntime(options, config).use { runtime ->
                runtime.start()
                fun invoke(request: OperationRequest): OperationResult {
                    val params = JsonParser.parseString(codec.encodeRequest(request)).asJsonObject.getAsJsonObject("params")
                    params.addProperty("timeout_ms", 30_000)
                    val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/${request.operation.wireName}"))
                        .timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(params.toString())).build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, response.statusCode(), response.body())
                    return codec.decodeResult(request.operation, response.body())
                }
                val info = invoke(CreateSessionRequest(input.toString(), deobf = false)) as SessionInfo
                assertEquals(BackendKind.HEADLESS, info.backend)
                assertEquals(setOf(StringSearchMode.SOURCE_TEXT), info.capabilities!!.stringSearchModes)
                assertTrue((invoke(SummaryRequest(info.sessionId)) as SummaryResult).classesCount > 0)
                val hello = "com.yyang.sample.HelloSample"
                assertTrue((invoke(DecompileRequest(info.sessionId, className = hello)) as SourceResult).text.content.contains("HelloSample"))
                val renamed = invoke(RenameRequest(info.sessionId, SymbolKind.METHOD, SymbolRef(hello, "greet"), "rewrittenGreeting")) as RenameResult
                assertTrue(renamed.state.applied)
                assertFalse(renamed.state.persisted, "No owner store is configured while persistence policy is pending")
                val search = invoke(SearchRequest(info.sessionId, SearchScope.STRING, "HelloSample", stringMode = StringSearchMode.SOURCE_TEXT)) as SearchResult
                assertTrue(search.hits.isNotEmpty())
                assertTrue((invoke(CloseSessionRequest(info.sessionId, clearCache = true)) as CloseSessionResult).closed)
                assertTrue(Files.list(input.resolveSibling("sample.jar.jadx_mcp_cache")).use { it.findAny().isEmpty }, "Only owned generated worker data is removed")
            }
        }
        assertArrayEquals(original, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)))
        assertEquals("private fixture project: do not discover or overwrite", Files.readString(project))
    }

    @Test fun optionsNeedNoCacheRootAndRejectRemovedAndInvalidArguments() {
        val options = MasterOptions.parse(arrayOf("--port", "8660", "--worker-heap", "512m"))
        assertEquals(8660, options.port)
        assertEquals("com.yyang.jadxmcp.backend.AnalysisWorkerEndpointFactory", options.workerConfig().factoryClassName)
        assertEquals(8650, MasterOptions.parse(emptyArray()).port)
        assertThrows(IllegalArgumentException::class.java) { MasterOptions.parse(arrayOf("--cache-root", "x")) }
        assertThrows(IllegalArgumentException::class.java) { MasterOptions.parse(arrayOf("--port", "0")) }
        assertThrows(IllegalArgumentException::class.java) { MasterOptions.parse(arrayOf("--unknown", "x")) }
    }

    private fun childClasspath(): String {
        // Gradle test workers put their real dependency URLs in a separate classloader.
        val urls = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }
        return (System.getProperty("java.class.path").split(File.pathSeparator).asSequence() + urls)
            .distinct().joinToString(File.pathSeparator)
    }
}
