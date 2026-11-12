package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubProviderTerminalCheckpointTest {
    private val saved = Json.parseToJsonElement("""{"state":"WAITING_PROVIDER_LIMIT","resume_allowed":true,"user_input_required":false,"draft":"saved draft","completed_results":[{"call_id":"write-1","status":"SUCCEEDED"}],"failure_code":"429","failure_details":{"retryable":true,"http_status":429,"invocation_id":"prior-http-request"}}""") as JsonObject
    private val expected = GitHubProviderCheckpoint("b".repeat(64), MessageDigest.getInstance("SHA-256").digest(saved.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) })

    @Test fun terminalRecoveryFailureRetainsReceiptsAndDisablesAutomaticWake() {
        val result = requireNotNull(GitHubProviderTerminalCheckpoint.reconcile(saved, expected, "FAILED", "An unexpected error occurred: PROVIDER_WAIT_CHECKPOINT_CHANGED"))
        assertEquals(JsonPrimitive("FAILED"), result["state"])
        assertEquals(JsonPrimitive(false), result["resume_allowed"])
        assertEquals(JsonPrimitive(true), result["user_input_required"])
        assertEquals(JsonPrimitive("PROVIDER_WAIT_CHECKPOINT_CHANGED"), result["failure_code"])
        assertEquals(JsonPrimitive("429"), result["prior_interruption_code"])
        assertEquals(saved["failure_details"], result["prior_interruption_details"])
        val failure = result.getValue("failure_details") as JsonObject
        assertEquals(JsonPrimitive(false), failure["request_replay_allowed"])
        assertEquals(JsonPrimitive("provider_recovery"), failure["failed_stage"])
        assertNull(failure["invocation_id"])
        assertNull(failure["http_status"])
        assertEquals(saved["completed_results"], result["completed_results"])
        assertEquals(saved["draft"], result["draft"])
        assertNull(GitHubProviderTerminalCheckpoint.reconcile(result, expected, "FAILED", "PROVIDER_WAIT_CHECKPOINT_CHANGED"))
    }

    @Test fun aNewerCheckpointOrActiveRunIsNeverReconciledAsTerminal() {
        val newer = JsonObject(saved + ("draft" to JsonPrimitive("newer saved draft")))
        assertNull(GitHubProviderTerminalCheckpoint.reconcile(newer, expected, "FAILED", "PROVIDER_WAIT_CHECKPOINT_CHANGED"))
        for (status in listOf("QUEUED", "RUNNING", "WAITING_PROVIDER_LIMIT", "WAITING_REMOTE_JOB", "unknown")) {
            assertNull(GitHubProviderTerminalCheckpoint.reconcile(saved, expected, status, "PROVIDER_WAIT_CHECKPOINT_CHANGED"))
        }
    }

    @Test fun cancellationDisablesWakeWithoutRequestingApprovalOrRetainingPrivateErrorText() {
        val result = requireNotNull(GitHubProviderTerminalCheckpoint.reconcile(saved, expected, "CANCELED", "Private credential or URL was rejected"))
        assertEquals(JsonPrimitive(false), result["resume_allowed"])
        assertEquals(JsonPrimitive(false), result["user_input_required"])
        assertEquals(JsonPrimitive("NONE"), result["next_action"])
        assertEquals(JsonPrimitive("PROVIDER_WAIT_TERMINATED"), result["failure_code"])
    }

    @Test fun completedOwnerDoesNotRetainTheOldInterruptionAsItsCurrentFailure() {
        val result = requireNotNull(GitHubProviderTerminalCheckpoint.reconcile(saved, expected, "COMPLETED", null))
        assertEquals(JsonPrimitive("COMPLETED"), result["state"])
        assertEquals(JsonPrimitive(false), result["resume_allowed"])
        assertNull(result["failure_code"])
        assertNull(result["failure_details"])
        assertEquals(saved["failure_details"], result["prior_interruption_details"])
    }

    @Test fun incidentalGuardTextCannotManufactureTheTerminalCause() {
        for (code in listOf("PROVIDER_WAIT_CHECKPOINT_CHANGED", "RESEARCH_RECOVERY_ACTION_CHANGED", "USER_CANCELLED")) {
            for (text in listOf("Provider mentioned $code in its response", "An unexpected error occurred: Provider mentioned $code", "${code}_UNTRUSTED_SUFFIX")) {
                val result = requireNotNull(GitHubProviderTerminalCheckpoint.reconcile(saved, expected, "FAILED", text))
                assertEquals(JsonPrimitive("PROVIDER_WAIT_TERMINATED"), result["failure_code"])
                assertEquals(saved["failure_details"], result["prior_interruption_details"])
                assertEquals(saved["completed_results"], result["completed_results"])
            }
            for (text in listOf(code, "An unexpected error occurred: $code", "$code: Reconcile retained results", "$code; recovery stopped")) {
                val result = requireNotNull(GitHubProviderTerminalCheckpoint.reconcile(saved, expected, "FAILED", text))
                assertEquals(JsonPrimitive(code), result["failure_code"])
            }
        }
    }
}
