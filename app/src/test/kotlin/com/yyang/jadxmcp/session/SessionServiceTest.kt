package com.yyang.jadxmcp.session

import com.yyang.jadxmcp.protocol.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Fake transport/backend only: these tests do not start JADX, Swing, HTTP, or a real GUI. */
class SessionServiceTest {
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
}
