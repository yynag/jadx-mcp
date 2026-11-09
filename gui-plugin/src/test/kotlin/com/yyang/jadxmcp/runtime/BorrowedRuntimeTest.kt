package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxCodeComment
import jadx.api.data.impl.JadxNodeRef
import jadx.core.dex.attributes.AFlag
import com.yyang.jadxmcp.project.NativeGuiCodeDataBinding
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real core and borrowed lease; this does not construct a GUI factory or window. */
class BorrowedRuntimeTest {
    @Test fun invalidationCannotDeepUnloadWhileNativeJavaClassReloadOwnsItsMonitor() {
        val fixture = listOf(Path.of("../app/src/test/resources/sample.jar"), Path.of("app/src/test/resources/sample.jar"))
            .first { it.toFile().isFile }.toFile()
        val context = CallContext("native-reload-boundary", 1, System.currentTimeMillis() + 30_000)
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture); threadsCount = 1 }).use { host ->
            host.load()
            val cls = requireNotNull(host.searchJavaClassByOrigFullName("com.yyang.sample.HelloSample"))
            val workspace = JadxWorkspace.borrowed(host) { 1 }
            val source = JadxSourceService(workspace)
            val actions = listOf<() -> Unit>(
                { NativeGuiCodeDataBinding.apply(host, JadxCodeData(), context) },
                { source.invalidate(context) }
            )
            for (action in actions) for (nativeMonitor in listOf(cls, cls.classNode, cls.classNode.classInfo)) {
                cls.code // Native RenameService's JClass.reload ultimately owns this JavaClass monitor.
                cls.classNode.add(AFlag.DONT_RENAME)
                val entered = CountDownLatch(1)
                val finished = CompletableFuture<Unit>()
                val worker = Thread {
                    entered.countDown()
                    try { action(); finished.complete(Unit) }
                    catch (failure: Throwable) { finished.completeExceptionally(failure) }
                }
                try {
                    synchronized(nativeMonitor) {
                        worker.start()
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        // Observe the lock boundary, not elapsed time: old unload runs to completion
                        // here despite the host's monitor, new unload must block BEFORE deepUnload.
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (worker.state != Thread.State.BLOCKED && !finished.isDone && System.nanoTime() < deadline)
                            Thread.onSpinWait()
                        assertEquals(Thread.State.BLOCKED, worker.state, "Invalidation bypassed native reload monitor")
                        assertFalse(finished.isDone)
                        assertTrue(cls.classNode.contains(AFlag.DONT_RENAME), "deepUnload mutated attributes before owning native monitor")
                    }
                } finally {
                    worker.join(5_000)
                    assertFalse(worker.isAlive, "Native reload lock order deadlocked")
                }
                finished.get(5, TimeUnit.SECONDS)
            }
            workspace.close()
        }
    }

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
