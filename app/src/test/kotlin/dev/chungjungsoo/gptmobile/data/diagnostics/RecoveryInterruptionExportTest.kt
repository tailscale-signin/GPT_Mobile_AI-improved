package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryInterruptionExportTest {
    @Test fun continuationCauseUsesExistingPublicFieldsWithoutReplacingTheCurrentIncident() {
        val context = IssueReportContext.fromFields("CHAT_FAILURE", wrapper(), time)
        assertEquals(4, context.schemaVersion)
        assertEquals("PROVIDER_RATE_LIMITED", context.originalFailureCode)
        assertEquals(429, context.originalHttpStatus)
        assertEquals("final_synthesis", context.stage)
        assertEquals(false, context.dispatched)
        assertEquals(false, context.retryable)
        assertNull(context.httpStatus)
        assertNull(context.retryAfterMillis)
        assertNull(context.invocationIdHash)
        assertNull(context.reasonCode)
        val public = IssueReport.json.encodeToJsonElement(IssueReportContext.serializer(), context)
        assertTrue(IssueReportContext.validDiagnosticsIn(public))
        assertEquals(JsonPrimitive("PROVIDER_RATE_LIMITED"), public.jsonObject["originalFailureCode"])
        assertFalse(public.toString().contains("private"))
        assertFalse(public.toString().contains("original_interruption"))
        assertFalse(public.toString().contains("latest_interruption"))
        assertFalse(public.toString().contains("retry_at_ms"))
    }

    @Test fun publicCauseAlsoAcceptsBoundedMapEvidenceWithoutARecoveryRefusalCode() {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf("failed_stage" to "delivery", "original_interruption" to mapOf("code" to 429, "details" to mapOf("http_status" to 429L))),
            time
        )
        assertEquals(4, context.schemaVersion)
        assertEquals("PROVIDER_RATE_LIMITED", context.originalFailureCode)
        assertEquals(429, context.originalHttpStatus)
        assertEquals("delivery", context.stage)
        assertNull(context.httpStatus)
        assertNull(context.reasonCode)
    }

    @Test fun malformedHistoricalFactsCannotEnterThePublicContext() {
        for ((code, status) in listOf("private-provider-token" to "429", "x".repeat(97) to 600, 401 to -1)) {
            val context = IssueReportContext.fromFields(
                "CHAT_FAILURE",
                mapOf("failed_stage" to "final_synthesis", "original_interruption" to mapOf("code" to code, "details" to mapOf("http_status" to status))),
                time
            )
            assertNull(context.originalFailureCode)
            assertNull(context.originalHttpStatus)
            assertEquals("final_synthesis", context.stage)
        }
    }

    @Test fun savedContinuationFailureRetainsBothCausesAndTheNoWaitReasonWithoutRetryAuthority() {
        val failure = ProfileFailureRecord.fromProvider("Continuation stopped", "OUTPUT_CONTINUATION_UNAVAILABLE", wrapper())
        val saved = Json.decodeFromString(ProfileFailureRecord.serializer(), Json.encodeToString(ProfileFailureRecord.serializer(), failure))
        assertEquals("OUTPUT_CONTINUATION_UNAVAILABLE", saved.category)
        assertEquals("final_synthesis", saved.phase)
        assertFalse(saved.retryAllowed)
        assertEquals(false, saved.requestReplayAllowed)
        assertNull(saved.retryAfterMillis)
        assertNull(saved.invocationId)
        val retained = requireNotNull(saved.recoveryDetails)
        assertEquals(JsonPrimitive("RETRY_DEADLINE_UNAVAILABLE"), retained["provider_wait_no_wait_reason"])
        assertEquals(JsonPrimitive("429"), retained.getValue("original_interruption").jsonObject["code"])
        val original = retained.getValue("original_interruption").jsonObject.getValue("details").jsonObject
        assertEquals(JsonPrimitive("model_invocation"), original["failed_stage"])
        assertEquals(JsonPrimitive(time + 5_000), original["retry_at_ms"])
        assertEquals(JsonPrimitive("private-first-invocation"), original["invocation_id"])
        assertEquals(JsonPrimitive("NETWORK_TIMEOUT"), retained.getValue("latest_interruption").jsonObject["code"])
        assertFalse(retained.toString().contains("private-message-body"))
        assertFalse(retained.toString().contains("private-authorization"))
        assertFalse(retained.containsKey("recovery_code"))
        assertFalse(retained.containsKey("failure_origin"))
    }

    @Test fun uncertainWriteStillBlocksReplayWhenItsCauseChainIsRetained() {
        val details = JsonObject(wrapper() + mapOf("dispatched" to JsonPrimitive(true), "requires_reconciliation" to JsonPrimitive(true)))
        val failure = ProfileFailureRecord.fromProvider("An earlier write has no result", "GATEWAY_OUTCOME_UNKNOWN", details)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.category)
        assertFalse(failure.retryAllowed)
        assertEquals(false, failure.requestReplayAllowed)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
        assertEquals(JsonPrimitive("429"), requireNotNull(failure.recoveryDetails).getValue("original_interruption").jsonObject["code"])
    }

    @Test fun storedMessageAndCompactExportKeepTheChainWithoutInventingALifecycleRefusal() {
        val failure = ProfileFailureRecord.fromProvider("Continuation stopped", "OUTPUT_CONTINUATION_UNAVAILABLE", wrapper())
        val timeline = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "NOTICE")
                    put("modelAuthored", false)
                    put("profileFailure", Json.encodeToJsonElement(ProfileFailureRecord.serializer(), failure))
                }
            )
        )
        val evidence = requireNotNull(diagnosticMessageFailureEvidence(14, timeline))
        assertFalse(evidence.payload.containsKey("latest_recovery_failure"))
        val summary = diagnosticFailureSummary("run", "PARTIAL", null, "test", 1, "build", listOf(evidence), capturedAtMillis = time)
        assertEquals(JsonPrimitive("OUTPUT_CONTINUATION_UNAVAILABLE"), summary["failure_code"])
        assertEquals(JsonPrimitive("final_synthesis"), summary["failed_stage"])
        assertEquals(JsonPrimitive(false), summary["retryable"])
        assertEquals(JsonNull, summary["invocation_id"])
        assertEquals(ProviderRecoveryFailure.retainedInterruption(wrapper()["original_interruption"] as JsonObject), summary["original_interruption"])
        assertEquals(ProviderRecoveryFailure.retainedInterruption(wrapper()["latest_interruption"] as JsonObject), summary["latest_interruption"])
        assertFalse(summary.toString().contains("private-message-body"))
        assertFalse(summary.toString().contains("private-authorization"))
    }

    @Test fun anotherFailureOrACompletedRunCannotLendItsHistoricalCause() {
        val current = DiagnosticFailureEvidence("current", "VERIFICATION_CURRENT", buildJsonObject { put("failure_code", "GITHUB_EVIDENCE_REQUIRED") })
        val previous = DiagnosticFailureEvidence("previous", "MESSAGE_PROFILE_FAILURE", JsonObject(wrapper() + ("failure_code" to JsonPrimitive("OUTPUT_CONTINUATION_UNAVAILABLE"))))
        val unrelated = diagnosticFailureSummary("run", "PARTIAL", null, "test", 1, "build", listOf(current, previous), capturedAtMillis = time)
        assertEquals(JsonPrimitive("GITHUB_EVIDENCE_REQUIRED"), unrelated["failure_code"])
        assertFalse(unrelated.containsKey("original_interruption"))
        assertFalse(unrelated.containsKey("latest_interruption"))
        val completed = diagnosticFailureSummary("run", "COMPLETED", null, "test", 1, "build", listOf(previous), capturedAtMillis = time)
        assertFalse(completed.containsKey("original_interruption"))
        assertFalse(completed.containsKey("latest_interruption"))
    }

    private val time = 1_800_000_000_000L

    private fun wrapper(): JsonObject = buildJsonObject {
        put("failed_stage", "final_synthesis")
        put("failure_origin", "local_recovery")
        put("dispatched", false)
        put("retryable", false)
        put("request_replay_allowed", false)
        put("provider_wait_no_wait_reason", "RETRY_DEADLINE_UNAVAILABLE")
        put(
            "original_interruption",
            buildJsonObject {
                put("code", "429")
                put("message", "private-message-body")
                put(
                    "details",
                    buildJsonObject {
                        put("failed_stage", "model_invocation")
                        put("http_status", 429)
                        put("retry_at_ms", time + 5_000)
                        put("invocation_id", "private-first-invocation")
                        put("authorization", "private-authorization")
                    }
                )
            }
        )
        put(
            "latest_interruption",
            buildJsonObject {
                put("code", "NETWORK_TIMEOUT")
                put(
                    "details",
                    buildJsonObject {
                        put("failed_stage", "response_stream")
                        put("invocation_id", "private-last-invocation")
                    }
                )
            }
        )
    }
}
