package com.yyang.jadxmcp.session

import com.yyang.jadxmcp.protocol.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Fake transport/backend only: these tests do not start JADX, Swing, HTTP, or a real GUI. */
class SessionServiceTest {
    @Test fun `headless name is basename and session IDs are short with strong independent GUI tokens`() {
        val clock = Clock.fixed(Instant.parse("2026-11-10T07:18:34Z"), ZoneId.of("Asia/Shanghai"))
        SessionService(HeadlessBackendFactory { _, _ -> FakeBackend() }, idClock = clock).use { service ->
            val headless = service.create(CreateSessionRequest("/private/input/example.apk"), context())
            assertEquals("example.apk", headless.name)
            assertEquals("10151834", headless.sessionId)
            val leases = (1..32).map { service.register(GuiRegistration("gui-$it", "project.apk", 0)) }
            assertEquals("10151834-1", leases.first().sessionId)
            assertEquals(32, leases.map { it.token }.toSet().size)
            leases.forEach {
                val token = UUID.fromString(it.token)
                assertEquals(4, token.version())
                assertEquals(2, token.variant())
                assertNotEquals(it.sessionId, it.token)
            }
        }
    }

    @Test fun `month repeated session ID cannot shadow active or tombstoned session`() {
        var instant = Instant.parse("2026-11-10T07:18:34Z")
        val clock = object : Clock() {
            override fun instant() = instant
            override fun getZone() = ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
        }
        SessionService(HeadlessBackendFactory { _, _ -> FakeBackend() }, idClock = clock).use { service ->
            val first = service.register(GuiRegistration("first", "first.apk", 0))
            instant = Instant.parse("2026-12-10T07:18:34Z")
            val second = service.register(GuiRegistration("second", "second.apk", 0))
            assertEquals("${first.sessionId}-1", second.sessionId)
            service.unregister(first)
            instant = Instant.parse("2027-01-10T07:18:34Z")
            val third = service.register(GuiRegistration("third", "third.apk", 0))
            assertEquals("${first.sessionId}-2", third.sessionId)
            assertEquals(PollStatus.CLOSED, service.poll(first, context()).status)
            assertEquals("second.apk", service.get(second.sessionId).name)
        }
    }

    @Test fun `project replacement unregisters old GUI session and allocates a new session ID`() {
        service().use { service ->
            val old = service.register(GuiRegistration("same-gui", "Project A", 4))
            assertEquals(listOf(old.sessionId), service.list().sessions.map { it.sessionId })

            service.unregister(old)
            assertTrue(service.list().sessions.isEmpty())

            val replacement = service.register(GuiRegistration("same-gui", "Project B", 5))
            assertNotEquals(old.sessionId, replacement.sessionId)
            assertEquals(listOf(replacement.sessionId), service.list().sessions.map { it.sessionId })
            val metadata = service.get(replacement.sessionId)
            assertEquals("Project B", metadata.name)
            assertEquals(5, metadata.projectEpoch)
            assertEquals(BackendKind.GUI, metadata.backend)
            error(ErrorCode.CLOSED) { service.heartbeat(old, 4) }
        }
    }

