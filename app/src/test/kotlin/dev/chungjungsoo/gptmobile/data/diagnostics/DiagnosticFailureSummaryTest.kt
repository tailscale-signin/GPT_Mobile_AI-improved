package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticFailureSummaryTest {
    @Test fun typedToolResultFailureProvidesRecoveryWithoutInventingDispatchOrRevisionEvidence() {
        val summary = summary("CLIENT_TOOL_RESULTS_REQUIRED", emptyList())
        assertEquals("CLIENT_TOOL_RESULTS_REQUIRED", summary.getValue("failure_code").jsonPrimitive.content)
        assertEquals("RESUME_WITH_RETAINED_TOOL_RESULTS", summary.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("TERMINAL_ERROR", summary.getValue("field_sources").jsonObject.getValue("failure_code").jsonPrimitive.content)
        assertEquals("FAILURE_CODE_POLICY", summary.getValue("field_sources").jsonObject.getValue("recovery_action").jsonPrimitive.content)
        assertEquals(JsonNull, summary["tested_sha"])
        assertEquals(JsonNull, summary["invocation_id"])
        assertFalse(summary.containsKey("dispatched"))
    }

    @Test fun recordedRecoveryAndOutputStageRemainDistinctFromCompactEventPhase() {
        val event = DiagnosticFailureEvidence(
            "event",
            "VERIFICATION_EVENT",
            buildJsonObject {
                put("phase", "INTERRUPTED")
                put("task_state", "BLOCKED")
                put("failure_code", "CONVERSATION_CONFLICT")
            }
        )
        val output = DiagnosticFailureEvidence(
            "output",
            "OUTPUT_CHECKPOINT",
            buildJsonObject {
                put(
                    "failure_details",
                    buildJsonObject {
                        put("failed_stage", "message_admission")
                        put("recovery_action", "ATTACH_TO_ACTIVE_JOB")
                        put("invocation_id", "attempt")
                    }
                )
            }
        )
        val summary = summary(null, listOf(event, output))
        assertEquals("message_admission", summary.getValue("failed_stage").jsonPrimitive.content)
        assertEquals("ATTACH_TO_ACTIVE_JOB", summary.getValue("recovery_action").jsonPrimitive.content)
        assertEquals("OUTPUT_CHECKPOINT", summary.getValue("field_sources").jsonObject.getValue("recovery_action").jsonPrimitive.content)
    }

    @Test fun arbitraryErrorTextDoesNotBecomeAnInventedFailureCodeOrRecovery() {
        val summary = summary("The provider returned private unstructured details", emptyList())
        assertEquals("FAILED", summary.getValue("latest_verdict").jsonPrimitive.content)
        assertEquals(JsonNull, summary["failure_code"])
        assertEquals(JsonNull, summary["recovery_action"])
        assertFalse(summary.toString().contains("private unstructured details"))
    }

    private fun summary(error: String?, evidence: List<DiagnosticFailureEvidence>): JsonObject =
        diagnosticFailureSummary("run", "FAILED", error, "test", 141, "build", evidence, capturedAtMillis = 1)
}
