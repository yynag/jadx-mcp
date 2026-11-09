package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GuiBridgeClientTest {
    private class ManualRuntime : BridgeRuntime, BridgeClock {
        data class Task(val lane: BridgeLane, val at: Long, val action: () -> Unit)
        var now = 1_000L
        var closed = false
        val tasks = mutableListOf<Task>()
        override fun nowMillis() = now
        override fun schedule(lane: BridgeLane, delayMillis: Long, action: () -> Unit) {
            check(!closed)
            tasks.add(Task(lane, now + delayMillis, action))
        }
        fun run(lane: BridgeLane) {
            val task = tasks.filter { it.lane == lane }.minByOrNull { it.at } ?: error("No task for $lane")
            tasks.remove(task)
            now = maxOf(now, task.at)
            task.action()
        }
        override fun close() { closed = true }
    }

    private class Master : GuiChannelPort {
        val registrations = mutableListOf<GuiRegistration>()
        val leases = mutableListOf<GuiLease>()
        val completions = mutableListOf<Pair<GuiLease, CommandCompletion>>()
        val unregistered = mutableListOf<GuiLease>()
        val commands = ArrayDeque<PollResult>()
        var beats = 0
        var registerFailures = 0
        var pollFailure = false
        var heartbeatFailure = false
        var completionFailure = false
        var rejectActiveInstance = false
        private val activeLeases = mutableSetOf<GuiLease>()
        var onRegister: () -> Unit = {}
        var onPoll: () -> Unit = {}
        var onHeartbeat: () -> Unit = {}
        var onComplete: () -> Unit = {}
        override fun register(registration: GuiRegistration): GuiLease {
            registrations.add(registration)
            if (registerFailures-- > 0) throw java.io.IOException("offline")
            if (rejectActiveInstance && activeLeases.isNotEmpty())
                throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "GUI instance already registered"))
            onRegister()
            return GuiLease("s${registrations.size}", "token${registrations.size}").also {
                leases.add(it)
                activeLeases.add(it)
            }
        }
        override fun poll(lease: GuiLease, context: CallContext): PollResult {
            onPoll()
            if (pollFailure) throw java.io.IOException("connection lost")
            return commands.pollFirst() ?: PollResult(PollStatus.IDLE)
        }
        override fun complete(lease: GuiLease, completion: CommandCompletion) {
            onComplete()
            if (completionFailure) throw java.io.IOException("delivery uncertain")
            completions.add(lease to completion)
        }
        override fun heartbeat(lease: GuiLease, projectEpoch: Long) {
            onHeartbeat()
            if (heartbeatFailure) throw java.io.IOException("heartbeat lost")
            beats++
        }
        override fun unregister(lease: GuiLease) { unregistered.add(lease); activeLeases.remove(lease) }
    }

    private class Endpoint : BackendEndpoint {
        override val capabilities = BackendCapabilities(BackendKind.GUI,
            listOf(OperationCapability(Operation.META_SUMMARY)))
        val calls = mutableListOf<CallContext>()
        var closed = false
        var action: (CallContext) -> OperationResult = { SummaryResult(classesCount = 7) }
        override fun execute(request: BackendRequest, context: CallContext): OperationResult {
            calls.add(context)
            return action(context)
        }
        override fun close() { closed = true }
    }

    private class Fixture(diagnostics: GuiDiagnosticLog? = null) {
        val runtime = ManualRuntime()
        val master = Master()
        val endpoint = Endpoint()
        val status = BridgeStatusModel({ runtime.now })
        val client = GuiBridgeClient(master, endpoint, "instance", "fake GUI", runtime = runtime, clock = runtime,
            policy = BridgePolicy(initialBackoffMillis = 10, maxBackoffMillis = 40), observer = status,
            diagnostics = diagnostics)
        fun connect() { client.enable(); runtime.run(BridgeLane.CHANNEL) }
        fun command(id: String = "r1", session: String = "s1", epoch: Long = 0, deadline: Long = 100_000) {
            master.commands.add(PollResult(PollStatus.COMMAND,
                RequestFrame(SummaryRequest(session), CallContext(id, epoch, deadline))))
        }
        fun execute() { runtime.run(BridgeLane.CHANNEL); runtime.run(BridgeLane.EXECUTION); runtime.run(BridgeLane.CHANNEL) }
    }

    @Test fun diagnosticToggleAndHandlerFailureNeverRestartOrDisableChannel(
        @org.junit.jupiter.api.io.TempDir root: java.nio.file.Path,
    ) {
        val log = GuiDiagnosticLog(directory = { root })
        val f = Fixture(log)
        f.connect()
        assertTrue(log.setEnabled(true))
        assertTrue(log.setEnabled(false))
        assertTrue(log.setEnabled(true))
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertEquals(1, f.master.registrations.size)
        assertTrue(f.master.unregistered.isEmpty())
        val field = GuiDiagnosticLog::class.java.getDeclaredField("handler").apply { isAccessible = true }
        (field.get(log) as java.util.logging.FileHandler).errorManager.error(null, java.io.IOException("private"), 1)
        f.command()
        f.execute()
        assertEquals(1, f.endpoint.calls.size)
        assertEquals(1, f.master.completions.size)
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertEquals(1, f.master.registrations.size)
        f.client.close(); log.close()
    }

    @Test fun diagnosticsCaptureExecutionDeliveryDisableReconnectAndUnregisterSources(
        @org.junit.jupiter.api.io.TempDir root: java.nio.file.Path,
    ) {
        val log = GuiDiagnosticLog(directory = { root })
        log.setEnabled(true)
        val f = Fixture(log)
        f.connect()
        f.endpoint.action = { throw IllegalStateException("SOURCE_SENTINEL") }
        f.command()
        f.master.onComplete = { throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "TOKEN_SENTINEL")) }
        f.execute()
        assertEquals(BridgeState.DISABLED, f.client.state)
        f.runtime.run(BridgeLane.CLEANUP)
        assertEquals(1, f.master.unregistered.size)
        f.master.onComplete = {}
        assertTrue(f.client.reconnect())
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertEquals(2, f.master.registrations.size)
        assertEquals(1, f.endpoint.calls.size) // No replay when reconnecting.
        f.client.close()
        log.close()
        val text = java.nio.file.Files.list(root).use { paths ->
            paths.filter { it.toString().endsWith(".log") }.toList().joinToString("\n") { java.nio.file.Files.readString(it) }
        }
        listOf("event=execution_failed", "stage=endpoint_execute", "event=completion_delivery_failed",
            "event=generation_disabled", "source=terminal_failure", "event=unregister_finished", "event=reconnect").forEach {
            assertTrue(text.contains(it), it)
        }
        assertFalse(text.contains("SOURCE_SENTINEL"))
        assertFalse(text.contains("TOKEN_SENTINEL"))
    }

    @Test fun projectNamesAndEpochsAreCapturedByEachRegistrationGeneration() {
        val f = Fixture()
        f.client.updateProject(1, "app.apk")
        f.master.onRegister = {
            f.master.onRegister = {}
            f.client.updateProject(2, "base.apk (+2 files)")
        }
        f.connect()
        assertEquals(GuiLease("s1", "token1"), f.master.unregistered.single())
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(listOf(1L to "app.apk", 2L to "base.apk (+2 files)"),
            f.master.registrations.map { it.projectEpoch to it.name })
        assertEquals(BridgeState.CONNECTED, f.client.state)
    }

    @Test fun sameEpochNameUpdateRetiresQueuedRegistrationAndRetriesWithThatName() {
        val f = Fixture()
        f.client.enable()
        f.client.updateProject(0, "app.apkm")
        f.runtime.run(BridgeLane.CHANNEL) // retired registration never reaches the transport
        assertTrue(f.master.registrations.isEmpty())
        f.master.registerFailures = 1
        f.runtime.run(BridgeLane.CHANNEL)
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(listOf(0L to "app.apkm", 0L to "app.apkm"),
            f.master.registrations.map { it.projectEpoch to it.name })
    }

    @Test fun unavailableProjectFencesDelayedEnableReconnectAndOldRegistration() {
        val f = Fixture(); f.client.enable()
        f.client.suspendProject(1)
        f.client.enable()
        assertFalse(f.client.reconnect())
        f.runtime.run(BridgeLane.CHANNEL)
        assertTrue(f.master.registrations.isEmpty())
        assertEquals(1L, f.status.snapshot().epoch)
        assertThrows(IllegalArgumentException::class.java) { f.client.updateProject(0, "old.apk") }
        f.client.updateProject(1, "loaded.apk")
        f.connect()
        assertEquals("loaded.apk", f.master.registrations.single().name)
        f.command(epoch = 1); f.execute()
        assertEquals(1, f.status.snapshot().requests30s)
        f.client.suspendProject(2)
        f.client.enable()
        f.runtime.run(BridgeLane.CHANNEL) // old poll cannot register the closed project
        f.runtime.run(BridgeLane.HEARTBEAT)
        assertEquals(1, f.master.registrations.size)
        assertEquals(0, f.status.snapshot().requests30s)
    }

    @Test fun blankProjectNameIsRejectedWithoutChangingCurrentProject() {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) { f.client.updateProject(1, " ") }
        f.connect()
        assertEquals(0L to "fake GUI", f.master.registrations.single().let { it.projectEpoch to it.name })
    }

    @Test fun replacementRegistrationWaitsForOldLeaseCleanupWithoutBlockingCaller() {
        val f = Fixture(); f.master.rejectActiveInstance = true; f.connect()
        f.client.updateProject(1, "next.apk")
        f.runtime.run(BridgeLane.CHANNEL) // stale poll
        f.runtime.run(BridgeLane.CHANNEL) // cleanup lane has not acknowledged the old lease
        assertEquals(1, f.master.registrations.size)
        assertEquals(BridgeState.CONNECTING, f.client.state)
        f.runtime.run(BridgeLane.CLEANUP)
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals("next.apk", f.master.registrations.last().name)
        assertEquals(BridgeState.CONNECTED, f.client.state)
    }

    @Test fun replacementBridgeWaitsForRetiredPredecessorAndItsInFlightRegistration() {
        val f = Fixture()
        val nextRuntime = ManualRuntime()
        val next = GuiBridgeClient(f.master, f.endpoint, "instance", "next.apk", runtime = nextRuntime,
            clock = nextRuntime, previousRetired = f.client::isRegistrationRetired)
        f.master.onRegister = {
            f.master.onRegister = {}
            f.client.close()
            next.enable()
            nextRuntime.run(BridgeLane.CHANNEL)
            assertEquals(1, f.master.registrations.size)
            assertFalse(f.client.isRegistrationRetired())
        }
        f.connect()
        assertTrue(f.client.isRegistrationRetired())
        nextRuntime.run(BridgeLane.CHANNEL)
        assertEquals(listOf("fake GUI", "next.apk"), f.master.registrations.map { it.name })
        next.close(); nextRuntime.run(BridgeLane.CLEANUP)
        assertTrue(next.isRegistrationRetired())
    }

    @Test fun replacementBridgeWaitsForAcceptedPredecessorLeaseToUnregister() {
        val f = Fixture(); f.master.rejectActiveInstance = true; f.connect()
        f.client.close()
        val nextRuntime = ManualRuntime()
        val next = GuiBridgeClient(f.master, f.endpoint, "instance", "next.apk", runtime = nextRuntime,
            clock = nextRuntime, previousRetired = f.client::isRegistrationRetired)
        next.enable(); nextRuntime.run(BridgeLane.CHANNEL)
        assertEquals(1, f.master.registrations.size)
        assertFalse(f.client.isRegistrationRetired())
        f.runtime.run(BridgeLane.CLEANUP)
        nextRuntime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.CONNECTED, next.state)
        assertEquals("next.apk", f.master.registrations.last().name)
        next.close(); nextRuntime.run(BridgeLane.CLEANUP)
    }

    @Test fun identicalFilenamesKeepShortNamesAndDistinctInstanceIdentities() {
        val master = Master()
        val runtimes = List(2) { ManualRuntime() }
        val clients = runtimes.mapIndexed { index, runtime ->
            GuiBridgeClient(master, Endpoint(), "instance-$index", "app.apk", runtime = runtime, clock = runtime)
        }
        clients.forEachIndexed { index, client -> client.enable(); runtimes[index].run(BridgeLane.CHANNEL) }
        assertEquals(listOf("app.apk", "app.apk"), master.registrations.map { it.name })
        assertEquals(2, master.registrations.map { it.instanceId }.toSet().size)
        clients.forEachIndexed { index, client -> client.close(); runtimes[index].run(BridgeLane.CLEANUP) }
    }

    @Test fun registrationAndCompletionPreserveProtocolCorrelationAndCapabilities() {
        val f = Fixture(); f.connect(); f.command(); f.execute()
        assertEquals(f.endpoint.capabilities, f.master.registrations.single().capabilities)
        val (lease, result) = f.master.completions.single()
        assertEquals(GuiLease("s1", "token1"), lease)
        assertEquals("r1", result.requestId)
        assertEquals(0L, result.projectEpoch)
        assertEquals(Operation.META_SUMMARY, result.operation)
        assertEquals(SummaryResult(classesCount = 7), result.result)
        f.client.close()
        assertFalse(f.endpoint.closed)
    }

    @Test fun businessReceiptCountsOnceAndNeverCountsNetworkMaintenanceOrDuplicate() {
        val f = Fixture(); f.connect()
        f.runtime.run(BridgeLane.HEARTBEAT); f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(0, f.status.snapshot().requests30s)
        f.command(); f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(1, f.status.snapshot().requests30s) // receipt, not successful completion
        f.runtime.run(BridgeLane.EXECUTION); f.runtime.run(BridgeLane.CHANNEL)
        f.command(); f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(1, f.status.snapshot().requests30s)
        assertTrue(f.status.snapshot().reason.startsWith("Error:"))
        f.runtime.now += 30_000
        assertEquals(0, f.status.snapshot().requests30s)
    }

    @Test fun lostLeaseAfterMasterRestartReconnectsWithoutCommandReplay() {
        val f = Fixture(); f.connect(); f.command(); f.execute()
        f.master.onHeartbeat = { throw ProtocolException(ProtocolError(ErrorCode.NOT_FOUND, "Unknown session")) }
        f.runtime.run(BridgeLane.HEARTBEAT)
        assertEquals(BridgeState.RECONNECTING, f.client.state)
        assertEquals(0, f.status.snapshot().requests30s)
        f.master.onHeartbeat = {}
        f.runtime.run(BridgeLane.CLEANUP)
        f.runtime.run(BridgeLane.CHANNEL) // old poll, fenced
        f.runtime.run(BridgeLane.CHANNEL) // fresh registration
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertEquals(1, f.endpoint.calls.size)
        assertEquals(2, f.master.registrations.size)
    }

    @Test fun unknownRegistrationIsTerminalNotAnInfiniteRetry() {
        val f = Fixture()
        f.master.onRegister = { throw ProtocolException(ProtocolError(ErrorCode.NOT_FOUND, "Wrong endpoint")) }
        f.connect()
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertTrue(f.status.snapshot().reason.startsWith("Error: NOT_FOUND"))
        assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.CHANNEL })
    }

    @Test fun onlyExplicitReconnectRecoversTerminalProtocolErrorAndRetiresOldLease() {
        val f = Fixture(); f.connect()
        val originalLease = GuiLease("s1", "token1")
        f.master.onPoll = { throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "bad registration")) }
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertTrue(f.status.snapshot().reason.startsWith("Error: INVALID_ARGUMENT"))

        f.client.enable() // Lifecycle enable is not an implicit acknowledgement of a terminal error.
        assertEquals(1, f.master.registrations.size)
        assertTrue(f.client.reconnect())
        assertEquals(BridgeState.CONNECTING, f.client.state)
        f.runtime.run(BridgeLane.CLEANUP)
        assertEquals(listOf(originalLease), f.master.unregistered)
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(2, f.master.registrations.size)
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertTrue(f.master.completions.isEmpty())
    }

    @Test fun heartbeatRunsIndependentlyWhileExecutionHasNotFinished() {
        val f = Fixture(); f.connect(); f.command()
        f.endpoint.action = {
            f.runtime.run(BridgeLane.HEARTBEAT)
            f.runtime.run(BridgeLane.HEARTBEAT)
            assertEquals(2, f.master.beats)
            assertTrue(f.master.completions.isEmpty())
            SummaryResult()
        }
        f.execute()
        assertEquals(1, f.endpoint.calls.size)
    }

    @Test fun noSecondPollUntilFirstExecutionAndReportFinish() {
        val f = Fixture(); f.connect(); f.command(); f.command("r2")
        f.runtime.run(BridgeLane.CHANNEL)
        assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.CHANNEL })
        f.runtime.run(BridgeLane.EXECUTION)
        f.runtime.run(BridgeLane.CHANNEL)
        f.runtime.run(BridgeLane.CHANNEL)
        f.runtime.run(BridgeLane.EXECUTION)
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(listOf("r1", "r2"), f.endpoint.calls.map { it.requestId })
    }

    @Test fun disableIsStickyEvenIfQueuedWorkOrHeartbeatRunsLater() {
        val f = Fixture(); f.connect(); f.client.disable()
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.HEARTBEAT)
        f.runtime.run(BridgeLane.CLEANUP)
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertEquals(1, f.master.registrations.size)
        assertEquals(0, f.master.beats)
        assertEquals(1, f.master.unregistered.size)
        f.connect()
        assertEquals(BridgeState.CONNECTED, f.client.state)
    }

    @Test fun registrationFinishingAfterUnloadReleasesLeaseWithoutReactivation() {
        val f = Fixture()
        f.master.onRegister = { f.client.close() }
        f.connect()
        assertEquals(BridgeState.CLOSED, f.client.state)
        assertEquals(listOf(GuiLease("s1", "token1")), f.master.unregistered)
        assertFalse(f.endpoint.closed)
    }

    @Test fun registrationRetryUsesBoundedExponentialBackoff() {
        val f = Fixture(); f.master.registerFailures = 10
        f.connect()
        val delays = mutableListOf<Long>()
        repeat(5) {
            delays.add(f.runtime.tasks.single { it.lane == BridgeLane.CHANNEL }.at - f.runtime.now)
            f.runtime.run(BridgeLane.CHANNEL)
        }
        assertEquals(listOf(10L, 20L, 40L, 40L, 40L), delays)
        assertEquals(BridgeState.RECONNECTING, f.client.state)
    }

    @Test fun pollFailureReconnectsButMasterClosedDisables() {
        val f = Fixture(); f.connect(); f.master.pollFailure = true
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.RECONNECTING, f.client.state)
        f.master.pollFailure = false
        f.runtime.run(BridgeLane.CLEANUP)
        f.runtime.run(BridgeLane.CHANNEL)
        f.master.commands.add(PollResult(PollStatus.CLOSED))
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.DISABLED, f.client.state)
        f.runtime.run(BridgeLane.HEARTBEAT) // stale heartbeat cannot schedule a registration
        assertEquals(2, f.master.registrations.size)
        assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.CHANNEL })
    }

    @Test fun heartbeatClosedDisablesAndRejectsRunningResult() {
        val f = Fixture(); f.connect(); f.command()
        f.master.onHeartbeat = { throw ProtocolException(ProtocolError(ErrorCode.CLOSED, "server closed")) }
        f.endpoint.action = { context ->
            f.runtime.run(BridgeLane.HEARTBEAT)
            assertTrue(context.cancellation.isCancelled())
            SummaryResult()
        }
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.EXECUTION)
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertTrue(f.master.completions.isEmpty())
        f.client.updateProject(1, "next.apk")
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.CHANNEL })
    }

    @Test fun lateHeartbeatClosedCancelsAutomaticReconnectSuccessor() {
        val f = Fixture(); f.connect()
        f.master.onHeartbeat = {
            f.master.pollFailure = true
            f.runtime.run(BridgeLane.CHANNEL) // poll network failure retires the heartbeat's generation
            f.master.pollFailure = false
            f.runtime.run(BridgeLane.CLEANUP)
            f.runtime.run(BridgeLane.CHANNEL) // automatic reconnect succeeds before CLOSED arrives
            throw ProtocolException(ProtocolError(ErrorCode.CLOSED, "old session actively closed"))
        }
        f.runtime.run(BridgeLane.HEARTBEAT)
        assertEquals(BridgeState.DISABLED, f.client.state)
        f.runtime.run(BridgeLane.CHANNEL) // already queued replacement poll is fenced
        assertEquals(2, f.master.registrations.size)
        assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.CHANNEL })
    }

    @Test fun lateHeartbeatClosedDoesNotDisableExplicitNewActivation() {
        val f = Fixture(); f.connect()
        f.master.onHeartbeat = {
            f.client.disable(); f.client.enable()
            f.runtime.run(BridgeLane.CLEANUP)
            f.runtime.run(BridgeLane.CHANNEL) // discard old queued poll
            f.runtime.run(BridgeLane.CHANNEL) // register the explicit new activation
            throw ProtocolException(ProtocolError(ErrorCode.CLOSED, "old activation closed"))
        }
        f.runtime.run(BridgeLane.HEARTBEAT)
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertEquals(2, f.master.registrations.size)
    }

    @Test fun completionClosedDisablesInsteadOfReplayingOrReconnecting() {
        val f = Fixture(); f.connect(); f.command()
        f.master.onComplete = { throw ProtocolException(ProtocolError(ErrorCode.CLOSED, "closed before report")) }
        f.execute()
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertTrue(f.master.completions.isEmpty())
        assertEquals(1, f.endpoint.calls.size)
    }

    @Test fun protocolLeaseConflictIsNotTreatedAsNetworkFailure() {
        val f = Fixture(); f.connect()
        f.master.onHeartbeat = { throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "stale lease")) }
        f.runtime.run(BridgeLane.HEARTBEAT)
        assertEquals(BridgeState.DISABLED, f.client.state)
    }

    @Test fun queuedReportIsRejectedAfterHeartbeatClosed() {
        val f = Fixture(); f.connect(); f.command()
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.EXECUTION)
        f.master.onHeartbeat = { throw ProtocolException(ProtocolError(ErrorCode.CLOSED, "server closed")) }
        f.runtime.run(BridgeLane.HEARTBEAT); f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.DISABLED, f.client.state)
        assertTrue(f.master.completions.isEmpty())
    }

    @Test fun latePollClosedCancelsPendingNetworkReconnect() {
        val f = Fixture(); f.connect()
        f.master.commands.add(PollResult(PollStatus.CLOSED))
        f.master.onPoll = {
            f.master.heartbeatFailure = true
            f.runtime.run(BridgeLane.HEARTBEAT)
        }
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.DISABLED, f.client.state)
        f.runtime.run(BridgeLane.CHANNEL) // fenced automatic registration
        assertEquals(1, f.master.registrations.size)
    }

    @Test fun oldExecutionCannotReportIntoReplacementLeaseAfterHeartbeatFailure() {
        val f = Fixture(); f.connect(); f.command()
        f.endpoint.action = { context ->
            f.master.heartbeatFailure = true
            f.runtime.run(BridgeLane.HEARTBEAT)
            assertTrue(context.cancellation.isCancelled())
            f.master.heartbeatFailure = false
            f.runtime.run(BridgeLane.CLEANUP)
            f.runtime.run(BridgeLane.CHANNEL)
            assertEquals(BridgeState.CONNECTED, f.client.state)
            SummaryResult()
        }
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.EXECUTION)
        assertTrue(f.master.completions.isEmpty())
        assertEquals(2, f.master.registrations.size)
    }

    @Test fun projectSwapCancelsRunningContextAndDropsOldResult() {
        val f = Fixture(); f.connect(); f.command()
        f.endpoint.action = { context ->
            f.client.updateProject(1, "next.apk")
            assertTrue(context.cancellation.isCancelled())
            f.runtime.run(BridgeLane.CLEANUP)
            f.runtime.run(BridgeLane.CHANNEL)
            SummaryResult()
        }
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.EXECUTION)
        assertNotEquals(f.master.leases[0].sessionId, f.master.leases[1].sessionId)
        assertEquals(listOf(f.master.leases[0]), f.master.unregistered)
        assertEquals(1L, f.master.registrations.last().projectEpoch)
        assertTrue(f.master.completions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { f.client.updateProject(0, "old.apk") }
    }

    @Test fun invalidSessionEpochAndDeadlineNeverReachEndpoint() {
        listOf(ErrorCode.CONFLICT, ErrorCode.CONFLICT, ErrorCode.DEADLINE_EXCEEDED).forEachIndexed { index, code ->
            val f = Fixture(); f.connect()
            f.command(session = if (index == 0) "wrong" else "s1", epoch = if (index == 1) 2 else 0,
                deadline = if (index == 2) 1 else 100_000)
            f.execute()
            assertTrue(f.endpoint.calls.isEmpty())
            assertEquals(code, f.master.completions.single().second.error?.code)
        }
    }

    @Test fun duplicateRequestIsNotExecutedTwice() {
        val f = Fixture(); f.connect(); f.command(); f.execute(); f.command()
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(1, f.endpoint.calls.size)
        assertEquals(BridgeState.DISABLED, f.client.state)
    }

    @Test fun ambiguousCompletionFailureReconnectsWithoutReexecuting() {
        val f = Fixture(); f.connect(); f.command(); f.master.completionFailure = true; f.execute()
        assertEquals(1, f.endpoint.calls.size)
        assertEquals(BridgeState.RECONNECTING, f.client.state)
        f.runtime.run(BridgeLane.CLEANUP)
        f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.CONNECTED, f.client.state)
        assertEquals(1, f.endpoint.calls.size)
    }

    @Test fun latePollCannotQueueWorkAfterDisable() {
        val f = Fixture(); f.connect(); f.command()
        f.master.onPoll = { f.client.disable() }
        f.runtime.run(BridgeLane.CHANNEL)
        assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.EXECUTION })
        assertEquals(BridgeState.DISABLED, f.client.state)
    }

    @Test fun reconnectCannotQueueNewAnalysisBehindUninterruptibleOldAnalysis() {
        val f = Fixture(); f.connect(); f.command()
        f.endpoint.action = {
            f.master.heartbeatFailure = true
            f.runtime.run(BridgeLane.HEARTBEAT)
            f.master.heartbeatFailure = false
            f.runtime.run(BridgeLane.CLEANUP)
            f.runtime.run(BridgeLane.CHANNEL) // replacement registration
            f.command("new", "s2")
            repeat(3) { f.runtime.run(BridgeLane.CHANNEL) }
            assertEquals(1, f.master.commands.size) // not polled while the borrowed call is running
            assertTrue(f.runtime.tasks.none { it.lane == BridgeLane.EXECUTION })
            SummaryResult()
        }
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.EXECUTION)
        f.endpoint.action = { SummaryResult() }
        f.runtime.run(BridgeLane.CHANNEL); f.runtime.run(BridgeLane.EXECUTION); f.runtime.run(BridgeLane.CHANNEL)
        assertEquals(listOf("r1", "new"), f.endpoint.calls.map { it.requestId })
        assertEquals("s2", f.master.completions.single().first.sessionId)
    }

    @Test fun expiredResultAndEndpointFailureAreErrorsNotSuccesses() {
        val f = Fixture(); f.connect(); f.command(deadline = 2_000)
        f.endpoint.action = { f.runtime.now = 2_000; SummaryResult() }
        f.execute()
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, f.master.completions.single().second.error?.code)
        f.command("failure")
        f.endpoint.action = { error("private implementation details") }
        f.execute()
        assertEquals(ProtocolError(ErrorCode.INTERNAL, "GUI execution failed"), f.master.completions.last().second.error)
    }

    @Test fun unsupportedOperationAndWrongResultTypeAreNotSuccessful() {
        val f = Fixture(); f.connect()
        f.master.commands.add(PollResult(PollStatus.COMMAND,
            RequestFrame(ManifestRequest("s1"), CallContext("unsupported", 0, 100_000))))
        f.execute()
        assertTrue(f.endpoint.calls.isEmpty())
        assertEquals(ErrorCode.UNSUPPORTED, f.master.completions.single().second.error?.code)
        f.command()
        f.endpoint.action = { ManifestResult() }
        f.execute()
        assertEquals(ErrorCode.INTERNAL, f.master.completions.last().second.error?.code)
    }

    @Test fun unloadAndDisableBeforeRegistrationNeverTouchTransport() {
        val f = Fixture(); f.client.enable(); f.client.disable(); f.runtime.run(BridgeLane.CHANNEL)
        assertTrue(f.master.registrations.isEmpty())
        f.client.close()
        assertThrows(IllegalStateException::class.java) { f.client.enable() }
        assertFalse(f.endpoint.closed)
    }

    @Test fun originalCancellationIsPreservedAndPreventsExecution() {
        val f = Fixture(); f.connect()
        val source = CancellationSource().apply { cancel() }
        f.master.commands.add(PollResult(PollStatus.COMMAND,
            RequestFrame(SummaryRequest("s1"), CallContext("cancelled", 0, 100_000, source))))
        f.execute()
        assertTrue(f.endpoint.calls.isEmpty())
        assertEquals(ErrorCode.CANCELLED, f.master.completions.single().second.error?.code)
    }

    @Test fun replayMemoryLimitDisablesRatherThanForgettingIdsOrAutomaticallyRegistering() {
        val runtime = ManualRuntime()
        val master = Master()
        val endpoint = Endpoint()
        val client = GuiBridgeClient(master, endpoint, "instance", "fake GUI", runtime = runtime, clock = runtime,
            policy = BridgePolicy(maxCommandsPerLease = 1))
        client.enable(); runtime.run(BridgeLane.CHANNEL)
        master.commands.add(PollResult(PollStatus.COMMAND,
            RequestFrame(SummaryRequest("s1"), CallContext("r1", 0, 100_000))))
        runtime.run(BridgeLane.CHANNEL); runtime.run(BridgeLane.EXECUTION); runtime.run(BridgeLane.CHANNEL)
        runtime.run(BridgeLane.CHANNEL)
        assertEquals(BridgeState.DISABLED, client.state)
        assertEquals(1, endpoint.calls.size)
    }

    @Test fun executorRuntimeKeepsHeartbeatIndependentAndUnloadNonblocking() {
        // Real scheduling, fake transport/endpoint: this is not evidence of a real JADX GUI run.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val beatDuringExecution = CountDownLatch(1)
        val endpoint = Endpoint().apply {
            action = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                SummaryResult()
            }
        }
        val transport = object : GuiChannelPort {
            override fun register(registration: GuiRegistration) = GuiLease("s1", "token")
            override fun poll(lease: GuiLease, context: CallContext) = PollResult(PollStatus.COMMAND,
                RequestFrame(SummaryRequest("s1"), CallContext("r", 0, System.currentTimeMillis() + 5_000)))
            override fun heartbeat(lease: GuiLease, projectEpoch: Long) {
                if (entered.count == 0L) beatDuringExecution.countDown()
            }
            override fun complete(lease: GuiLease, completion: CommandCompletion) = Unit
            override fun unregister(lease: GuiLease) = Unit
        }
        val client = GuiBridgeClient(transport, endpoint, "instance", "fake GUI", policy = BridgePolicy(heartbeatMillis = 10))
        try {
            client.enable()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(beatDuringExecution.await(3, TimeUnit.SECONDS))
            val start = System.nanoTime()
            client.close()
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            assertFalse(endpoint.closed)
        } finally {
            release.countDown()
            client.close()
        }
    }
}