    @Test fun `reused caller request ID cannot accept an old wire completion`() {
        service().use { service ->
            val lease = service.register(registration())
            val first = service.submit(SummaryRequest(lease.sessionId), context("same-user-id"))
            val oldFrame = assertNotNull(service.poll(lease, context("poll-first")).command)
            val oldCompletion = CommandCompletion(oldFrame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult())
            service.complete(lease, oldCompletion)
            first.done()
            val second = service.submit(SummaryRequest(lease.sessionId), context("same-user-id"))
            val newFrame = assertNotNull(service.poll(lease, context("poll-second")).command)
            assertNotEquals(oldFrame.context.requestId, newFrame.context.requestId)
            error(ErrorCode.CONFLICT) { service.complete(lease, oldCompletion) }
            assertFalse(second.isDone)
            service.complete(lease, CommandCompletion(newFrame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            second.done()
        }
    }

    @Test fun `retiring session ID stays reserved after its tombstone is evicted`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        var instant = Instant.parse("2026-11-10T07:18:34Z")
        val clock = object : Clock() {
            override fun instant() = instant
            override fun getZone() = ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
        }
        val backend = FakeBackend { entered.countDown(); exit.await(3, TimeUnit.SECONDS); SummaryResult() }
        try {
            SessionService(HeadlessBackendFactory { _, _ -> backend }, SessionPolicy(maxTombstones = 1),
                idClock = clock).use { service ->
                val retired = create(service)
                val pending = service.submit(SummaryRequest(retired), context("draining"))
                await(entered)
                service.closeSession(retired)
                failed(ErrorCode.CLOSED, pending)
                instant = Instant.parse("2026-11-11T07:18:34Z")
                val other = service.register(GuiRegistration("other", "other.apk", 0))
                service.unregister(other) // Evicts the first tombstone, but not its draining lane.
                instant = Instant.parse("2026-12-10T07:18:34Z")
                val fresh = service.register(GuiRegistration("fresh", "fresh.apk", 0))
                assertEquals("$retired-1", fresh.sessionId)
            }
        } finally { exit.countDown() }
    }

    private class FakeBackend(val action: (CallContext) -> OperationResult = { SummaryResult(classesCount = 7) }) : BackendEndpoint {
        override val capabilities = BackendCapabilities(BackendKind.HEADLESS)
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        override fun execute(request: BackendRequest, context: CallContext) = action(context)
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }

    private fun context(id: String = "request", epoch: Long = 0, timeout: Long = 5_000) =
        CallContext(id, epoch, System.currentTimeMillis() + timeout)
    private fun create(service: SessionService) = service.create(CreateSessionRequest("fake.apk"), context()).sessionId
    private fun registration(epoch: Long = 0) = GuiRegistration("gui-1", "Fake GUI", epoch)
    private fun service(backend: BackendEndpoint = FakeBackend(), policy: SessionPolicy = SessionPolicy()) =
        SessionService(HeadlessBackendFactory { _, _ -> backend }, policy)
    private fun <T> CompletableFuture<T>.done(): T = get(2, TimeUnit.SECONDS)
    private fun error(code: ErrorCode, action: () -> Unit) {
        val e = assertFailsWith<ProtocolException>(block = action)
        assertEquals(code, e.error.code)
    }
    private fun failed(code: ErrorCode, future: CompletableFuture<OperationResult>) {
        val e = assertFailsWith<ExecutionException> { future.done() }
        assertEquals(code, (e.cause as ProtocolException).error.code)
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(2, TimeUnit.SECONDS), "Timed out waiting for fake backend")

    @Test fun `same session serial cross session parallel and cancellation retains physical lane`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val starts = AtomicInteger()
        val first = FakeBackend {
            if (starts.incrementAndGet() == 1) {
                entered.countDown()
                assertTrue(exit.await(3, TimeUnit.SECONDS))
            }
            SummaryResult()
        }
        val second = FakeBackend()
        val count = AtomicInteger()
        SessionService(HeadlessBackendFactory { _, _ -> if (count.incrementAndGet() == 1) first else second }).use { service ->
            val a = create(service)
            val b = create(service)
            val running = service.submit(SummaryRequest(a), context("first"))
            await(entered)
            val queued = service.submit(SummaryRequest(a), context("second"))
            assertEquals(7, (service.submit(SummaryRequest(b), context("other")).done() as SummaryResult).classesCount)
            assertTrue(service.cancel(a, "first"))
            failed(ErrorCode.CANCELLED, running)
            assertFalse(queued.isDone)
            assertEquals(1, starts.get())
            exit.countDown()
            queued.done()
            assertEquals(2, starts.get())
        }
    }

