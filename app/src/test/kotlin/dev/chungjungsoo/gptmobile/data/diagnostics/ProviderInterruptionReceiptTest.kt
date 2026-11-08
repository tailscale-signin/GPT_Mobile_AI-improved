package dev.chungjungsoo.gptmobile.data.diagnostics

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
