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
        assertEquals(JsonPrimitive("tool_reconciliation"), details["failed_stage"])
        assertFalse(details.containsKey("reason_code"))
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

    @Test fun exactExtractionCauseAndActionSurviveDeliveryWithoutBorrowingHistoricalDispatch() {
        val extraction = buildJsonObject {
            put("failed_stage", "claim_extraction")
            put("code", "GITHUB_EXTRACTION_INVALID_RESPONSE")
            put("recovery_action", "RESUME_THIS_BATCH_WITH_STRICT_SCHEMA")
            put("dispatched", true)
            put("invocation_id", "historical-extractor")
        }
        val verdict = buildJsonObject {
            put("verification_failure", extraction)
            put(
                "stop_reason",
                buildJsonObject {
                    put("code", "ACCEPTANCE_EVIDENCE_MISSING")
                    put("recovery_action", "RESUME_UNFINISHED_WORK")
                }
            )
        }
        val fresh = GitHubTaskLifecycle.acceptanceFailureDetails(verdict)
        assertEquals(JsonPrimitive(true), fresh["dispatched"])
        assertEquals(extraction["invocation_id"], fresh["invocation_id"])
        assertFalse(fresh.containsKey("http_status"))
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict, retainedReport = true)
        assertEquals(JsonPrimitive("GITHUB_EXTRACTION_INVALID_RESPONSE"), details["reason_code"])
        assertEquals(JsonPrimitive("RESUME_THIS_BATCH_WITH_STRICT_SCHEMA"), details["recovery_action"])
        assertFalse(details.containsKey("dispatched"))
        assertFalse(details.containsKey("invocation_id"))
        val primary = JsonObject(
            verdict + (
                "original_failure" to buildJsonObject {
                    put("code", "NETWORK_TIMEOUT")
                    put("failed_stage", "transport")
                }
                )
        )
        val original = GitHubTaskLifecycle.acceptanceFailureDetails(primary)
        assertEquals(JsonPrimitive("transport"), original["failed_stage"])
        assertFalse(original.containsKey("reason_code"))
        assertFalse(original["recovery_action"] == extraction["recovery_action"])
    }

    @Test fun retainedReviewRecoveryKeepsExplicitActionAndUncertainOutcomePrecedence() {
        val original = buildJsonObject {
            put("failed_stage", "review_comparison")
            put("next_action", "SELECT_REVIEW_BASELINE")
            put("dispatched", true)
            put("invocation_id", "private-past-invocation")
        }
        val verdict = buildJsonObject {
            put("original_failure", original)
            put("execution_ledger", buildJsonObject { put("recovery", "RESUME_VERIFICATION") })
        }
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict)
        assertEquals(JsonPrimitive("review_comparison"), details["failed_stage"])
        assertEquals(JsonPrimitive("SELECT_REVIEW_BASELINE"), details["recovery_action"])
        assertFalse(details.containsKey("dispatched"))
        assertFalse(details.containsKey("invocation_id"))
        val explicit = JsonObject(verdict + ("original_failure" to JsonObject(original + ("recovery_action" to JsonPrimitive("RESTORE_REVIEW_COMPARISON_EVIDENCE")))))
        assertEquals(JsonPrimitive("RESTORE_REVIEW_COMPARISON_EVIDENCE"), GitHubTaskLifecycle.acceptanceFailureDetails(explicit)["recovery_action"])
        val unresolved = JsonObject(explicit + ("execution_ledger" to buildJsonObject { put("unresolved_remote_outcomes", 1) }))
        val reconcile = GitHubTaskLifecycle.acceptanceFailureDetails(unresolved)
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), reconcile["recovery_action"])
        assertFalse(reconcile.containsKey("dispatched"))
        assertFalse(reconcile.containsKey("invocation_id"))
    }
}
