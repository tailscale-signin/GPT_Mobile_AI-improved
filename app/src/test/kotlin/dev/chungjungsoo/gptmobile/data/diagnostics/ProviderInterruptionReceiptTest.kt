package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderInterruptionReceiptTest {
    @Test fun completedProviderWithPendingReconciliationIsNotClassifiedAsDeliveryOrTransportFailure() {
        for (stage in listOf("tool_reconciliation", "stage_not_recorded")) {
            val failure = ProviderEvent.Failed(
                "Completed provider output is waiting for durable tool receipts.",
                errorCode = "OUTPUT_CONTINUATION_PENDING_TOOLS",
                transportComplete = true,
                failureDetails = buildJsonObject {
                    put("failed_stage", stage)
                    put("generation_completed", true)
                    put("dispatched", true)
                    put("remote_acceptance", "YES")
                    put("requires_reconciliation", true)
                    put("request_replay_allowed", false)
                    put("logical_continuation_allowed", false)
                }
            )
            val receipt = ProviderFailureReceipt.from(PlatformV2(uid = "profile", name = "Selected", model = "chosen-model"), "github-delivery", failure, ProviderEvent.Usage(finishReason = "tool_calls"))
            assertTrue(receipt.valid())
            assertEquals(ProviderFailureStage.CONTINUATION, receipt.stage)
            assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", receipt.errorCode)
            assertTrue(receipt.transportComplete)
            assertEquals(true, receipt.generationCompleted)
            assertEquals(ProviderFinishCategory.TOOL_CALLS, receipt.finishReason)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
        }
    }

    @Test fun acceptedUnknownToolOutcomeRetainsItsIdentityWithoutReopeningEitherRetryPath() {
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN")) {
            val details = buildJsonObject {
                put("http_status", 200)
                put("failed_stage", "gateway_dispatch")
                put("dispatched", true)
                put("remote_acceptance", "YES")
                put("server_request_id", "req-accepted")
                put("generation_id", "gen-accepted")
                put("generation_completed", false)
                put("upstream_dispatched", false)
                put("upstream_failed_stage", "backend_admission")
                put("dispatch_state", "PRECHECK_REJECTED")
                // Contradictory retry advice cannot clear an unknown tool outcome.
                put("request_replay_allowed", true)
                put("logical_continuation_allowed", true)
                put("cause_types", JsonArray(listOf(JsonPrimitive("java.io.EOFException"), JsonPrimitive("Authorization: private"))))
            }
            val failure = ProviderEvent.Failed("Server reported an error; reconcile the command.", errorCode = code, failureDetails = details)
            val classified = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
            val restored = kotlinx.serialization.json.Json.decodeFromString<ProfileFailureRecord>(kotlinx.serialization.json.Json.encodeToString(ProfileFailureRecord.serializer(), classified))
            assertEquals(code, restored.category)
            assertFalse(restored.retryAllowed)
            assertEquals(true, restored.dispatched)
            assertEquals(RemoteRequestAcceptance.YES, restored.remoteAcceptance)
            assertEquals("req-accepted", restored.serverRequestId)
            assertEquals(false, restored.generationCompleted)
            assertEquals(false, restored.requestReplayAllowed)
            assertEquals(listOf("java.io.EOFException"), restored.causeTypes)

            val receipt = ProviderFailureReceipt.from(PlatformV2(uid = "profile", name = "Selected", model = "chosen-model"), "primary", failure)
            assertTrue(receipt.valid())
            assertEquals(code, receipt.errorCode)
            assertEquals(true, receipt.dispatched)
            assertEquals(RemoteRequestAcceptance.YES, receipt.remoteAcceptance)
            assertEquals("req-accepted", receipt.serverRequestId)
            assertEquals("gen-accepted", receipt.generationId)
            assertEquals(false, receipt.generationCompleted)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
            assertEquals(listOf("java.io.EOFException"), receipt.causeTypes)
        }
    }

    @Test fun laterNegativeAdmissionCannotReclassifyAnUnknownToolOperationAsUnsent() {
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN")) {
            val failure = ProviderEvent.Failed(
                "$code: reconcile the previous operation",
                errorCode = "CIRCUIT_BREAKER_OPEN",
                failureDetails = buildJsonObject {
                    put("failed_stage", "provider_admission")
                    put("dispatched", false)
                    put("remote_acceptance", "NO")
                    put("request_replay_allowed", true)
                    put("logical_continuation_allowed", true)
                }
            )
            val receipt = ProviderFailureReceipt.from(PlatformV2(uid = "profile", name = "Selected", model = "chosen-model"), "primary", failure)
            assertFalse(receipt.dispatched == false)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, receipt.remoteAcceptance)
            assertEquals(false, receipt.requestReplayAllowed)
            assertEquals(false, receipt.logicalContinuationAllowed)
        }
    }

    @Test fun reasoningOnlyStreamRetainsTheCanonicalFailureAndAcceptanceSeparatelyFromCompletion() {
        val failure = ProviderEvent.Failed(
            "Localized interruption message",
            errorCode = "CONNECTION_ABORTED",
            failureDetails = buildJsonObject {
                put("http_status", 200)
                put("failed_stage", "response_stream")
                put("dispatched", true)
                put("remote_acceptance", "YES")
                put("server_request_id", "req-17")
                put("generation_id", "gen-29")
                put("generation_completed", false)
                put("request_replay_allowed", false)
                put("logical_continuation_allowed", true)
                put("cause_types", JsonArray(listOf(JsonPrimitive("java.io.EOFException"))))
            }
        )
        val receipt = ProviderFailureReceipt.from(PlatformV2(uid = "profile", name = "Selected", model = "chosen-model"), "github-output-continuation", failure)
        assertTrue(receipt.valid())
        assertEquals("CONNECTION_ABORTED", receipt.errorCode)
        assertEquals(ProviderFailureStage.CONTINUATION, receipt.stage)
        assertEquals(RemoteRequestAcceptance.YES, receipt.remoteAcceptance)
        assertEquals("req-17", receipt.serverRequestId)
        assertEquals("gen-29", receipt.generationId)
        assertEquals(false, receipt.generationCompleted)
        assertEquals(false, receipt.requestReplayAllowed)
        assertEquals(true, receipt.logicalContinuationAllowed)
        assertFalse(receipt.transportComplete)
    }

    @Test fun noDispatchRejectionIsAdmissionAndArbitraryCauseTextCannotEnterTheReceipt() {
        val failure = ProviderEvent.Failed(
            "Unavailable",
            errorCode = "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
            failureDetails = buildJsonObject {
                put("failed_stage", "connection_preflight")
                put("dispatched", false)
                put("remote_acceptance", "NO")
                put("server_request_id", "Authorization: private")
                put("cause_types", JsonArray(listOf(JsonPrimitive("https://private.example?token=secret"), JsonPrimitive("java.io.IOException"))))
            }
        )
        val receipt = ProviderFailureReceipt.from(PlatformV2(uid = "profile", name = "Selected", model = "chosen-model"), "primary", failure)
        assertTrue(receipt.valid())
        assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
        assertEquals(false, receipt.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, receipt.remoteAcceptance)
        assertEquals(null, receipt.serverRequestId)
        assertEquals(listOf("java.io.IOException"), receipt.causeTypes)
    }
}
