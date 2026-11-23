package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure
import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRecoveryCheckpointProjectionTest {
    private val binding = GitHubEvidenceBook.digest("chat:profile:repository:accepted-task")

    @Test fun persistedRecoveryRefusalOverridesThePriorCooldownOnTheNextVerdictRead() {
        val previous = waitingOutput()
        val expected = GitHubProviderCheckpoint(binding, GitHubEvidenceBook.digest(previous.toString()))
        val failure = ProviderRecoveryFailure(
            "PROVIDER_WAIT_CHECKPOINT_CHANGED",
            ProviderRecoveryFailure.interruption(previous),
            JsonObject(
                ProviderRecoveryFailure.checkpointCorrelation(expected, previous, "TOOL_RECEIPTS_CHANGED") +
                    buildJsonObject {
                        put("settled_tool_count", 103)
                        put("checkpoint_validation_stage", "TOOL_JOURNAL")
                    }
            )
        )
        // ProviderWaitStore persists this exact projection after a refused resume.
        val blockedOutput = failure.block(previous)
        val oldVerdict = waitingVerdict()
        val updated = GitHubEffectiveRecovery.withOutputCheckpoint(oldVerdict, blockedOutput, binding)
        val decision = GitHubEffectiveRecovery.resolve(updated)
        assertEquals(GitHubEffectiveRecovery.Kind.INSPECT_FAILURE, decision.kind)
        assertEquals("REVIEW_SAVED_RECOVERY", decision.action)
        assertEquals(null, decision.retryAtMillis)
        assertFalse(decision.continueWork)
        assertEquals(blockedOutput["current_failure_code"], updated["current_failure_code"])
        assertEquals(blockedOutput["latest_recovery_failure"], updated["latest_recovery_failure"])
        assertEquals(oldVerdict["original_failure"], updated["original_failure"])
        assertEquals(JsonPrimitive("BLOCKED"), updated["task_outcome"])
        assertEquals(JsonPrimitive(false), updated["completion_verified"])
        val task = updated["task_recovery_checkpoint"] as JsonObject
        assertEquals(JsonPrimitive("BLOCKED_RECOVERY"), task["output_attempt_state"])
        assertEquals(JsonArray(listOf(JsonPrimitive("acceptance-test"))), task["pending_requirement_ids"])
        assertEquals(JsonPrimitive(false), task["task_complete"])
        assertEquals(JsonPrimitive(false), task["request_replay_allowed"])
    }

    @Test fun aBlockedOutputCannotBorrowAnotherTaskOrCheckpointBinding() {
        val blocked = ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED").block(waitingOutput())
        val wrongTask = JsonObject(blocked + ("context" to buildJsonObject { put("task_binding", "other-task") }))
        val wrongCheckpoint = JsonObject(
            blocked + (
                "latest_recovery_failure" to JsonObject(
                    (blocked["latest_recovery_failure"] as JsonObject) + ("checkpoint_binding" to JsonPrimitive("other-task"))
                )
                )
        )
        for (output in listOf(wrongTask, wrongCheckpoint, null)) {
            val updated = GitHubEffectiveRecovery.withOutputCheckpoint(waitingVerdict(), output, binding)
            assertFalse(updated.containsKey("current_failure_code"))
            assertFalse(updated.containsKey("latest_recovery_failure"))
            assertEquals(GitHubEffectiveRecovery.Kind.WAIT_FOR_PROVIDER, GitHubEffectiveRecovery.resolve(updated).kind)
        }
        val anotherVerdict = JsonObject(waitingVerdict() + ("binding" to JsonPrimitive("other-task")))
        val updated = GitHubEffectiveRecovery.withOutputCheckpoint(anotherVerdict, blocked, binding)
        assertFalse(updated.containsKey("current_failure_code"))
        assertEquals(anotherVerdict["binding"], updated["binding"])
    }

    @Test fun completedChildOutputCannotRetirePendingAcceptanceOrClaimAnExecutorAfterRestart() {
        val unfinished = JsonObject(
            (waitingVerdict() - setOf("original_failure", "failure_code", "retry_not_before_ms")) +
                buildJsonObject {
                    put("phase", "IN_PROGRESS")
                    put("resume_allowed", true)
                    put("response_delivered", true)
                    put(
                        "execution_ledger",
                        buildJsonObject {
                            put("recovery", "RESUME_UNFINISHED_WORK")
                            put("remaining_criteria", 1)
                        }
                    )
                }
        )
        val completed = JsonObject(waitingOutput() + mapOf("state" to JsonPrimitive("COMPLETED"), "resume_allowed" to JsonPrimitive(false)))
        val updated = GitHubEffectiveRecovery.withOutputCheckpoint(unfinished, completed, binding)
        assertTrue(GitHubEffectiveRecovery.resolve(updated).continueWork)
        assertEquals(JsonPrimitive(false), updated["completion_verified"])
        assertEquals(unfinished["remaining_requirements"], updated["remaining_requirements"])
        val task = updated["task_recovery_checkpoint"] as JsonObject
        assertEquals(JsonPrimitive("COMPLETED"), task["output_attempt_state"])
        assertEquals(JsonPrimitive(false), task["task_complete"])
        assertEquals(JsonPrimitive("UNCONFIRMED"), task["wake_owner"])
        assertEquals(JsonPrimitive(true), task["response_delivered"])
    }

    private fun waitingVerdict() = buildJsonObject {
        put("binding", binding)
        put("phase", "INTERRUPTED")
        put("task_outcome", "PARTIAL")
        put("completion_verified", false)
        put("evidence_retention_complete", true)
        put("response_delivered", false)
        put("failure_code", "429")
        put("remaining_requirements", JsonArray(listOf(buildJsonObject { put("id", "acceptance-test") })))
        put(
            "original_failure",
            buildJsonObject {
                put("code", "429")
                put("retry_not_before_ms", 5000)
            }
        )
    }

    private fun waitingOutput() = buildJsonObject {
        put("schema", "github.output-checkpoint.v1")
        put("state", "WAITING_PROVIDER_LIMIT")
        put("resume_allowed", true)
        put("failure_code", "429")
        put("context", buildJsonObject { put("task_binding", binding) })
    }
}
