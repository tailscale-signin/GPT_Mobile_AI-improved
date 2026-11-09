package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTypedRecoveryTest {
    private fun interruption() = ProviderEvent.Failed(
        "The provider's localized explanation contains no transport keywords.",
        errorCode = "CONNECTION_ABORTED",
        failureDetails = buildJsonObject {
            put("failed_stage", "response_stream")
            put("dispatched", true)
            put("remote_acceptance", "YES")
            put("server_request_id", "req-17")
            put("generation_completed", false)
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", true)
        }
    )

    @Test fun typedInterruptionContinuesSavedWorkWithoutChangingTheWireReplayDecision() {
        val failure = interruption()
        assertEquals(1000L, GitHubTaskRecovery.delayMillis(failure, 0))
        assertNull(GitHubTaskRecovery.delayMillis(failure, 1))
        val retained = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
        assertEquals("CONNECTION_ABORTED", retained.category)
        assertEquals("response_stream", retained.phase)
        assertEquals(RemoteRequestAcceptance.YES, retained.remoteAcceptance)
        assertEquals("req-17", retained.serverRequestId)
        assertTrue(retained.retryAllowed)
        assertEquals(false, retained.requestReplayAllowed)
        assertEquals(false, retained.generationCompleted)
    }

    @Test fun unknownMutationApprovalAndCancellationCannotBecomeLogicalRetries() {
        val failure = interruption()
        assertNull(GitHubTaskRecovery.delayMillis(failure, 0, hasUnknownOutcome = true))
        assertNull(GitHubTaskRecovery.delayMillis(failure.copy(errorCode = "WAITING_FOR_APPROVAL", taskOutcome = "BLOCKED"), 0))
        val approval = ProfileFailureRecord.fromProvider("Review the saved staged change", "WAITING_FOR_APPROVAL")
        assertEquals("WAITING_FOR_APPROVAL", approval.category)
        assertFalse(approval.retryAllowed)
        assertNull(GitHubTaskRecovery.delayMillis(failure.copy(errorCode = "TLS_FAILURE"), 0))
        assertNull(GitHubTaskRecovery.delayMillis(failure.copy(errorCode = "CLIENT_CANCELLED"), 0))
        for (code in listOf("TLS_FAILURE", "CLIENT_CANCELLED", "GATEWAY_ENGINEERING_GITHUB_REQUIRED", "GITHUB_REVIEW_INCOMPATIBLE")) {
            assertNull(GitHubTaskRecovery.delayMillis(failure.copy(message = "connection reset; unexpected end of stream", errorCode = code), 0))
        }
        assertNull(GitHubTaskRecovery.delayMillis(failure.copy(failureDetails = buildJsonObject { put("logical_continuation_allowed", false) }), 0))
        assertNull(GitHubTaskRecovery.delayMillis(failure.copy(failureDetails = buildJsonObject { put("requires_reconciliation", true) }), 0))
    }

    @Test fun admissionAndReviewKeepTheOriginalPhaseAndNeverInferAcceptanceFromOlderWork() {
        val rejected = ProfileFailureRecord.fromProvider(
            "Service setup required",
            "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
            buildJsonObject {
                put("failed_stage", "connection_preflight")
                put("dispatched", false)
                put("remote_acceptance", "NO")
                put("review_stage", "review_compatibility")
            }
        )
        assertFalse(rejected.retryAllowed)
        assertEquals("connection_preflight", rejected.phase)
        assertEquals(RemoteRequestAcceptance.NO, rejected.remoteAcceptance)
        val review = ProfileFailureRecord.fromProvider("Review request unavailable", details = buildJsonObject { put("failed_stage", JsonPrimitive("review_compatibility")) })
        assertEquals("review_compatibility", review.phase)
    }
}
