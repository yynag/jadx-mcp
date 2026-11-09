package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.HeadlessBackendFactory
import com.yyang.jadxmcp.session.SessionService
import kotlin.test.*

/** Default fences are captured before dispatch; management rechecks them at the atomic read/close. */
class CommandRegistryTest {
    @Test fun `default request IDs are short and explicit IDs are unchanged`() {
        SessionService(HeadlessBackendFactory { _, _ -> error("No headless load expected") }).use { sessions ->
            val registry = CommandRegistry(sessions)
            val first = registry.decode(Operation.SESSION_LIST, "{}").context.requestId
            val second = registry.decode(Operation.SESSION_LIST, "{}").context.requestId
            assertTrue(Regex("\\d{8}(?:-\\d+)?").matches(first))
            assertNotEquals(first, second)
            assertEquals("user/request-123", registry.decode(Operation.SESSION_LIST,
                """{"request_id":"user/request-123"}""").context.requestId)
        }
    }

    @Test fun `management defaults snapshot current epoch and reject a project change before dispatch`() {
        SessionService(HeadlessBackendFactory { _, _ -> error("No headless load expected") }).use { sessions ->
            val registry = CommandRegistry(sessions)
            val lease = sessions.register(GuiRegistration("registry-gui", "project.apk", 7))
            val arguments = """{"session_id":"${lease.sessionId}"}"""
            val close = registry.decode(Operation.SESSION_CLOSE, arguments)
            assertEquals(7L, close.context.projectEpoch)
            sessions.heartbeat(lease, 8)
            val failure = assertFailsWith<ProtocolException> { sessions.execute(close.request, close.context) }
            assertEquals(ErrorCode.CONFLICT, failure.error.code)
            val closed = ProtocolCodec().decodeResult(Operation.SESSION_CLOSE,
                registry.execute(Operation.SESSION_CLOSE, arguments)) as CloseSessionResult
            assertTrue(closed.closed)
            assertFalse((ProtocolCodec().decodeResult(Operation.SESSION_CLOSE,
                registry.execute(Operation.SESSION_CLOSE, arguments)) as CloseSessionResult).closed)
            assertFalse((ProtocolCodec().decodeResult(Operation.SESSION_CLOSE,
                registry.execute(Operation.SESSION_CLOSE, """{"session_id":"absent"}""")) as CloseSessionResult).closed)
        }
    }
}
