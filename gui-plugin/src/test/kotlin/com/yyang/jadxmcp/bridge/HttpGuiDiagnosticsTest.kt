package com.yyang.jadxmcp.bridge

import com.sun.net.httpserver.HttpServer
import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class HttpGuiDiagnosticsTest {
    @TempDir lateinit var root: Path
    private val lease = GuiLease("SESSION_SENTINEL", "TOKEN_SENTINEL")
    private fun text() = Files.list(root).use { paths ->
        paths.filter { it.toString().endsWith(".log") }.toList().joinToString("\n") { Files.readString(it) }
    }

    @Test fun encodingAndSizeFailuresAreDistinctAndDoNotSend() {
        val log = GuiDiagnosticLog(directory = { root })
        log.setEnabled(true)
        val port = HttpGuiChannelPort(URI("http://127.0.0.1:1"), GuiHttpLimits(maxRequestBytes = 64), diagnostics = log)
        assertThrows(ProtocolException::class.java) {
            port.complete(lease, CommandCompletion("REQUEST_SENTINEL", 3, Operation.META_SUMMARY, SelectionResult("SOURCE_SENTINEL")))
        }
        assertThrows(ProtocolException::class.java) {
            port.complete(lease, CommandCompletion("REQUEST_SENTINEL", 3, Operation.META_SUMMARY, SummaryResult()))
        }
        log.close()
        val output = text()
        assertTrue(output.contains("stage=complete_encode"))
        assertTrue(output.contains("event=request_size_check"))
        assertTrue(output.contains("reason=request_size_limit"))
        assertFalse(output.contains("event=http_response"))
        listOf("SESSION_SENTINEL", "TOKEN_SENTINEL", "REQUEST_SENTINEL", "SOURCE_SENTINEL").forEach { assertFalse(output.contains(it)) }
    }

    @Test fun httpStatusAndAckDecodeFailuresKeepSafeStagesAndOriginalCause() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val log = GuiDiagnosticLog(directory = { root })
        server.createContext("/") { exchange ->
            val index = calls.getAndIncrement()
            val body = when (index) { 0 -> "{"; 1 -> "{\"accepted\":false}"; 2 -> "{\"accepted\":true}"; else -> "PRIVATE_SENTINEL" }
            val bytes = body.toByteArray()
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(if (index == 3) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            log.setEnabled(true)
            val port = HttpGuiChannelPort(URI("http://127.0.0.1:${server.address.port}"), diagnostics = log)
            val completion = CommandCompletion("REQUEST_SENTINEL", 3, Operation.META_SUMMARY, SummaryResult())
            assertThrows(ProtocolException::class.java) { port.complete(lease, completion) }
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) { port.complete(lease, completion) }.error.code)
            port.complete(lease, completion)
            assertThrows(ProtocolException::class.java) { port.complete(lease, completion) }
        } finally { server.stop(0); log.close() }
        val output = text()
        listOf("stage=complete_ack_decode", "JsonSyntaxException", "accepted=false", "accepted=true", "status=503", "event=http_error_decode").forEach {
            assertTrue(output.contains(it), it)
        }
        assertFalse(output.contains("PRIVATE_SENTINEL"))
        assertFalse(output.contains("TOKEN_SENTINEL"))
    }

    @Test fun codecsKeepCauseWithoutChangingProtocolMessages() {
        val error = assertThrows(ProtocolException::class.java) { ProtocolCodec().decodeRequest("{") }
        assertNotNull(error.cause)
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.error.code)
        val transport = assertThrows(ProtocolException::class.java) { GuiTransportCodec().decodePollResult("{") }
        assertNotNull(transport.cause)
        assertEquals("Invalid GUI transport object", transport.error.message)
    }
}
