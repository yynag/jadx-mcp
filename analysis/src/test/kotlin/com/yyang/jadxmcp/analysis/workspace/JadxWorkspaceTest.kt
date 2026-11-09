package com.yyang.jadxmcp.analysis.workspace

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class JadxWorkspaceTest {
    private fun context(cancellation: CancellationSignal = CancellationSignal.NONE, timeout: Long = 60_000) =
        CallContext("workspace-test", 0, System.currentTimeMillis() + timeout, cancellation)
    private fun args() = JadxArgs().apply { inputFiles = listOf(File("../app/src/test/resources/sample.jar")) }

    @Test
    fun ownershipAndEpochAreEnforced() {
        val ownedClosed = AtomicBoolean()
        val owned = JadxWorkspace.owned(args(), context())
        owned.withDecompiler(context()) { it.addCloseable(Closeable { ownedClosed.set(true) }) }
        owned.close()
        owned.close()
        assertTrue(ownedClosed.get())
        assertEquals(ErrorCode.CLOSED, assertThrows(ProtocolException::class.java) {
            owned.withDecompiler(context()) { it.classes }
        }.error.code)
        val native = JadxDecompiler(args())
        native.load()
        val hostClosed = AtomicBoolean()
        native.addCloseable(Closeable { hostClosed.set(true) })
        val epoch = AtomicLong()
        val borrowed = JadxWorkspace.borrowed(native, epoch::get)
        try {
            assertEquals(WorkspaceOwnership.BORROWED, borrowed.ownership)
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                borrowed.withDecompiler(context()) { epoch.incrementAndGet() }
            }.error.code)
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                borrowed.withDecompiler(context().copy(projectEpoch = 1)) { error("Old native project must not be reused") }
            }.error.code)
            borrowed.close()
            assertFalse(hostClosed.get())
            assertTrue(native.classes.first().code.isNotBlank())
        } finally { native.close() }
        assertTrue(hostClosed.get())
    }

    @Test
    fun cancellationDoesNotReleaseRunningNativeLeaseAndQueuedDeadlineDoesNotStartAction() {
        val native = JadxDecompiler(args())
        native.load()
        val workspace = JadxWorkspace.borrowed(native) { 0 }
        val executor = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CancellationSource()
        try {
            val first = executor.submit<ErrorCode> {
                try {
                    workspace.withDecompiler(context(cancelled)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    error("Expected cancellation")
                } catch (failure: ProtocolException) { failure.error.code }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            cancelled.cancel()
            val secondEntered = AtomicBoolean()
            val waiting = executor.submit<ErrorCode> {
                try {
                    workspace.withDecompiler(context(timeout = 100)) { secondEntered.set(true) }
                    error("Expected deadline")
                } catch (failure: ProtocolException) { failure.error.code }
            }
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, waiting.get(5, TimeUnit.SECONDS))
            assertFalse(first.isDone)
            assertFalse(secondEntered.get())
            val closing = executor.submit { workspace.close() }
            // Closing and the third queued request race; neither may enter the active native instance.
            val rejected = assertThrows(ProtocolException::class.java) {
                workspace.withDecompiler(context(timeout = 100)) { secondEntered.set(true) }
            }.error.code
            assertTrue(rejected == ErrorCode.CLOSED || rejected == ErrorCode.DEADLINE_EXCEEDED)
            assertFalse(secondEntered.get())
            release.countDown()
            assertEquals(ErrorCode.CANCELLED, first.get(5, TimeUnit.SECONDS))
            closing.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            workspace.close()
            native.close()
        }
    }
}
