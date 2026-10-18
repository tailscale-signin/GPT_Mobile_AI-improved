package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAdmissionDiagnosticsTest {
    @Test fun completeCallCountAndArgumentLimitHaveDifferentReceipts() {
        val count = ToolRoundAdmission()
        repeat(8) { assertTrue(count.admit(ProviderEvent.ToolCall("id-$it", "lookup", buildJsonObject {}))) }
        assertFalse(count.admit(ProviderEvent.ToolCall("ninth", "lookup", buildJsonObject {})))
        assertEquals("COMPLETE_CALL_COUNT", count.rejection!!.threshold)
        assertEquals(9, count.rejection!!.completeCallIds)
        assertEquals(8, count.rejection!!.allowedValue)
        val bytes = ToolRoundAdmission(maxArgumentBytes = 64)
        assertFalse(bytes.admit(ProviderEvent.ToolCall("one", "lookup", buildJsonObject { put("text", "x".repeat(1000)) })))
        assertEquals("ARGUMENT_BYTES_LOWER_BOUND", bytes.rejection!!.threshold)
        assertEquals(1, bytes.rejection!!.completeCallIds)
    }

    @Test fun fragmentsDoNotInflateToolSlotCounts() {
        val guard = ToolStreamGuard()
        repeat(100) {
            guard.slot("one")
            guard.append("x")
        }
        repeat(7) { guard.slot("other-$it") }
        val failure = runCatching { guard.slot("ninth") }.exceptionOrNull() as ToolBatchOversizeException
        assertEquals("STREAM_TOOL_SLOTS", failure.threshold)
        assertEquals(9, failure.observedValue)
        assertEquals(null, failure.completeCallIds)
    }
}
