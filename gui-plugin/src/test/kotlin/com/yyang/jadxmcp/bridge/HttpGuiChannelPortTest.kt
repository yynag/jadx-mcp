package com.yyang.jadxmcp.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real loopback HTTP, fake master responses only; does not launch JADX or the P10 application. */
class HttpGuiChannelPortTest {
    private val codec = GuiTransportCodec()
    private val lease = GuiLease("gui-session", "secret-token")

    private class Loopback(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "bridge-http-test").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val failure = AtomicReference<Throwable>()
        val calls = AtomicInteger()
        val origin: URI get() = URI.create("http://127.0.0.1:${server.address.port}")
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                try { handler(exchange) }
                catch (e: Throwable) {
                    // Disconnects are expected in cancellation/size/timeout tests; assertions are not.
                    if (e !is java.io.IOException && e !is InterruptedException) failure.compareAndSet(null, e)
                } finally { exchange.close() }
            }
            server.start()
        }
        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            failure.get()?.let { throw AssertionError("Loopback handler failed", it) }
        }
    }

    private fun reply(exchange: HttpExchange, body: String, status: Int = 200, chunked: Boolean = false) {
        val bytes = body.toByteArray(UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun pollContext(timeout: Long = 2_000, cancellation: CancellationSignal = CancellationSignal.NONE) =
        CallContext("poll-id", 7, System.currentTimeMillis() + timeout, cancellation)

    @Test fun allFiveRoutesUseSharedTypedBodiesAndPolymorphicCodecs() {
        val paths = CopyOnWriteArrayList<String>()
        val completionCount = AtomicInteger()
        val frame = RequestFrame(SummaryRequest(lease.sessionId),
            CallContext("command-id", 7, System.currentTimeMillis() + 5_000))
        Loopback { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            val path = exchange.requestURI.path
            paths.add(path)
            val body = String(exchange.requestBody.readBytes(), UTF_8)
            when (path) {
                GuiHttpPaths.REGISTER -> {
                    val registration = codec.decodeRegistration(body)
                    assertEquals("plugin", registration.instanceId)
                    assertEquals(7L, registration.projectEpoch)
                    assertEquals(BackendKind.GUI, registration.capabilities.backend)
                    reply(exchange, codec.encodeLease(lease))
                }
                GuiHttpPaths.POLL -> {
                    val request = codec.decodePollRequest(body)
                    assertEquals(lease, request.lease)
                    assertEquals("poll-id", request.requestId)
                    assertEquals(7L, request.projectEpoch)
                    request.context().checkActive()
                    reply(exchange, codec.encodePollResult(PollResult(PollStatus.COMMAND, frame)))
                }
                GuiHttpPaths.COMPLETE -> {
                    val request = codec.decodeCompletion(body)
                    assertEquals(lease, request.lease)
                    assertEquals("command-id", request.completion.requestId)
                    assertEquals(7L, request.completion.projectEpoch)
                    assertEquals(Operation.META_SUMMARY, request.completion.operation)
                    if (completionCount.getAndIncrement() == 0) {
                        assertEquals(SummaryResult(classesCount = 42), request.completion.result)
                    } else assertEquals(ErrorCode.CANCELLED, request.completion.error?.code)
                    reply(exchange, codec.encodeAck())
                }
                GuiHttpPaths.HEARTBEAT -> {
                    assertEquals(GuiHeartbeatRequest(lease, 7), codec.decodeHeartbeat(body))
                    reply(exchange, codec.encodeAck())
                }
                GuiHttpPaths.UNREGISTER -> {
                    assertEquals(lease, codec.decodeLease(body))
                    reply(exchange, codec.encodeAck())
                }
                else -> error("Unexpected path $path")
            }
        }.use { server ->
            val transport = HttpGuiChannelPort(server.origin)
            assertEquals(lease, transport.register(GuiRegistration("plugin", "Fake GUI", 7)))
            val response = transport.poll(lease, pollContext())
            assertEquals(PollStatus.COMMAND, response.status)
            assertEquals(frame.request, response.command?.request)
            assertEquals(frame.context.requestId, response.command?.context?.requestId)
            assertEquals(frame.context.deadlineEpochMillis, response.command?.context?.deadlineEpochMillis)
            transport.complete(lease, CommandCompletion("command-id", 7, Operation.META_SUMMARY, SummaryResult(classesCount = 42)))
            transport.complete(lease, CommandCompletion("command-id", 7, Operation.META_SUMMARY,
                error = ProtocolError(ErrorCode.CANCELLED, "cancelled")))
            transport.heartbeat(lease, 7)
            transport.unregister(lease)
            assertEquals(listOf(GuiHttpPaths.REGISTER, GuiHttpPaths.POLL, GuiHttpPaths.COMPLETE,
                GuiHttpPaths.COMPLETE, GuiHttpPaths.HEARTBEAT, GuiHttpPaths.UNREGISTER), paths)
        }
    }

    @Test fun nonSuccessOnEveryRouteDecodesProtocolErrorRatherThanHttpStatusGuessing() {
        val expected = ProtocolError(ErrorCode.CLOSED, "actively closed by master", false)
        Loopback { exchange -> reply(exchange, codec.encodeError(expected), 410) }.use { server ->
            val transport = HttpGuiChannelPort(server.origin)
            val actions: List<() -> Unit> = listOf(
                { transport.register(GuiRegistration("plugin", "Fake GUI")); Unit },
                { transport.poll(lease, pollContext()); Unit },
                { transport.complete(lease, CommandCompletion("r", 7, Operation.META_SUMMARY, SummaryResult())) },
                { transport.heartbeat(lease, 7) },
                { transport.unregister(lease) }
            )
            actions.forEach { action ->
                assertEquals(expected, assertThrows(ProtocolException::class.java) { action() }.error)
            }
            assertEquals(5, server.calls.get())
        }
    }

    @Test fun idleAndClosedPollBodiesHaveNoFakeCommand() {
        val count = AtomicInteger()
        Loopback { exchange -> reply(exchange, codec.encodePollResult(
            PollResult(if (count.getAndIncrement() == 0) PollStatus.IDLE else PollStatus.CLOSED))) }.use { server ->
            val transport = HttpGuiChannelPort(server.origin)
            assertEquals(PollResult(PollStatus.IDLE), transport.poll(lease, pollContext()))
            assertEquals(PollResult(PollStatus.CLOSED), transport.poll(lease, pollContext()))
        }
    }

    @Test fun declaredAndChunkedResponsesAreBoundedIncludingErrorBodies() {
        listOf(false to 200, true to 200, true to 503).forEach { (chunked, status) ->
            Loopback { exchange -> reply(exchange, "x".repeat(1024), status, chunked) }.use { server ->
                val transport = HttpGuiChannelPort(server.origin, GuiHttpLimits(maxResponseBytes = 64))
                val error = assertThrows(ProtocolException::class.java) { transport.heartbeat(lease, 7) }.error
                assertEquals(ErrorCode.INVALID_ARGUMENT, error.code)
                assertEquals("GUI response exceeds size limit", error.message)
            }
        }
    }

    @Test fun inFlightPollCancellationStopsWaitingWithoutReportingAnIdleCommand() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            Loopback { exchange ->
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                reply(exchange, codec.encodePollResult(PollResult(PollStatus.IDLE)))
            }.use { server ->
                val transport = HttpGuiChannelPort(server.origin, GuiHttpLimits(cancellationCheckMillis = 10))
                val cancellation = CancellationSource()
                val result = worker.submit<ProtocolError> {
                    assertThrows(ProtocolException::class.java) { transport.poll(lease, pollContext(5_000, cancellation)) }.error
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                cancellation.cancel()
                assertEquals(ErrorCode.CANCELLED, result.get(2, TimeUnit.SECONDS).code)
                assertEquals(1, server.calls.get())
                release.countDown()
            }
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun alreadyCancelledOrExpiredPollNeverSendsRequest() {
        Loopback { exchange -> reply(exchange, codec.encodePollResult(PollResult(PollStatus.IDLE))) }.use { server ->
            val transport = HttpGuiChannelPort(server.origin)
            val cancellation = CancellationSource().apply { cancel() }
            assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
                transport.poll(lease, pollContext(cancellation = cancellation))
            }.error.code)
            assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(ProtocolException::class.java) {
                transport.poll(lease, CallContext("expired", 7, 1))
            }.error.code)
            assertEquals(0, server.calls.get())
        }
    }

    @Test fun pollDeadlineIsShorterThanGeneralIoLimit() {
        val release = CountDownLatch(1)
        try {
            Loopback { exchange ->
                release.await(3, TimeUnit.SECONDS)
                reply(exchange, codec.encodePollResult(PollResult(PollStatus.IDLE)))
            }.use { server ->
                val transport = HttpGuiChannelPort(server.origin, GuiHttpLimits(ioTimeoutMillis = 5_000))
                val start = System.nanoTime()
                assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(ProtocolException::class.java) {
                    transport.poll(lease, pollContext(200))
                }.error.code)
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                release.countDown()
            }
        } finally { release.countDown() }
    }

    @Test fun totalIoBudgetAlsoBoundsStalledChunkedBodyNotOnlyResponseHeaders() {
        val release = CountDownLatch(1)
        try {
            Loopback { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                release.await(3, TimeUnit.SECONDS)
            }.use { server ->
                val transport = HttpGuiChannelPort(server.origin, GuiHttpLimits(ioTimeoutMillis = 200))
                val start = System.nanoTime()
                assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(ProtocolException::class.java) {
                    transport.heartbeat(lease, 7)
                }.error.code)
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                release.countDown()
            }
        } finally { release.countDown() }
    }

    @Test fun registrationCompletionAndUnregisterAlsoHaveFiniteIoBudgets() {
        repeat(3) { operation ->
            val release = CountDownLatch(1)
            try {
                Loopback { exchange ->
                    release.await(3, TimeUnit.SECONDS)
                    reply(exchange, codec.encodeAck())
                }.use { server ->
                    val transport = HttpGuiChannelPort(server.origin, GuiHttpLimits(ioTimeoutMillis = 200))
                    val start = System.nanoTime()
                    val error = assertThrows(ProtocolException::class.java) {
                        when (operation) {
                            0 -> { transport.register(GuiRegistration("plugin", "Fake GUI")); Unit }
                            1 -> transport.complete(lease, CommandCompletion("r", 7, Operation.META_SUMMARY, SummaryResult()))
                            else -> transport.unregister(lease)
                        }
                    }.error
                    assertEquals(ErrorCode.DEADLINE_EXCEEDED, error.code)
                    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
                    release.countDown()
                }
            } finally { release.countDown() }
        }
    }

    @Test fun refusedLoopbackConnectionIsReportedAsTransportFailure() {
        val origin = Loopback { error("Not serving") }.use { it.origin }
        val transport = HttpGuiChannelPort(origin, GuiHttpLimits(connectTimeoutMillis = 200, ioTimeoutMillis = 1_000))
        assertEquals(ErrorCode.DISCONNECTED, assertThrows(ProtocolException::class.java) {
            transport.heartbeat(lease, 7)
        }.error.code)
    }

    @Test fun redirectsAreNotFollowedAndTheirTypedErrorsAreDecoded() {
        Loopback { exchange ->
            if (exchange.requestURI.path == "/target") error("Lease was forwarded to redirect target")
            exchange.responseHeaders.set("Location", "/target")
            reply(exchange, codec.encodeError(ProtocolError(ErrorCode.CONFLICT, "redirect denied")), 307)
        }.use { server ->
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                HttpGuiChannelPort(server.origin).heartbeat(lease, 7)
            }.error.code)
            assertEquals(1, server.calls.get())
        }
    }

    @Test fun oversizedOutgoingCompletionIsRejectedBeforeNetworkIo() {
        Loopback { exchange -> reply(exchange, codec.encodeAck()) }.use { server ->
            val transport = HttpGuiChannelPort(server.origin, GuiHttpLimits(maxRequestBytes = 64))
            assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
                transport.complete(lease, CommandCompletion("r", 7, Operation.META_SUMMARY,
                    SummaryResult(mainActivity = "x".repeat(1024))))
            }.error.code)
            assertEquals(0, server.calls.get())
        }
    }

    @Test fun malformedErrorAndRejectedAcknowledgementAreNotAcceptedAsSuccess() {
        val counter = AtomicInteger()
        Loopback { exchange ->
            if (counter.getAndIncrement() == 0) reply(exchange, "{}", 503)
            else reply(exchange, codec.encodeAck(GuiAck(false)))
        }.use { server ->
            val transport = HttpGuiChannelPort(server.origin)
            assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
                transport.heartbeat(lease, 7)
            }.error.code)
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                transport.unregister(lease)
            }.error.code)
        }
    }

    @Test fun originMustNotContainCredentialsOrUnexpectedPathQueryOrFragment() {
        listOf("http://user:password@localhost", "http://localhost/gui", "http://localhost?token=x",
            "http://localhost/#fragment", "file:///local").forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) { HttpGuiChannelPort(URI.create(uri)) }
        }
    }
}
