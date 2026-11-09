package com.yyang.jadxmcp.ui

import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.runtime.GuiGeneration
import com.yyang.jadxmcp.runtime.RuntimeSourceCache
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Files
import java.io.File
import javax.tools.ToolProvider

class GuiQueryAdapterTest {
    private fun context() = CallContext("gui-query", 1, System.currentTimeMillis() + 30_000)

    @Test fun exactMethodIdentitySurvivesStalePositionsAliasesInnerClassesAndCacheHits() {
        val output = Files.createTempDirectory(File("build").toPath(), "method-identity-").toFile()
        val sourceFile = File(requireNotNull(javaClass.getResource("/source/MethodIdentity.java")).toURI())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "11", "-d", output.absolutePath, sourceFile.absolutePath))
        JadxDecompiler(JadxArgs().apply {
            inputFiles = output.walkTopDown().filter { it.extension == "class" }.toList()
            isDeobfuscationOn = false; threadsCount = 1
        }).use { d ->
            d.load()
            val workspace = JadxWorkspace.borrowed(d) { 1 }
            try {
                val generation = GuiGeneration()
                val native = JadxSourceService(workspace, { generation.current().toString() })
                val cached = RuntimeSourceCache(native, generation)
                val metadata = object : MetadataService {
                    override fun summary(request: SummaryRequest, context: CallContext) = SummaryResult(mainPackage = "regression")
                    override fun classes(request: ClassRequest, context: CallContext): ClassResult = error("unused")
                }
                val query = GuiQueryAdapter(workspace, metadata, cached, cached::stats, { 1 }) { generation.invalidate() }
                val cls = requireNotNull(d.searchJavaClassByOrigFullName("regression.MethodIdentity"))
                cls.codeInfo
                val string = cls.methods.single { it.methodNode.methodInfo.shortId == "echo(Ljava/lang/String;)Ljava/lang/String;" }
                val adjacent = cls.methods.single { it.name == "adjacent" }
                string.methodNode.defPosition = adjacent.defPos
                fun verify(owner: String?, name: String, signature: String?, marker: String) {
                    repeat(3) { index ->
                        val result = query.methodByName(MethodByNameRequest("s", name, owner, signature, slim = index != 1), context())
                        assertEquals(name, result.methodName)
                        if (signature != null) assertTrue(requireNotNull(result.methodSignature).endsWith(signature), result.toString())
                        assertTrue(result.text.content.contains(marker), result.text.content)
                        listOf("ADJACENT_ONLY", "INTEGER_ONLY", "STRING_ONLY", "INNER_ONLY", "AFTER_ONLY", "UNIQUE_ONLY")
                            .filter { it != marker }.forEach { assertFalse(result.text.content.contains(it), result.text.content) }
                    }
                }
                verify("regression.MethodIdentity", "echo", "(Ljava/lang/String;)Ljava/lang/String;", "STRING_ONLY")
                verify("regression.MethodIdentity", "echo", "(I)Ljava/lang/String;", "INTEGER_ONLY")
                verify("regression.MethodIdentity\$Inner", "echo", "(Ljava/lang/String;)Ljava/lang/String;", "INNER_ONLY")
                verify(null, "unique", null, "UNIQUE_ONLY")
                assertTrue(cached.stats().hits > 0)
                assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
                    query.methodByName(MethodByNameRequest("s", "echo"), context())
                }.error.code)
                assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
                    query.methodByName(MethodByNameRequest("s", "echo", "regression.MethodIdentity"), context())
                }.error.code)
                string.methodNode.methodInfo.alias = "renamedEcho"
                cls.classNode.classInfo.changeShortName("RenamedIdentity")
                generation.invalidate()
                verify("regression.RenamedIdentity", "renamedEcho", "(Ljava/lang/String;)Ljava/lang/String;", "STRING_ONLY")
            } finally { workspace.close() }
        }
    }
    @Test fun realBorrowedCoreQueriesDelegateSourceAndKeepExactMethodIdentity() {
        val fixture = listOf(Path.of("../app/src/test/resources/sample.jar"), Path.of("app/src/test/resources/sample.jar"))
            .first { it.toFile().isFile }.toFile()
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture); threadsCount = 1 }).use { d ->
            d.load()
            val workspace = JadxWorkspace.borrowed(d) { 1 }
            val metadata = object : MetadataService {
                override fun summary(request: SummaryRequest, context: CallContext) = SummaryResult(mainPackage = "com.yyang.sample")
                override fun classes(request: ClassRequest, context: CallContext): ClassResult = error("not used")
            }
            val stats = CacheStatsResult(entries = 4, hits = 5)
            var cleared = false
            val query = GuiQueryAdapter(workspace, metadata, JadxSourceService(workspace), { stats }, { 1 }) { cleared = true }
            val names = query.appClassNames(AppClassNamesRequest("s"), context()).classes
            assertTrue(names.contains("com.yyang.sample.HelloSample"))
            val method = query.methodByName(MethodByNameRequest("s", "add", "com.yyang.sample.HelloSample"), context())
            assertEquals("add", method.methodName)
            assertTrue(method.text.content.contains("return"))
            assertTrue(query.appClassCode(AppClassCodeRequest("s", PageRequest(count = 1)), context()).classes.size <= 1)
            assertTrue(query.packageTree(PackageTreeRequest("s"), context()).totalClasses > 0)
            assertEquals(stats, query.cacheStats(CacheStatsRequest("s"), context()))
            assertTrue(query.clearCache(ClearCacheRequest("s"), context()).cleared)
            assertTrue(cleared)
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProtocolException::class.java) {
                query.methodByName(MethodByNameRequest("s", "absent"), context())
            }.error.code)
            workspace.close()
        }
    }
}
