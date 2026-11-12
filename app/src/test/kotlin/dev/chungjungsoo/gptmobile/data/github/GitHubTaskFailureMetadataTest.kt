package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
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

    @Test fun acceptanceDetailsKeepExtractionAndUncertainOutcomePrecedenceWithoutInventingDispatch() {
        val verdict = buildJsonObject {
            put("failure_code", "GITHUB_EXTRACTION_OUTPUT_LIMIT")
            put(
                "verification_failure",
                buildJsonObject {
                    put("failed_stage", "claim_extraction")
                    put("batch", 2)
                }
            )
            put(
                "stop_reason",
                buildJsonObject {
                    put("code", "ACCEPTANCE_EVIDENCE_MISSING")
                    put("recovery_action", "RESUME_UNFINISHED_WORK")
                }
            )
            put(
                "execution_ledger",
                buildJsonObject {
                    put("required_criteria", 5)
                    put("verified_criteria", 4)
                    put("remaining_criteria", 1)
                    put("unresolved_remote_outcomes", 1)
                }
            )
        }
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict)
        assertEquals(JsonPrimitive("claim_extraction"), details["failed_stage"])
        assertEquals(JsonPrimitive(2), details["extraction_batch"])
        assertEquals(JsonPrimitive("ACCEPTANCE_EVIDENCE_MISSING"), details["stop_reason"])
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), details["recovery_action"])
        assertEquals(JsonPrimitive(5), details["required_criteria"])
        assertEquals(JsonPrimitive(4), details["verified_criteria"])
        assertEquals(JsonPrimitive(1), details["remaining_criteria"])
        assertFalse(details.containsKey("dispatched"))
        assertFalse(details.containsKey("automatic_continuations"))
        val unknown = GitHubTaskLifecycle.acceptanceFailureDetails(JsonObject(emptyMap()))
        assertFalse(unknown.containsKey("required_criteria"))
        assertFalse(unknown.containsKey("verified_criteria"))
        assertFalse(unknown.containsKey("remaining_criteria"))
    }

    @Test fun retainedPrimaryFailureKeepsItsStageAndCannotAcquireMissingDispatchFacts() {
        val review = buildJsonObject {
            put(
                "original_failure",
                buildJsonObject {
                    put("code", "GITHUB_REVIEW_UNAVAILABLE")
                    put("failed_stage", "answer_review")
                    put("dispatched", true)
                    put("retryable", false)
                    put("remote_acceptance", "YES")
                }
            )
            put("execution_ledger", buildJsonObject { put("recovery", "RESUME_VERIFICATION") })
        }
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(review)
        assertEquals(JsonPrimitive("answer_review"), details["failed_stage"])
        assertFalse(details.containsKey("dispatched"))
        assertFalse(details.containsKey("retryable"))
        assertFalse(details.containsKey("remote_acceptance"))
        assertEquals(JsonPrimitive("RESUME_VERIFICATION"), details["recovery_action"])
        val unrecorded = GitHubTaskLifecycle.acceptanceFailureDetails(buildJsonObject { put("original_failure", buildJsonObject { put("code", "NETWORK_TIMEOUT") }) })
        assertFalse(unrecorded.containsKey("failed_stage"))
        assertFalse(unrecorded.containsKey("dispatched"))
        assertFalse(unrecorded.containsKey("failure_domain"))
    }
}
