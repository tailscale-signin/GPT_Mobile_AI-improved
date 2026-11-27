package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEffectiveRecoveryTest {
    @Test fun completedClaimExtractionLimitAllowsSmallerBatchResumeWithoutReplayingProviderRequest() {
        val action = "RESUME_THIS_BATCH_WITH_A_SMALLER_CLAIM_LIMIT"
        val decision = GitHubEffectiveRecovery.resolve(
            json("""{"verification_failure":{"code":"GITHUB_EXTRACTION_OUTPUT_LIMIT","failed_stage":"claim_extraction","transport_complete":true,"remote_acceptance":"YES","logical_continuation_allowed":false,"request_replay_allowed":false,"recovery_action":"$action"},"evidence_retention_complete":true}""")
        )

        assertEquals(GitHubEffectiveRecovery.Kind.CONTINUE_WORK, decision.kind)
        assertTrue(decision.continueWork)
        assertEquals(action, decision.action)
        assertEquals(JsonPrimitive(false), decision.json()["request_replay_allowed"])
    }

    @Test fun claimExtractionRepairStillRequiresCompletedRetainedEvidenceAndKnownAcceptance() {
        val action = "RESUME_THIS_BATCH_WITH_A_SMALLER_CLAIM_LIMIT"
        val blocked = listOf(
            json("""{"verification_failure":{"code":"GITHUB_EXTRACTION_OUTPUT_LIMIT","failed_stage":"claim_extraction","transport_complete":false,"remote_acceptance":"YES","logical_continuation_allowed":false,"recovery_action":"$action"},"evidence_retention_complete":true}"""),
            json("""{"verification_failure":{"code":"GITHUB_EXTRACTION_OUTPUT_LIMIT","failed_stage":"claim_extraction","transport_complete":true,"remote_acceptance":"UNKNOWN","logical_continuation_allowed":false,"recovery_action":"$action"},"evidence_retention_complete":true}"""),
            json("""{"verification_failure":{"code":"GITHUB_EXTRACTION_OUTPUT_LIMIT","failed_stage":"claim_extraction","transport_complete":true,"remote_acceptance":"YES","logical_continuation_allowed":false,"recovery_action":"$action"},"evidence_retention_complete":false}"""),
            json("""{"verification_failure":{"code":"GITHUB_EXTRACTION_OUTPUT_LIMIT","failed_stage":"claim_extraction","transport_complete":true,"remote_acceptance":"YES","automatic_retry_allowed":false,"logical_continuation_allowed":false,"recovery_action":"$action"},"evidence_retention_complete":true}""")
        )

        blocked.forEach { report ->
            val decision = GitHubEffectiveRecovery.resolve(report)
            assertEquals(GitHubEffectiveRecovery.Kind.INSPECT_FAILURE, decision.kind)
            assertFalse(decision.continueWork)
        }
    }

    private fun fault() = buildJsonObject {
        put("failure_code", "GITHUB_VERIFICATION_INTERNAL_ERROR")
        put("task_outcome", "PARTIAL")
        put("evidence_retention_complete", true)
        put("resume_allowed", false)
        put("retryable_without_new_input", false)
        put("execution_ledger", buildJsonObject { put("recovery", "INSPECT_VERIFICATION_FAILURE") })
    }

    @Test fun postBodyFailureUsesOneInspectionActionWithTheRetainedReads() {
        val original = buildJsonObject {
            put("code", "TLS_FAILURE")
            put("remote_acceptance", "UNKNOWN")
            put("dispatched", true)
            put("invocation_id", "attempt-42")
            put("resume_allowed", false)
        }
        val report = buildJsonObject {
            put("original_failure", original)
            put("recovery_action", "CONTINUE_FROM_THE_FIRST_UNVERIFIED_CRITERION")
            put("task_outcome", "PARTIAL")
        }
        val read = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", "github_context")
            put("arguments", buildJsonObject { put("action", "status") })
        }
        val receipts = (1..7).associate { "read-$it" to read }
        val decision = GitHubEffectiveRecovery.resolve(report)
        assertEquals("INSPECT_EXECUTION_FAILURE", decision.action)
        assertFalse(decision.continueWork)
        val result = GitHubEffectiveRecovery.publicationText("Saved results.\n\n**Next action:** Resume from the first unverified criterion.", report, receipts)
        assertTrue(result.contains("attempt-42"))
        assertTrue(result.contains("7 retained successful read receipts"))
        assertFalse(result.contains("Resume from"))
        assertTrue(result.contains("Check remote acceptance"))
        val local = JsonObject(
            report + (
                "original_failure" to buildJsonObject {
                    put("code", "REPLAY_EVIDENCE_REQUIRED")
                    put("dispatched", false)
                    put("resume_allowed", false)
                }
                )
        )
        val saved = GitHubEffectiveRecovery.publicationText("Retained pages.", local, receipts)
        assertTrue(saved.contains("saved-page recovery"))
        assertFalse(saved.contains("Check remote acceptance"))
    }

    @Test fun outputCheckpointCannotOverrideTheTaskRecoveryDenial() {
        val report = buildJsonObject {
            put("task_outcome", "PARTIAL")
            put("resume_allowed", false)
            put(
                "output_checkpoint",
                buildJsonObject {
                    put("state", "TOOL_RESULTS_READY")
                    put("resume_allowed", true)
                }
            )
            put(
                "execution_ledger",
                buildJsonObject {
                    put("recovery", "RESUME_UNFINISHED_WORK")
                    put("remaining_criteria", 1)
                }
            )
        }
        val decision = GitHubEffectiveRecovery.resolve(report)
        assertFalse(decision.continueWork)
        assertFalse(decision.verificationOnly)
        assertFalse(GitHubExecutionContinuation.canResume(report, emptyMap(), 100, 60000))
        assertEquals(decision.json(), GitHubTaskLifecycle.acceptanceFailureDetails(report)["effective_recovery"])
        assertEquals(decision.json(), GitHubEffectiveRecovery.attach(report)["effective_recovery"])
        assertEquals(JsonPrimitive(false), decision.json()["request_replay_allowed"])
    }

    @Test fun savedVerifierFaultOffersAnExplicitPassWithoutAutomaticWorkOrRequestReplay() {
        val report = fault()
        val decision = GitHubEffectiveRecovery.resolve(report)
        assertTrue(decision.verificationOnly)
        assertFalse(decision.continueWork)
        assertEquals("RESUME_VERIFICATION", decision.action)
        assertEquals("Resume verification", decision.label)
        assertFalse(GitHubExecutionContinuation.canResume(report, emptyMap(), 100, 60000, allowVerificationResume = true))
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(report)
        assertEquals(JsonPrimitive("RESUME_VERIFICATION"), details["recovery_action"])
        assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertEquals(JsonPrimitive(false), details["resume_allowed"])
        assertEquals(JsonPrimitive(true), details["user_verification_retry_allowed"])
        val metadata = GitHubTaskLifecycle.failureMetadata(
            dev.chungjungsoo.gptmobile.data.agent.ProviderEvent.Failed("Verification remains incomplete", errorCode = "GITHUB_VERIFICATION_INTERNAL_ERROR", taskOutcome = "PARTIAL", failureDetails = details)
        )
        assertEquals(decision.json(), metadata["effective_recovery"])
        assertEquals(details["recovery_action"], metadata["recovery_action"])
    }

    @Test fun freshEvidenceCannotTurnAnExplicitVerifierPassIntoAutomaticExecution() {
        val receipts = mapOf(
            "fresh-read" to buildJsonObject {
                put("trusted", true)
                put("success", true)
                put("tool", "github_read")
            }
        )
        val verification = JsonObject(
            fault() + mapOf(
                "failure_code" to JsonPrimitive("GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION"),
                "execution_ledger" to buildJsonObject {
                    put("recovery", "RESUME_VERIFICATION")
                    put("remaining_criteria", 1)
                }
            )
        )
        assertTrue(GitHubEffectiveRecovery.resolve(verification).verificationOnly)
        assertFalse(GitHubExecutionContinuation.canResume(verification, receipts, 100, 60000, allowVerificationResume = true))

        val work = JsonObject(
            verification + mapOf(
                "failure_code" to JsonPrimitive("GITHUB_TASK_INCOMPLETE"),
                "resume_allowed" to JsonPrimitive(true),
                "retryable_without_new_input" to JsonPrimitive(true),
                "execution_ledger" to buildJsonObject {
                    put("recovery", "RESUME_UNFINISHED_WORK")
                    put("remaining_criteria", 1)
                }
            )
        )
        assertTrue(GitHubEffectiveRecovery.resolve(work).continueWork)
        assertTrue(GitHubExecutionContinuation.canResume(work, receipts, 100, 60000, allowVerificationResume = true))
    }

    @Test fun unresolvedWritesTakePrecedenceOverDeadlineApprovalAndVerifierRecovery() {
        val report = JsonObject(
            fault() + mapOf(
                "requires_reconciliation" to JsonPrimitive(true),
                "retry_not_before_ms" to JsonPrimitive(5000L),
                "original_failure" to buildJsonObject { put("code", "WAITING_FOR_APPROVAL") }
            )
        )
        val decision = GitHubEffectiveRecovery.resolve(report)
        assertEquals(GitHubEffectiveRecovery.Kind.RECONCILE_REMOTE_OUTCOME, decision.kind)
        assertEquals("Review uncertain write", decision.label)
        assertFalse(decision.verificationOnly)
        assertFalse(decision.continueWork)
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), GitHubTaskLifecycle.acceptanceFailureDetails(report)["recovery_action"])
    }

    @Test fun authorityAndProviderDeadlineCannotBecomeVerifierRetries() {
        val authority = GitHubEffectiveRecovery.resolve(JsonObject(fault() + ("task_outcome" to JsonPrimitive("CONFIGURATION_INVALIDATED"))))
        assertEquals(GitHubEffectiveRecovery.Kind.REFRESH_AUTHORITY, authority.kind)
        assertEquals("RESTORE_AUTHORIZED_CONFIGURATION", authority.action)
        val provider = GitHubEffectiveRecovery.resolve(JsonObject(fault() + ("retry_not_before_ms" to JsonPrimitive(5000L))))
        assertEquals(GitHubEffectiveRecovery.Kind.WAIT_FOR_PROVIDER, provider.kind)
        assertEquals(5000L, provider.retryAtMillis)
        assertEquals(JsonPrimitive(5000L), provider.json()["retry_not_before_ms"])
    }

    @Test fun durableCompletionRetiresAnOldProviderDeadlineButNotCurrentAuthorityOrWriteBlockers() {
        val complete = buildJsonObject {
            put("completion_verified", true)
            put("response_delivered", true)
            put("evidence_retention_complete", true)
            put("task_outcome", "COMPLETED")
            put(
                "original_failure",
                buildJsonObject {
                    put("code", "PROVIDER_RATE_LIMITED")
                    put("retry_not_before_ms", 5000L)
                }
            )
            put("execution_ledger", buildJsonObject { put("requested_task", "VERIFIED_COMPLETE") })
        }
        assertEquals(GitHubEffectiveRecovery.Kind.NONE, GitHubEffectiveRecovery.resolve(complete).kind)
        assertEquals(GitHubEffectiveRecovery.Kind.RECONCILE_REMOTE_OUTCOME, GitHubEffectiveRecovery.resolve(JsonObject(complete + ("requires_reconciliation" to JsonPrimitive(true)))).kind)
        assertEquals(GitHubEffectiveRecovery.Kind.REFRESH_AUTHORITY, GitHubEffectiveRecovery.resolve(JsonObject(complete + ("task_outcome" to JsonPrimitive("CONFIGURATION_INVALIDATED")))).kind)
        assertEquals(GitHubEffectiveRecovery.Kind.WAIT_FOR_PROVIDER, GitHubEffectiveRecovery.resolve(JsonObject(complete + ("task_outcome" to JsonPrimitive("WAITING_PROVIDER_LIMIT")))).kind)
    }

    @Test fun missingEvidenceOriginalFailureAndRequiredUserDecisionRemainBlocked() {
        val reports = listOf(
            JsonObject(fault() - "evidence_retention_complete"),
            JsonObject(fault() + ("evidence_retention_complete" to JsonPrimitive(false))),
            JsonObject(fault() + ("user_input_required" to JsonPrimitive(true))),
            JsonObject(fault() + ("original_failure_code" to JsonPrimitive("NETWORK_TIMEOUT"))),
            JsonObject(fault() + ("original_failure" to buildJsonObject { put("code", "PROVIDER_RATE_LIMITED") }))
        )
        reports.forEach { report ->
            val decision = GitHubEffectiveRecovery.resolve(report)
            assertFalse(report.toString(), decision.verificationOnly)
            assertFalse(report.toString(), decision.continueWork)
        }
    }

    @Test fun completedOutputKeepsTaskAcceptanceAndWakeOwnershipIndependent() {
        val report = buildJsonObject {
            put("phase", "IN_PROGRESS")
            put("task_outcome", "PARTIAL")
            put("completion_verified", false)
            put("response_delivered", true)
            put("resume_allowed", true)
            put("remaining_requirements", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("id", "validation") })))
            put(
                "output_checkpoint",
                buildJsonObject {
                    put("state", "COMPLETED")
                    put("resume_allowed", false)
                }
            )
            put(
                "execution_ledger",
                buildJsonObject {
                    put("recovery", "RESUME_UNFINISHED_WORK")
                    put("remaining_criteria", 1)
                }
            )
        }
        val attached = GitHubEffectiveRecovery.attach(report)
        val checkpoint = attached["task_recovery_checkpoint"] as JsonObject
        assertTrue(GitHubEffectiveRecovery.resolve(attached).continueWork)
        assertEquals(JsonPrimitive(false), checkpoint["task_complete"])
        assertEquals(JsonPrimitive(true), checkpoint["response_delivered"])
        assertEquals(JsonPrimitive("COMPLETED"), checkpoint["output_attempt_state"])
        assertEquals(JsonPrimitive("UNCONFIRMED"), checkpoint["wake_owner"])
        assertEquals(report["remaining_requirements"]?.let { kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("validation"))) }, checkpoint["pending_requirement_ids"])
        assertEquals(JsonPrimitive(false), checkpoint["request_replay_allowed"])
    }

    @Test fun currentLocalRecoveryBlockerSupersedesHistoricalCooldown() {
        val report = buildJsonObject {
            put("task_outcome", "BLOCKED")
            put("current_failure_code", "PROVIDER_WAIT_CHECKPOINT_CHANGED")
            put(
                "original_failure",
                buildJsonObject {
                    put("code", "PROVIDER_RATE_LIMITED")
                    put("retry_not_before_ms", 5000)
                }
            )
            put(
                "latest_recovery_failure",
                buildJsonObject {
                    put("recovery_code", "PROVIDER_WAIT_CHECKPOINT_CHANGED")
                    put("next_action", "REVIEW_SAVED_RECOVERY")
                }
            )
        }
        val decision = GitHubEffectiveRecovery.resolve(report)
        assertEquals(GitHubEffectiveRecovery.Kind.INSPECT_FAILURE, decision.kind)
        assertEquals("REVIEW_SAVED_RECOVERY", decision.action)
        assertEquals(null, decision.retryAtMillis)
        assertEquals(GitHubEffectiveRecovery.Kind.INSPECT_FAILURE, GitHubEffectiveRecovery.resolve(JsonObject(report + mapOf("completion_verified" to JsonPrimitive(true), "response_delivered" to JsonPrimitive(true), "task_outcome" to JsonPrimitive("COMPLETED")))).kind)
        assertFalse(decision.continueWork)
        assertEquals(report["original_failure"], GitHubEffectiveRecovery.attach(report)["original_failure"])
    }

    @Test fun providerWakeIsClaimedOnlyAfterDurableSchedulingEvidence() {
        val waiting = buildJsonObject {
            put("task_outcome", "WAITING_PROVIDER_LIMIT")
            put("retry_not_before_ms", 5000)
        }
        fun owner(report: JsonObject) = (GitHubEffectiveRecovery.attach(report)["task_recovery_checkpoint"] as JsonObject)["wake_owner"]
        assertEquals(JsonPrimitive("UNCONFIRMED"), owner(waiting))
        assertEquals(JsonPrimitive("PROVIDER_WAIT_REATTACHMENT"), owner(JsonObject(waiting + ("provider_wait_persisted" to JsonPrimitive(true)))))
        assertEquals(JsonPrimitive("PROVIDER_WAIT_SCHEDULER"), owner(JsonObject(waiting + mapOf("provider_wait_persisted" to JsonPrimitive(true), "provider_wait_scheduler_state" to JsonPrimitive("ENQUEUED")))))
    }

    @Test fun acceptanceContinuationUsesRemainingAuthorizedBudgetBeyondThreeRounds() {
        repeat(8) { assertTrue(GitHubExecutionContinuation.hasAcquisitionBudget(48 - it, 60000)) }
        assertFalse(GitHubExecutionContinuation.hasAcquisitionBudget(1, 60000))
        assertFalse(GitHubExecutionContinuation.hasAcquisitionBudget(48, 0))
        assertFalse(GitHubExecutionContinuation.hasAcquisitionBudget(48, -1))
        assertFalse(GitHubExecutionContinuation.hasAcquisitionBudget(null, 60000))
    }
}
