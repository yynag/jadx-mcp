package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxCodeComment
import jadx.api.data.impl.JadxNodeRef
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Real core and borrowed lease, NOT a real GUI factory/window test. P10 runs this centrally. */
class BorrowedRuntimeTest {
    @Test fun generationClearAndBorrowedClosePreserveHostDecompilerAndCodeData() {
        val fixture = listOf(Path.of("../app/src/test/resources/sample.jar"), Path.of("app/src/test/resources/sample.jar"))
            .first { it.toFile().isFile }.toFile()
        val context = CallContext("runtime-core", 1, System.currentTimeMillis() + 30_000)
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture); threadsCount = 1 }).use { host ->
            host.load()
            val data = JadxCodeData().apply {
                comments = listOf(JadxCodeComment(JadxNodeRef.forCls(host.searchJavaClassByOrigFullName("com.yyang.sample.HelloSample")!!), "keep"))
            }
            host.args.codeData = data
            host.reloadCodeData()
            val workspace = JadxWorkspace.borrowed(host) { 1 }
            val generation = GuiGeneration()
            val native = JadxSourceService(workspace, revision = { generation.current().toString() })
            val source = RuntimeSourceCache(native, generation)
            val request = DecompileRequest("s", className = "com.yyang.sample.HelloSample")
            assertTrue(source.decompile(request, context).text.content.isNotBlank())
            generation.invalidate()
            native.invalidate(context)
            assertEquals(CacheStatsResult(), source.stats())
            assertSame(data, host.args.codeData)
            assertEquals("keep", requireNotNull(host.args.codeData).comments.single().comment)
            workspace.close()
            assertNotNull(host.searchJavaClassByOrigFullName("com.yyang.sample.HelloSample"))
            assertSame(data, host.args.codeData)
        }
    }
}
