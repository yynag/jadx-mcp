package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.HeadlessBackendFactory
import com.yyang.jadxmcp.session.SessionPolicy
import com.yyang.jadxmcp.session.SessionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GuiLateCompletionIntegrationTest {
    private class ManualRuntime : BridgeRuntime, BridgeClock {
        data class Task(val lane: BridgeLane, val at: Long, val action: () -> Unit)
        val time = AtomicLong(1_000)
        private val tasks = mutableListOf<Task>()
        override fun nowMillis() = time.get()
        override fun schedule(lane: BridgeLane, delayMillis: Long, action: () -> Unit) {
            tasks.add(Task(lane, time.get() + delayMillis, action))
        }
        fun count(lane: BridgeLane) = tasks.count { it.lane == lane }
        fun run(lane: BridgeLane) {
            val task = tasks.filter { it.lane == lane }.minByOrNull { it.at } ?: error("No task for $lane")
            tasks.remove(task)
            time.updateAndGet { maxOf(it, task.at) }
            task.action()
        }
        override fun close() { tasks.clear() }
    }

    private class RecordingPort(private val service: SessionService) : GuiChannelPort by service {
        private val codec = GuiTransportCodec()
        val leases = mutableListOf<GuiLease>()
        val completions = mutableListOf<CommandCompletion>()
        val failures = mutableListOf<ErrorCode>()
        val unregistered = mutableListOf<GuiLease>()
        override fun register(registration: GuiRegistration) = service.register(registration).also { leases.add(it) }
        override fun poll(lease: GuiLease, context: CallContext): PollResult =
            // Real wire serialization must detach server cancellation from the GUI context.
            codec.decodePollResult(codec.encodePollResult(service.poll(lease, context)))
        override fun complete(lease: GuiLease, completion: CommandCompletion) {
            val decoded = codec.decodeCompletion(codec.encodeCompletion(GuiCompletionRequest(lease, completion)))
            completions.add(decoded.completion)
            try { service.complete(decoded.lease, decoded.completion) }
            catch (e: ProtocolException) { failures.add(e.error.code); throw e }
        }
        override fun unregister(lease: GuiLease) {
            service.unregister(lease)
            unregistered.add(lease)
        }
    }

    @Test fun maintenanceBeforeLateReportDisablesAndUnregistersOnCancelled() = lateReport(maintenanceFirst = true)

    @Test fun lateReportBeforeMaintenanceReconnectsOnDeadlineExceeded() = lateReport(maintenanceFirst = false)

    private fun lateReport(maintenanceFirst: Boolean) {
        val runtime = ManualRuntime()
        // Keep automatic maintenance outside the bounded test; only the explicit sweep orders it.
        val policy = SessionPolicy(sweepMillis = 600_000)
        SessionService(HeadlessBackendFactory { _, _ -> error("No headless backend expected") },
            policy, runtime.time::get).use { service ->
            val port = RecordingPort(service)
            val status = BridgeStatusModel({ runtime.nowMillis() })
            var executions = 0
            val endpoint = object : BackendEndpoint {
                override val capabilities = BackendCapabilities(BackendKind.GUI,
                    listOf(OperationCapability(Operation.META_SUMMARY)))
                override fun execute(request: BackendRequest, context: CallContext): OperationResult {
                    executions++
                    context.checkActive(runtime.nowMillis())
                    return SummaryResult(classesCount = 42)
                }
                override fun close() { fail<Unit>("Borrowed endpoint must not be closed") }
            }
            val client = GuiBridgeClient(port, endpoint, "late-fixture", "Fake GUI", runtime = runtime,
                clock = runtime, policy = BridgePolicy(initialBackoffMillis = 10, maxBackoffMillis = 40), observer = status)
            try {
                client.enable()
                runtime.run(BridgeLane.CHANNEL)
                val lease = port.leases.single()
                val future = service.submit(SummaryRequest(lease.sessionId), CallContext("caller", 0, 2_000))
                runtime.run(BridgeLane.CHANNEL) // Real ReverseGuiEndpoint admits and offers the call.
                runtime.run(BridgeLane.EXECUTION) // Finish before deadline; hold the scheduled report.
                assertEquals(1, executions)
                assertFalse(future.isDone)
                assertEquals(1, runtime.count(BridgeLane.CHANNEL))
                runtime.time.set(2_001)

                // These tests expose existing classification/recovery differences, not a fix.
                if (maintenanceFirst) {
                    service.sweep()
                    assertFailure(ErrorCode.DEADLINE_EXCEEDED, future)
                }
                runtime.run(BridgeLane.CHANNEL)
                val expected = if (maintenanceFirst) ErrorCode.CANCELLED else ErrorCode.DEADLINE_EXCEEDED
                assertEquals(listOf(expected), port.failures)
                assertEquals(SummaryResult(classesCount = 42), port.completions.single().result)
                assertFailure(ErrorCode.DEADLINE_EXCEEDED, future)
                assertEquals(1, executions)
                assertEquals(1, port.leases.size)
                assertTrue(port.unregistered.isEmpty())
                assertEquals(1, runtime.count(BridgeLane.CLEANUP))

                if (maintenanceFirst) {
                    assertEquals(BridgeState.DISABLED, client.state)
                    assertTrue(status.snapshot().reason.startsWith("Error: CANCELLED"))
                    assertEquals(0, runtime.count(BridgeLane.CHANNEL))
                } else {
                    assertEquals(BridgeState.RECONNECTING, client.state)
                    assertEquals("Retry in 10ms", status.snapshot().reason)
                    assertEquals(1, runtime.count(BridgeLane.CHANNEL))
                }
                runtime.run(BridgeLane.CLEANUP)
                assertEquals(listOf(lease), port.unregistered)
                assertTrue(service.list().sessions.isEmpty())
                if (maintenanceFirst) {
                    client.enable()
                    assertEquals(BridgeState.DISABLED, client.state)
                    assertEquals(0, runtime.count(BridgeLane.CHANNEL))
                } else {
                    runtime.run(BridgeLane.CHANNEL)
                    assertEquals(BridgeState.CONNECTED, client.state)
                    assertEquals(2, port.leases.size)
                    assertNotEquals(lease, port.leases.last())
                    assertNotEquals(lease.token, port.leases.last().token)
                    assertEquals(port.leases.last().sessionId, service.list().sessions.single().sessionId)
                }
                assertEquals(1, executions, "Recovery must not replay execution")
                assertEquals(1, port.completions.size, "Recovery must not replay completion")
                assertFailure(ErrorCode.DEADLINE_EXCEEDED, future)
            } finally { client.close() }
        }
    }

    private fun assertFailure(code: ErrorCode, future: CompletableFuture<OperationResult>) {
        val failure = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
        assertEquals(code, (failure.cause as ProtocolException).error.code)
        assertTrue(future.isCompletedExceptionally)
        assertFalse(future.isCancelled)
    }
}
