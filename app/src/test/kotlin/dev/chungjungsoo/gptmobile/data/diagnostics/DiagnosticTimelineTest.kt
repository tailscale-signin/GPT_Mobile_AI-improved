package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticTimelineTest {
    @Test fun runningChildOfPartialRunRequiresReconciliationWithoutInventingCompletion() {
        val result = requireNotNull(DiagnosticTimeline.database(invocation("RUNNING"), 1_800_000))
        assertEquals("REQUIRES_LIFECYCLE_RECONCILIATION", result["reconciliation"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, result["completedAtMillis"])
        assertEquals(1_700_000L, result["timestampMillis"]!!.jsonPrimitive.long)
        assertFalse(result.toString().contains("STUCK"))
    }

    @Test fun terminalInvocationNormalizesMillisecondsAndRetainsObservationBoundary() {
        val result = requireNotNull(DiagnosticTimeline.database(invocation("COMPLETED"), 1_800_000))
        assertEquals(1_701_234L, result["completedAtMillis"]!!.jsonPrimitive.long)
        assertEquals(1_800_000L, result["observedAtMillis"]!!.jsonPrimitive.long)
        assertEquals("invocation", result["invocationId"]!!.jsonPrimitive.content)
        assertEquals("run", result["runId"]!!.jsonPrimitive.content)
    }

    @Test fun toolReceiptEpochSecondsAndCallIdentityCanBeCorrelatedWithRun() {
        val row = buildJsonObject {
            put("table", "tool_events")
            put(
                "fields",
                buildJsonObject {
                    put("event_id", "receipt")
                    put("run_id", "run")
                    put("call_id", "call")
                    put("sequence", 107)
                    put("status", "COMPLETED")
                    put("started_at", 1700)
                    put("completed_at", 1702)
                }
            )
        }
        val result = requireNotNull(DiagnosticTimeline.database(row, 1_800_000))
        assertEquals(1_702_000L, result["timestampMillis"]!!.jsonPrimitive.long)
        assertEquals("call", result["callId"]!!.jsonPrimitive.content)
        assertEquals("tool_events:receipt", result["eventId"]!!.jsonPrimitive.content)
    }

    private fun invocation(status: String) = buildJsonObject {
        put("table", "model_invocations")
        put(
            "fields",
            buildJsonObject {
                put("id", "invocation")
                put("parentRunId", "run")
                put("parentRunStatus", "PARTIAL")
                put("status", status)
                put("startedAt", 1_700_000)
                put("durationMs", 1234)
            }
        )
    }
}
