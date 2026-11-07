package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubTaskFailureMetadataTest {
    @Test fun incompleteAcceptanceRetainsTaskContinuationWithoutProviderRetry() {
        for (code in listOf("GITHUB_TASK_INCOMPLETE", "GITHUB_EVIDENCE_REQUIRED", "GITHUB_EVIDENCE_RETENTION_INCOMPLETE")) {
            val failure = ProviderEvent.Failed("Required evidence is missing", errorCode = code, taskOutcome = "PARTIAL")
            val metadata = GitHubTaskLifecycle.failureMetadata(failure)
            assertEquals(JsonPrimitive("TASK_VERIFICATION"), metadata["failure_domain"])
            assertEquals(JsonPrimitive(true), metadata["continuation_required"])
            assertEquals(JsonPrimitive(false), metadata["retryable_without_new_input"])
            assertEquals(GitHubTaskLifecycle.State.PARTIAL, GitHubTaskLifecycle.progress(failure, 0))
            assertEquals(GitHubTaskLifecycle.State.BLOCKED, GitHubTaskLifecycle.progress(failure.copy(taskOutcome = "BLOCKED"), 1))
        }
    }

    @Test fun typedAmbiguousWriteCannotBecomeRetryableTimeout() {
        val failure = ProviderEvent.Failed("Request timed out", errorCode = "DISPATCH_OUTCOME_UNKNOWN")
        val metadata = GitHubTaskLifecycle.failureMetadata(failure)
        assertEquals(JsonPrimitive("tool_dispatch"), metadata["failed_stage"])
        assertEquals(JsonPrimitive(false), metadata["retryable_without_new_input"])
        assertEquals(GitHubTaskLifecycle.State.BLOCKED, GitHubTaskLifecycle.progress(failure, 1))
    }

    @Test fun missingStageIsUnrecordedAndDoesNotInventDispatchEvidence() {
        val metadata = GitHubTaskLifecycle.failureMetadata(ProviderEvent.Failed("Interrupted"))
        assertEquals(JsonPrimitive("stage_not_recorded"), metadata["failed_stage"])
        assertFalse(metadata.containsKey("failed_stage_dispatched"))
    }

    @Test fun confirmedCheckpointStageAndMissingRequirementsSurvive() {
        val missing = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("CURRENT_HEAD_TESTS")))
        val failure = ProviderEvent.Failed(
            "Acceptance evidence is incomplete",
            errorCode = "GITHUB_TASK_INCOMPLETE",
            taskOutcome = "PARTIAL",
            failureDetails = buildJsonObject {
                put("failed_stage", "ci_monitoring")
                put("dispatched", false)
                put("missing_requirements", missing)
            }
        )
        val metadata = GitHubTaskLifecycle.failureMetadata(failure)
        assertEquals(JsonPrimitive("ci_monitoring"), metadata["failed_stage"])
        assertEquals(JsonPrimitive(false), metadata["failed_stage_dispatched"])
        assertEquals(missing, metadata["missing_requirements"])
        assertEquals(GitHubTaskLifecycle.State.PARTIAL, GitHubTaskLifecycle.progress(failure, 1))
    }
}
