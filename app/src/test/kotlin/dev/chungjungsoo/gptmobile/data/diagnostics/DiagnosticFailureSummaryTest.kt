package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

    @Test fun activeReviewFailureOwnsItsInvocationAfterSuccessfulGeneration() {
        val review = reviewFailure(
            buildJsonObject {
                put("invocation_id", "review-attempt")
                put("failed_stage", "provider_generation")
                put("recovery_action", "CHANGE_REVIEW_CONFIGURATION")
            }
        )
        val summary = summary(null, listOf(successfulOutput(), review))
        assertEquals(JsonPrimitive("COMPLETION_REPAIR_BUDGET_EXHAUSTED"), summary["failure_code"])
        assertEquals(JsonPrimitive("review-attempt"), summary["invocation_id"])
        assertEquals(JsonPrimitive("provider_generation"), summary["failed_stage"])
        assertEquals(JsonPrimitive("CHANGE_REVIEW_CONFIGURATION"), summary["recovery_action"])
        assertEquals(JsonPrimitive("REVIEW_CHECKPOINT"), summary.getValue("field_sources").jsonObject["invocation_id"])
        assertEquals(JsonPrimitive("review"), summary.getValue("source_record_ids").jsonArray.first())
    }

    @Test fun missingReviewerProvenanceCannotBorrowSuccessfulPrimaryInvocationStageOrRecovery() {
        val summary = summary(null, listOf(successfulOutput(), reviewFailure()))
        assertEquals(JsonNull, summary["invocation_id"])
        assertEquals(JsonNull, summary["failed_stage"])
        assertEquals(JsonNull, summary["recovery_action"])
        assertEquals(JsonPrimitive("COMPLETION_REPAIR_BUDGET_EXHAUSTED"), summary["latest_verdict"])
    }

    @Test fun resolvedReviewCheckpointCannotOverrideNewerSuccessEvenWithRetainedFailureFields() {
        for ((state, reviewState) in listOf("REVIEWED" to "PASS", "COMPLETED" to "FAILED")) {
            val review = reviewFailure(
                buildJsonObject {
                    put("state", state)
                    put("review_state", reviewState)
                }
            )
            val summary = summary(null, listOf(review))
            assertEquals(JsonNull, summary["failure_code"])
            assertEquals(JsonPrimitive("FAILED"), summary["latest_verdict"])
            assertEquals(emptyList<Any>(), summary.getValue("source_record_ids").jsonArray.toList())
        }
        val verified = DiagnosticFailureEvidence("current", "VERIFICATION_CURRENT", buildJsonObject { put("completion_verified", true) })
        val summary = summary(null, listOf(reviewFailure(), verified))
        assertEquals(JsonPrimitive("VERIFIED"), summary["latest_verdict"])
        assertEquals(JsonNull, summary["failure_code"])
        assertEquals(listOf(JsonPrimitive("current")), summary.getValue("source_record_ids").jsonArray.toList())
    }

    @Test fun completedRunExcludesStaleUnresolvedReviewCheckpoint() {
        val summary = diagnosticFailureSummary("run", "COMPLETED", null, "test", 141, "build", listOf(reviewFailure()))
        assertEquals(JsonPrimitive("COMPLETED"), summary["latest_verdict"])
        assertEquals(JsonNull, summary["failure_code"])
    }

    @Test fun observedHeadCannotBecomeTestedRevisionAndDistinctExplicitRevisionIsPreserved() {
        val observed = "a".repeat(40)
        val tested = "b".repeat(40)
        val current = DiagnosticFailureEvidence("current", "VERIFICATION_CURRENT", buildJsonObject { put("head_sha", observed) })
        val untested = summary(null, listOf(current))
        assertEquals(JsonNull, untested["tested_sha"])
        assertEquals(JsonPrimitive("NOT_RETAINED"), untested["tested_sha_status"])
        assertEquals(JsonPrimitive(observed), untested["observed_head_sha"])
        val measured = summary(null, listOf(current.copy(payload = JsonObject(current.payload + ("tested_sha" to JsonPrimitive(tested))))))
        assertEquals(JsonPrimitive(tested), measured["tested_sha"])
        assertEquals(JsonPrimitive(observed), measured["observed_head_sha"])
    }

    private fun successfulOutput() = DiagnosticFailureEvidence(
        "output",
        "OUTPUT_CHECKPOINT",
        buildJsonObject {
            put("state", "COMPLETED")
            put("invocation_id", "successful-primary")
            put("failed_stage", "primary_stage_must_not_be_borrowed")
            put("recovery_action", "PRIMARY_RECOVERY_MUST_NOT_BE_BORROWED")
        }
    )

    private fun reviewFailure(extra: JsonObject = JsonObject(emptyMap())) = DiagnosticFailureEvidence(
        "review",
        "REVIEW_CHECKPOINT",
        JsonObject(
            buildJsonObject {
                put("state", "BLOCKED")
                put("review_state", "FAILED")
                put("delivery_state", "WITHHELD")
                put("failure_code", "COMPLETION_REPAIR_BUDGET_EXHAUSTED")
                put("review_stage", "answer_review")
            } + extra
        )
    )

    private fun summary(error: String?, evidence: List<DiagnosticFailureEvidence>): JsonObject =
        diagnosticFailureSummary("run", "FAILED", error, "test", 141, "build", evidence, capturedAtMillis = 1)
}
