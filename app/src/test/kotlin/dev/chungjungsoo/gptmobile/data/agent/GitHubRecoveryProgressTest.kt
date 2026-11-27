package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubRecoveryProgressTest {
    @Test fun aLegacyCooldownOrSuccessfulToolHandoffDoesNotInheritTheConnectionCounter() {
        for (state in listOf("WAITING_PROVIDER_LIMIT", "TOOL_HANDOFF", "TOOL_RESULTS_READY", "WAITING_FOR_CONNECTION")) {
            val checkpoint = buildJsonObject {
                put("state", state)
                put("resume_kind", "AFTER_TOOL_RESULTS")
                put("recovery_revision", "same-run")
                put("interruption_attempts", 1)
                put("original_interruption", buildJsonObject { put("code", "429") })
            }
            val recovery = GitHubRecoveryProgress(checkpoint, "same-run")
            assertEquals(state, 0, recovery.consecutiveTransportRecoveries)
            assertFalse(GitHubRecoveryProgress.resumeCause(checkpoint) == GitHubRecoveryProgress.Cause.TRANSPORT_INTERRUPTION)
        }
    }

    @Test fun aClaimedTransportRetrySurvivesProcessRestartAndOnlySuccessfulProgressResetsIt() {
        val previous = buildJsonObject {
            put("state", "INTERRUPTED")
            put("failure_code", "CONNECTION_ABORTED")
            put("recovery_revision", "same-run")
        }
        val first = GitHubRecoveryProgress(previous, "same-run")
        first.claimTransportRecovery()
        val claimed = JsonObject(previous + first.checkpoint("GENERATING", null, outputContinuation = false, noOutputRepair = false))
        val restarted = GitHubRecoveryProgress(claimed, "same-run")
        assertEquals(1, restarted.consecutiveTransportRecoveries)
        assertEquals(1, restarted.transportRecoveries)
        restarted.providerWait(releaseTransportClaim = false)
        assertEquals(1, restarted.consecutiveTransportRecoveries)
        restarted.completedBoundary(toolHandoff = true)
        assertEquals(0, restarted.consecutiveTransportRecoveries)
        assertEquals(1, restarted.transportRecoveries)
        val handoff = restarted.checkpoint("TOOL_HANDOFF", null, outputContinuation = false, noOutputRepair = false)
        assertEquals(JsonPrimitive(1), handoff["provider_waits"])
        assertEquals(JsonPrimitive(1), handoff["tool_handoffs"])
        assertEquals(JsonPrimitive("TOOL_HANDOFF"), handoff["recovery_cause"])
    }

    @Test fun outputLengthAndEmptyOutputRepairsHaveTheirOwnCauses() {
        assertEquals(
            GitHubRecoveryProgress.Cause.OUTPUT_LENGTH,
            GitHubRecoveryProgress.resumeCause(
                buildJsonObject {
                    put("state", "OUTPUT_LIMIT")
                    put("resume_kind", "OUTPUT_CONTINUATION")
                }
            )
        )
        assertEquals(
            GitHubRecoveryProgress.Cause.NO_OUTPUT,
            GitHubRecoveryProgress.resumeCause(
                buildJsonObject {
                    put("state", "NO_OUTPUT_REPAIR")
                    put("resume_kind", "AFTER_TOOL_RESULTS")
                }
            )
        )
        for (code in listOf("429", "TLS_FAILURE", "CLIENT_CANCELLED", "CONTEXT_CAPACITY_EXCEEDED")) {
            assertFalse(GitHubTaskRecovery.isConnectionUnavailable(ProviderEvent.Failed("LOCAL_SERVICE_UNREACHABLE", errorCode = code)))
        }
    }
}
