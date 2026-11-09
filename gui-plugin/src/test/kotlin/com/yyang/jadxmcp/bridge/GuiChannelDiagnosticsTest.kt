package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuiChannelDiagnosticsTest {
    @Test fun untrustedErrorTextNeverAppearsInUiSummary() {
        val error = ProtocolError(ErrorCode.INVALID_ARGUMENT, "GUI channel register: token=private payload")
        assertFalse(safeGuiErrorSummary(error).contains("private"))
        assertFalse(guiFailureCategory(error).contains("private"))
    }
    @Test fun locallyGeneratedStageAndCategoryRemainVisible() {
        val message = "GUI channel register: invalid response JSON/schema"
        assertEquals(message, safeGuiErrorSummary(ProtocolError(ErrorCode.INVALID_ARGUMENT, message)))
    }
}