    @Test fun `deadline and external cancellation wake without backend cooperation`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val backend = FakeBackend { entered.countDown(); exit.await(3, TimeUnit.SECONDS); SummaryResult() }
        service(backend).use { service ->
            val id = create(service)
            val timed = service.submit(SummaryRequest(id), context("timed", timeout = 80))
            await(entered)
            failed(ErrorCode.DEADLINE_EXCEEDED, timed)
            val source = CancellationSource()
            val queued = service.submit(SummaryRequest(id), context("source").copy(cancellation = source))
            source.cancel()
            failed(ErrorCode.CANCELLED, queued)
            exit.countDown()
        }
    }

    @Test fun `close bypasses busy backend and rejects late result`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val backend = FakeBackend { entered.countDown(); exit.await(3, TimeUnit.SECONDS); SummaryResult() }
        service(backend).use { service ->
            val id = create(service)
            val call = service.submit(SummaryRequest(id), context())
            await(entered)
            assertTrue(service.closeSession(id).closed)
            failed(ErrorCode.CLOSED, call)
            assertTrue(service.list().sessions.isEmpty())
            error(ErrorCode.CLOSED) { service.get(id) }
            exit.countDown()
        }
    }

    @Test fun `fake reverse transport completes typed request while control plane remains available`() {
        service().use { service ->
            val port: GuiChannelPort = service
            val lease = port.register(registration())
            val call = service.submit(SummaryRequest(lease.sessionId), context("gui-call"))
            val frame = assertNotNull(port.poll(lease, context("poll")).command)
            assertIs<SummaryRequest>(frame.request)
            assertEquals(SessionState.BUSY, service.get(lease.sessionId).state)
            port.heartbeat(lease, 0)
            error(ErrorCode.INVALID_ARGUMENT) {
                port.complete(lease, CommandCompletion(frame.context.requestId, 0, Operation.META_SUMMARY, SessionListResult()))
            }
            port.complete(lease, CommandCompletion(frame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult(classesCount = 12)))
            assertEquals(12, (call.done() as SummaryResult).classesCount)
            error(ErrorCode.CONFLICT) {
                port.complete(lease, CommandCompletion(frame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            }
        }
    }

    @Test fun `epoch heartbeat fences running queued and late completions`() {
        service().use { service ->
            val lease = service.register(registration())
            val first = service.submit(SummaryRequest(lease.sessionId), context("first"))
            val oldFrame = assertNotNull(service.poll(lease, context("poll")).command)
            val queued = service.submit(SummaryRequest(lease.sessionId), context("queued"))
            service.heartbeat(lease, 1)
            failed(ErrorCode.CONFLICT, first)
            failed(ErrorCode.CONFLICT, queued)
            error(ErrorCode.CONFLICT) {
                service.complete(lease, CommandCompletion(oldFrame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            }
            error(ErrorCode.CONFLICT) { service.heartbeat(lease, 0) }
            val next = service.submit(SummaryRequest(lease.sessionId), context("new", epoch = 1))
            val frame = assertNotNull(service.poll(lease, context("poll-new", epoch = 1)).command)
            assertEquals(1, frame.context.projectEpoch)
            service.complete(lease, CommandCompletion(frame.context.requestId, 1, Operation.META_SUMMARY, SummaryResult()))
            next.done()
        }
    }

    @Test fun `timed out remote command must acknowledge before successor is dispatched`() {
        service().use { service ->
            val lease = service.register(registration())
            val first = service.submit(SummaryRequest(lease.sessionId), context("slow", timeout = 120))
            val oldFrame = assertNotNull(service.poll(lease, context("poll")).command)
            failed(ErrorCode.DEADLINE_EXCEEDED, first)
            val next = service.submit(SummaryRequest(lease.sessionId), context("next"))
            assertFalse(next.isDone)
            service.heartbeat(lease, 0)
            error(ErrorCode.CANCELLED) {
                service.complete(lease, CommandCompletion(oldFrame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            }
            val frame = assertNotNull(service.poll(lease, context("poll-next")).command)
            assertNotEquals(oldFrame.context.requestId, frame.context.requestId)
            service.complete(lease, CommandCompletion(frame.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            next.done()
        }
    }

    @Test fun `unexpected disconnect reconnect rotates token but intentional close never revives old lease`() {
        val time = AtomicLong(System.currentTimeMillis())
        val policy = SessionPolicy(idleLeaseMillis = 50, busyHeartbeatMillis = 50, sweepMillis = 1_000)
        SessionService(HeadlessBackendFactory { _, _ -> FakeBackend() }, policy, time::get).use { service ->
            val old = service.register(registration())
            time.addAndGet(51)
            service.sweep()
            assertEquals(SessionState.DISCONNECTED, service.get(old.sessionId).state)
            error(ErrorCode.DISCONNECTED) { service.heartbeat(old, 0) }
            val fresh = service.register(registration())
            assertEquals(old.sessionId, fresh.sessionId)
            assertNotEquals(old.token, fresh.token)
            error(ErrorCode.NOT_FOUND) { service.heartbeat(old, 0) }
            service.unregister(fresh)
            assertEquals(PollStatus.CLOSED, service.poll(fresh, context()).status)
            error(ErrorCode.CLOSED) { service.heartbeat(fresh, 0) }
            val newSession = service.register(registration())
            assertNotEquals(fresh.sessionId, newSession.sessionId)
        }
    }

    @Test fun `long command heartbeat renews independently of idle lease`() {
        val time = AtomicLong(System.currentTimeMillis())
        val policy = SessionPolicy(idleLeaseMillis = 50, busyHeartbeatMillis = 100, sweepMillis = 1_000)
        SessionService(HeadlessBackendFactory { _, _ -> FakeBackend() }, policy, time::get).use { service ->
            val lease = service.register(registration())
            time.addAndGet(40)
            service.heartbeat(lease, 0)
            time.addAndGet(40)
            service.sweep()
            assertEquals(SessionState.READY, service.get(lease.sessionId).state)
            val call = service.submit(SummaryRequest(lease.sessionId), context("busy"))
            service.poll(lease, context("poll"))
            time.addAndGet(80)
            service.heartbeat(lease, 0)
            time.addAndGet(80)
            service.sweep()
            assertEquals(SessionState.BUSY, service.get(lease.sessionId).state)
            time.addAndGet(21)
            service.sweep()
            failed(ErrorCode.DISCONNECTED, call)
            assertEquals(SessionState.DISCONNECTED, service.get(lease.sessionId).state)
        }
    }

    @Test fun `reconnect atomically refreshes name epoch and lease while stale registration changes nothing`() {
        val time = AtomicLong(System.currentTimeMillis())
        val policy = SessionPolicy(idleLeaseMillis = 50, sweepMillis = 1_000)
        SessionService(HeadlessBackendFactory { _, _ -> FakeBackend() }, policy, time::get).use { service ->
            val old = service.register(GuiRegistration("gui-1", "old.apk", 2))
            time.addAndGet(51)
            service.sweep()
            val disconnected = service.get(old.sessionId)
            error(ErrorCode.CONFLICT) { service.register(GuiRegistration("gui-1", "stale.apk", 1)) }
            assertEquals(disconnected, service.get(old.sessionId))
            assertEquals(listOf(disconnected), service.list().sessions)

            val fresh = service.register(GuiRegistration("gui-1", "new.apk", 3))
            assertEquals(old.sessionId, fresh.sessionId)
            assertNotEquals(old.token, fresh.token)
            val current = service.get(fresh.sessionId)
            assertEquals("new.apk", current.name)
            assertEquals(3L, current.projectEpoch)
            assertEquals(BackendKind.GUI, current.backend)
            assertEquals(BackendKind.GUI, current.capabilities?.backend)
            assertEquals(SessionState.READY, current.state)
            assertEquals(disconnected.createdAt, current.createdAt)
            assertEquals(listOf(current), service.list().sessions)
            error(ErrorCode.NOT_FOUND) { service.heartbeat(old, 2) }
            error(ErrorCode.CONFLICT) { service.execute(CloseSessionRequest(fresh.sessionId), context("old-project-close", epoch = 2)) }
            assertEquals(current, service.get(fresh.sessionId))
        }
    }

    @Test fun `same filename on different GUI instances does not merge sessions`() {
        service().use { service ->
            val first = service.register(GuiRegistration("gui-1", "same.apk", 1))
            val second = service.register(GuiRegistration("gui-2", "same.apk", 4))
            assertNotEquals(first.sessionId, second.sessionId)
            assertNotEquals(first.token, second.token)
            val sessions = service.list().sessions
            assertEquals(2, sessions.size)
            assertEquals(listOf("same.apk", "same.apk"), sessions.map { it.name })
            assertEquals(listOf(1L, 4L), sessions.map { it.projectEpoch })
            sessions.forEach { assertEquals(it, service.get(it.sessionId)) }
            error(ErrorCode.CONFLICT) { service.register(GuiRegistration("gui-1", "other.apk", 5)) }
            assertEquals(sessions, service.list().sessions)
        }
    }

    @Test fun `close wakes blocking GUI poll`() {
        service().use { service ->
            val lease = service.register(registration())
            Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                val poll = threads.submit<PollResult> { service.poll(lease, context("poll")) }
                service.closeSession(lease.sessionId)
                assertEquals(PollStatus.CLOSED, poll.get(2, TimeUnit.SECONDS).status)
            }
        }
    }

    @Test fun `pending capacity cancellation and static capabilities are enforced`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val backend = FakeBackend { entered.countDown(); exit.await(3, TimeUnit.SECONDS); SummaryResult() }
        service(backend, SessionPolicy(maxPendingPerSession = 2)).use { service ->
            val id = create(service)
            service.submit(SummaryRequest(id), context("running"))
            await(entered)
            val queued = service.submit(SummaryRequest(id), context("queued"))
            error(ErrorCode.CONFLICT) { service.submit(SummaryRequest(id), context("running")) }
            error(ErrorCode.BUSY) { service.submit(SummaryRequest(id), context("excess")) }
            service.cancel(id, "queued")
            failed(ErrorCode.CANCELLED, queued)
            error(ErrorCode.UNSUPPORTED) { service.submit(CurrentClassRequest(id), context("gui")) }
            exit.countDown()
        }
    }

    @Test fun `tombstones bounded by count and time and disconnected records expire`() {
        val time = AtomicLong(System.currentTimeMillis())
        val policy = SessionPolicy(maxTombstones = 1, tombstoneMillis = 100,
            idleLeaseMillis = 10, disconnectedRetentionMillis = 10, sweepMillis = 1_000)
        SessionService(HeadlessBackendFactory { _, _ -> FakeBackend() }, policy, time::get).use { service ->
            val first = service.register(registration())
            service.unregister(first)
            val second = service.register(registration())
            service.unregister(second)
            error(ErrorCode.NOT_FOUND) { service.get(first.sessionId) }
            error(ErrorCode.CLOSED) { service.get(second.sessionId) }
            time.addAndGet(101)
            service.sweep()
            error(ErrorCode.NOT_FOUND) { service.get(second.sessionId) }
            val abandoned = service.register(registration())
            time.addAndGet(11)
            service.sweep()
            time.addAndGet(11)
            service.sweep()
            assertTrue(service.list().sessions.isEmpty())
            assertEquals(PollStatus.CLOSED, service.poll(abandoned, context()).status)
        }
    }

    @Test fun `noncooperative retired backends still count against resource bound`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val backend = FakeBackend { entered.countDown(); exit.await(3, TimeUnit.SECONDS); SummaryResult() }
        service(backend, SessionPolicy(maxSessions = 1)).use { service ->
            val id = create(service)
            val call = service.submit(SummaryRequest(id), context())
            await(entered)
            service.closeSession(id)
            failed(ErrorCode.CLOSED, call)
            error(ErrorCode.BUSY) { create(service) }
            exit.countDown()
        }
    }

    @Test fun `loading deadline wakes caller and retains capacity until late backend is disposed`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val backend = FakeBackend()
        val time = AtomicLong(System.currentTimeMillis())
        val policy = SessionPolicy(maxSessions = 1, sweepMillis = 1_000)
        SessionService(HeadlessBackendFactory { _, _ ->
            entered.countDown()
            exit.await(3, TimeUnit.SECONDS)
            backend
        }, policy, time::get).use { service ->
            Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                val loading = threads.submit<SessionInfo> {
                    service.create(CreateSessionRequest("fake.apk"), context("load", timeout = 100))
                }
                await(entered)
                time.addAndGet(200)
                service.sweep()
                val e = assertFailsWith<ExecutionException> { loading.get(2, TimeUnit.SECONDS) }
                assertEquals(ErrorCode.DEADLINE_EXCEEDED, (e.cause as ProtocolException).error.code)
                error(ErrorCode.BUSY) { create(service) }
                assertTrue(service.list().sessions.isEmpty())
                exit.countDown()
                await(backend.closed)
                assertEquals(1, backend.closes.get())
                assertTrue(service.list().sessions.isEmpty())
            }
        }
    }

    @Test fun `reused caller id cannot accept a late previous wire completion`() {
        service().use { service ->
            val lease = service.register(registration())
            val first = service.submit(SummaryRequest(lease.sessionId), context("reuse"))
            val old = assertNotNull(service.poll(lease, context("poll-one")).command)
            service.complete(lease, CommandCompletion(old.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            first.done()
            val next = service.submit(SummaryRequest(lease.sessionId), context("reuse"))
            val current = assertNotNull(service.poll(lease, context("poll-two")).command)
            assertNotEquals(old.context.requestId, current.context.requestId)
            error(ErrorCode.CONFLICT) {
                service.complete(lease, CommandCompletion(old.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            }
            assertFalse(next.isDone)
            service.complete(lease, CommandCompletion(current.context.requestId, 0, Operation.META_SUMMARY, SummaryResult()))
            next.done()
        }
    }

    @Test fun `busy poll traffic alone cannot renew command heartbeat`() {
        val policy = SessionPolicy(idleLeaseMillis = 1_000, busyHeartbeatMillis = 100, maxPollMillis = 5)
        service(policy = policy).use { service ->
            val lease = service.register(registration())
            val call = service.submit(SummaryRequest(lease.sessionId), context("busy-poll"))
            assertEquals(PollStatus.COMMAND, service.poll(lease, context("initial-poll")).status)
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var polls = 0
            while (System.nanoTime() < end) {
                try {
                    service.poll(lease, context("idle-poll"))
                    polls++
                } catch (e: ProtocolException) {
                    assertEquals(ErrorCode.DISCONNECTED, e.error.code)
                    break
                }
            }
            assertTrue(polls > 0)
            failed(ErrorCode.DISCONNECTED, call)
            assertEquals(SessionState.DISCONNECTED, service.get(lease.sessionId).state)
        }
    }

    @Test fun `blocking caller completion callback cannot hold control monitor`() {
        val releaseBackend = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val callbackExit = CountDownLatch(1)
        val backend = FakeBackend { releaseBackend.await(3, TimeUnit.SECONDS); SummaryResult() }
        service(backend).use { service ->
            val id = create(service)
            val call = service.submit(SummaryRequest(id), context("callbacks"))
            val callback = call.thenRun {
                callbackEntered.countDown()
                callbackExit.await(3, TimeUnit.SECONDS)
            }
            releaseBackend.countDown()
            await(callbackEntered)
            val lease = service.register(registration())
            service.heartbeat(lease, 0)
            assertTrue(service.closeSession(id).closed)
            callbackExit.countDown()
            callback.done()
        }
    }

    @Test fun `terminal worker exposes closed metadata aborts queued work and retains its physical lane`() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val starts = AtomicInteger()
        val backend = object : ManagedBackendEndpoint {
            override val capabilities = BackendCapabilities(BackendKind.HEADLESS)
            @Volatile var terminal: ProtocolError? = null
            override val terminalError get() = terminal
            override fun execute(request: BackendRequest, context: CallContext): OperationResult {
                starts.incrementAndGet()
                entered.countDown()
                assertTrue(exit.await(3, TimeUnit.SECONDS))
                return SummaryResult()
            }
            override fun close() = close(false)
            override fun close(clearCache: Boolean) { disposed.countDown() }
        }
        service(backend, SessionPolicy(maxSessions = 1, sweepMillis = 1_000)).use { service ->
            try {
                val id = create(service)
                val running = service.submit(SummaryRequest(id), context("running"))
                await(entered)
                val queued = service.submit(SummaryRequest(id), context("queued"))
                backend.terminal = ProtocolError(ErrorCode.DISCONNECTED, "fake terminal worker")
                service.sweep()
                failed(ErrorCode.DISCONNECTED, running)
                failed(ErrorCode.DISCONNECTED, queued)
                await(disposed)
                val terminal = service.get(id)
                assertEquals(SessionState.CLOSED, terminal.state)
                assertEquals(BackendKind.HEADLESS, terminal.backend)
                assertEquals(0L, terminal.projectEpoch)
                assertEquals(listOf(terminal), service.list().sessions)
                error(ErrorCode.DISCONNECTED) { service.submit(SummaryRequest(id), context("late")) }
                assertTrue(service.closeSession(id).closed)
                error(ErrorCode.BUSY) { create(service) }
                assertEquals(1, starts.get())
            } finally { exit.countDown() }
        }
    }

    @Test fun `stale management fences preserve live work and matching close aborts running and queued calls`() {
        service().use { service ->
            val lease = service.register(registration(epoch = 2))
            service.heartbeat(lease, 3)
            val running = service.submit(SummaryRequest(lease.sessionId), context("running", epoch = 3))
            val frame = assertNotNull(service.poll(lease, context("poll", epoch = 3)).command)
            val queued = service.submit(SummaryRequest(lease.sessionId), context("queued", epoch = 3))
            error(ErrorCode.CONFLICT) { service.execute(CloseSessionRequest(lease.sessionId), context("stale-close", epoch = 2)) }
            assertFalse(running.isDone)
            assertFalse(queued.isDone)
            service.complete(lease, CommandCompletion(frame.context.requestId, 3, Operation.META_SUMMARY, SummaryResult()))
            running.done()
            val next = assertNotNull(service.poll(lease, context("next-poll", epoch = 3)).command)
            val waiting = service.submit(SummaryRequest(lease.sessionId), context("waiting", epoch = 3))
            assertTrue((service.execute(CloseSessionRequest(lease.sessionId), context("close", epoch = 3)) as CloseSessionResult).closed)
            failed(ErrorCode.CLOSED, queued)
            failed(ErrorCode.CLOSED, waiting)
            error(ErrorCode.CLOSED) {
                service.complete(lease, CommandCompletion(next.context.requestId, 3, Operation.META_SUMMARY, SummaryResult()))
            }
            assertEquals(PollStatus.CLOSED, service.poll(lease, context("closed-poll", epoch = 3)).status)
        }
    }

    @Test fun `direct management without context retains current snapshot and unconditional close semantics`() {
        service().use { service ->
            val lease = service.register(registration(epoch = 9))
            assertEquals(9L, service.get(lease.sessionId).projectEpoch)
            assertTrue(service.closeSession(lease.sessionId).closed)
            assertFalse(service.closeSession(lease.sessionId).closed)
        }
    }

    @Test fun `heartbeat epoch advance and fenced retirement are mutually exclusive`() {
        service().use { service ->
            Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                repeat(50) { index ->
                    val lease = service.register(GuiRegistration("race-$index", "race.apk", 1))
                    val start = CountDownLatch(1)
                    val close = threads.submit<ErrorCode?> {
                        await(start)
                        try {
                            assertTrue(service.closeSession(lease.sessionId, context = context("close-$index", epoch = 1)).closed)
                            null
                        } catch (e: ProtocolException) { e.error.code }
                    }
                    val advance = threads.submit<ErrorCode?> {
                        await(start)
                        try { service.heartbeat(lease, 2); null }
                        catch (e: ProtocolException) { e.error.code }
                    }
                    start.countDown()
                    val closeError = close.get(2, TimeUnit.SECONDS)
                    val heartbeatError = advance.get(2, TimeUnit.SECONDS)
                    if (heartbeatError == null) {
                        assertEquals(ErrorCode.CONFLICT, closeError)
                        assertEquals(2L, service.get(lease.sessionId).projectEpoch)
                        service.closeSession(lease.sessionId)
                    } else {
                        assertEquals(ErrorCode.CLOSED, heartbeatError)
                        assertNull(closeError)
                    }
                }
            }
        }
    }
}
