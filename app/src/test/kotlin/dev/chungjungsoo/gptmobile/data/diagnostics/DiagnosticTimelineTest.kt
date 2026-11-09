package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticTimelineTest {
    @Test fun capturedDrawRetainsItsRunClockAndPartWithoutInventingProviderOrDisplayTiming() {
        val draw = buildJsonObject {
            put("eventId", "draw")
            put("runId", "run")
            put("streamId", "process:run")
            put("processSession", "process")
            put("sequence", 2)
            put("capturedAtMillis", 123)
            put("monotonicNanos", 123_456_789L)
            put("kind", "FIRST_VISIBLE_TEXT")
            put("measured", true)
            put("fence", 7)
            put("outputPartId", "0:1")
        }
        val observation = requireNotNull(DiagnosticTimeline.journal(draw))
        assertEquals(JsonPrimitive("rendered_run_stream"), observation["measurementScope"])
        assertEquals(JsonPrimitive("COMPOSE_DRAW_CONTENT_RETURNED"), observation["observedBoundary"])
        assertEquals(JsonPrimitive("UNLINKED"), observation["invocationAssociation"])
        assertEquals(JsonPrimitive("UNKNOWN"), observation["outputOrigin"])
        assertEquals(JsonPrimitive(false), observation["providerLatencyCandidate"])
        assertEquals(JsonPrimitive("process"), observation["processSession"])
        assertEquals(JsonPrimitive(123_456_789L), observation["monotonicNanos"])
        assertEquals(JsonPrimitive(7L), observation["ownershipFence"])
        assertEquals(JsonPrimitive("0:1"), observation["outputPartId"])
        assertEquals(JsonNull, observation["displayPresentationConfirmed"])
        assertFalse(observation.containsKey("invocationId"))
        assertFalse(observation.containsKey("elapsedMillis"))
    }

    @Test fun systemOutputOriginSurvivesExportWithoutBecomingAProviderLatencyCandidate() {
        val event = RunDiagnosticEvent(
            eventId = "error", processSession = "process", runId = "run", streamId = "process:run", sequence = 1,
            capturedAtMillis = 123, kind = RunDiagnosticKind.FIRST_VISIBLE_TEXT, role = RunDiagnosticRole.UNKNOWN,
            measured = true, outputOrigin = OutputOrigin.SYSTEM_ERROR
        )
        val observation = DiagnosticTimeline.journal(event)
        assertEquals(JsonPrimitive("SYSTEM_ERROR"), observation["outputOrigin"])
        assertEquals(JsonPrimitive("UNLINKED"), observation["invocationAssociation"])
        assertEquals(JsonPrimitive(false), observation["providerLatencyCandidate"])
    }

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
