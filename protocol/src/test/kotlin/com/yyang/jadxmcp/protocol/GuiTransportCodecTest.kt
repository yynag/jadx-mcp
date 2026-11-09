package com.yyang.jadxmcp.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuiTransportCodecTest {
    private val codec = GuiTransportCodec()
    @Test fun typedControlBodiesAndPolymorphicRepliesRoundTrip() {
        val lease = GuiLease("s", "secret")
        val registration = GuiRegistration("instance", "window", 4, BackendCapabilities(BackendKind.GUI,
            listOf(OperationCapability(Operation.GET_SELECTED_TEXT))))
        assertEquals(registration, codec.decodeRegistration(codec.encodeRegistration(registration)))
        assertEquals(lease, codec.decodeLease(codec.encodeLease(lease)))
        val poll = GuiPollRequest(lease, "q", 4, 12345)
        assertEquals(poll, codec.decodePollRequest(codec.encodePollRequest(poll)))
        val heartbeat = GuiHeartbeatRequest(lease, 5)
        assertEquals(heartbeat, codec.decodeHeartbeat(codec.encodeHeartbeat(heartbeat)))
        val frame = RequestFrame(SelectedTextRequest("s"), CallContext("q", 4, 12345))
        val reply = codec.decodePollResult(codec.encodePollResult(PollResult(PollStatus.COMMAND, frame)))
        assertEquals(frame, reply.command)
        val completion = GuiCompletionRequest(lease, CommandCompletion("q", 4, Operation.GET_SELECTED_TEXT, SelectionResult("text")))
        assertEquals(completion, codec.decodeCompletion(codec.encodeCompletion(completion)))
        val error = ProtocolError(ErrorCode.CLOSED, "Closed")
        assertEquals(error, codec.decodeError(codec.encodeError(error)))
    }
    @Test fun invalidControlShapesFailBeforeReachingTheSessionService() {
        assertThrows(ProtocolException::class.java) { codec.decodeLease("{}") }
        assertThrows(ProtocolException::class.java) { codec.decodeError("{}") }
        assertThrows(ProtocolException::class.java) { codec.decodeError("""{"error":{"message":"missing code"}}""") }
        assertThrows(ProtocolException::class.java) { codec.decodeRegistration("""{"instance_id":"i","name":"n","capabilities":{"backend":"HEADLESS"}}""") }
        assertThrows(ProtocolException::class.java) { codec.decodeHeartbeat("""{"lease":{"session_id":"s","token":"t"},"project_epoch":-1}""") }
        assertThrows(ProtocolException::class.java) { codec.decodePollResult("""{"status":"COMMAND"}""") }
        assertThrows(ProtocolException::class.java) { codec.decodePollResult("""{"status":"IDLE","unknown":true}""") }
        assertThrows(ProtocolException::class.java) { codec.encodePollResult(PollResult(PollStatus.IDLE,
            RequestFrame(SummaryRequest("s"), CallContext("q", 0, 1)))) }
    }
}
