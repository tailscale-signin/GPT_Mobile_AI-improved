package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubRunAcceptance

/** Flow termination is delivery evidence only. Objective acceptance lives in the task/evidence ledger. */
internal data class AgentRunTerminalDiagnostics(
    val reason: String,
    val stage: String,
    val executionState: String,
    val taskVerification: String = "NOT_MEASURED",
    val dispatched: Boolean? = null,
    val remoteAcceptance: RemoteRequestAcceptance = RemoteRequestAcceptance.UNKNOWN,
    val invocationId: String? = null,
    val recoveryAction: String = "RESUME_UNFINISHED_WORK",
    val taskState: String? = null,
    val stopCode: String? = null,
    val approvalState: String? = null,
    val taskOutcome: String? = null,
    val lastObservedStage: ObservedProgressStage? = null
) {
    companion object {
        fun from(status: String, failure: ProfileFailureRecord?, acceptance: GitHubRunAcceptance.Assessment? = null, lastObservedStage: ObservedProgressStage? = null): AgentRunTerminalDiagnostics {
            val completed = status == AgentRunStatus.COMPLETED
            val unknown = failure?.category in setOf("TOOL_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN") || acceptance?.verification == "UNKNOWN_OUTCOME"
            val acceptanceIncomplete = acceptance != null && acceptance.verification != "VERIFIED"
            val waitingForApproval = acceptance?.verification == "WAITING_FOR_APPROVAL" || acceptance?.taskOutcome == "WAITING_FOR_APPROVAL" || failure?.category == "WAITING_FOR_APPROVAL"
            val rejectedApproval = acceptance?.verification == "REJECTED" || acceptance?.taskOutcome == "REJECTED" || failure?.category == "APPROVAL_REJECTED"
            val acceptedStage = failure?.phase?.takeIf { it != "stage_not_recorded" && it.matches(Regex("[a-zA-Z0-9_:-]{1,80}")) }
            val acceptedReason = failure?.category?.takeUnless { it in setOf("INCOMPLETE", "GITHUB_TASK_INCOMPLETE", "TASK_INCOMPLETE") }
            val state = when {
                unknown -> "UNKNOWN_OUTCOME"
                waitingForApproval -> "WAITING_FOR_APPROVAL"
                rejectedApproval -> "REJECTED"
                completed -> "COMPLETED"
                failure?.dispatched == false -> "PRECHECK_REJECTED"
                failure?.dispatched == null -> if (acceptance != null) "OBSERVED" else "NOT_RECORDED"
                else -> "FAILED"
            }
            return AgentRunTerminalDiagnostics(
                lastObservedStage = lastObservedStage,
                reason = if (completed) {
                    "RESPONSE_FLOW_COMPLETED"
                } else {
                    acceptedReason ?: when (acceptance?.verification) {
                        "UNKNOWN_OUTCOME" -> "RECONCILIATION_REQUIRED"
                        "WAITING_FOR_APPROVAL" -> "WAITING_FOR_APPROVAL"
                        "REJECTED" -> "APPROVAL_REJECTED"
                        "BLOCKED" -> "GITHUB_ACCEPTANCE_BLOCKED"
                        "FAILED" -> "GITHUB_ACCEPTANCE_FAILED"
                        "PARTIAL" -> "GITHUB_EVIDENCE_MISSING"
                        else -> failure?.category ?: "TASK_INCOMPLETE"
                    }
                },
                stage = if (completed) "none" else acceptedStage ?: if (acceptanceIncomplete) "task_acceptance" else "stage_not_recorded",
                executionState = state,
                taskVerification = when {
                    unknown && acceptance != null -> "UNKNOWN_OUTCOME"
                    waitingForApproval -> "WAITING_FOR_APPROVAL"
                    rejectedApproval -> "REJECTED"
                    acceptance?.verification == "VERIFIED" && !completed -> "PARTIAL"
                    else -> acceptance?.verification ?: "NOT_MEASURED"
                },
                // A rejected recovery attempt does not prove an earlier remote
                // operation was unsent. Keep that ambiguity in the terminal receipt.
                dispatched = if (unknown) true.takeIf { failure?.dispatched == true } else failure?.dispatched.takeUnless { completed },
                remoteAcceptance = if (unknown || completed) RemoteRequestAcceptance.UNKNOWN else failure?.remoteAcceptance ?: RemoteRequestAcceptance.UNKNOWN,
                invocationId = failure?.invocationId.takeUnless { completed },
                recoveryAction = if (completed) "NONE" else failure?.recoveryActions?.firstOrNull() ?: recoveryAction(failure?.category, acceptance),
                taskState = acceptance?.taskState ?: when {
                    waitingForApproval -> "WAITING_FOR_APPROVAL"
                    rejectedApproval -> "REJECTED"
                    else -> null
                },
                stopCode = if (completed) null else acceptance?.stopCode ?: failure?.category?.takeIf { it.startsWith("GITHUB_") || it in setOf("WAITING_FOR_APPROVAL", "APPROVAL_REJECTED", "TOOL_BUDGET_EXHAUSTED", "TASK_BUDGET_EXHAUSTED") },
                approvalState = acceptance?.approvalState ?: when {
                    waitingForApproval -> "WAITING_FOR_APPROVAL"
                    rejectedApproval -> "REJECTED"
                    else -> null
                },
                taskOutcome = acceptance?.taskOutcome ?: when {
                    waitingForApproval -> "WAITING_FOR_APPROVAL"
                    rejectedApproval -> "REJECTED"
                    else -> acceptance?.verification
                }
            )
        }

        private fun recoveryAction(
            reason: String?,
            acceptance: GitHubRunAcceptance.Assessment?
        ): String = when {
            acceptance?.verification == "WAITING_FOR_APPROVAL" || reason == "WAITING_FOR_APPROVAL" -> "REVIEW_EXISTING_APPROVAL_CARD"
            acceptance?.taskOutcome == "REJECTED" || acceptance?.verification == "REJECTED" || reason == "APPROVAL_REJECTED" -> "RECONCILE_FIXTURE_AFTER_APPROVAL_REJECTION"
            reason == "GITHUB_EXTRACTION_OUTPUT_LIMIT" -> "RESUME_SAVED_BATCH_WITH_SMALLER_CLAIM_LIMIT"
            reason == "GITHUB_EXTRACTION_INVALID_RESPONSE" -> "RESUME_SAVED_BATCH_WITH_STRICT_SCHEMA"
            reason == "GITHUB_EXTRACTION_TIMEOUT" -> "RESUME_UNFINISHED_EXTRACTION_BATCHES"
            reason == "GITHUB_EXTRACTION_UNAVAILABLE" || reason == "GITHUB_EXTRACTION_FAILED" -> "RETRY_CLAIM_EXTRACTION_FROM_SAVED_RECEIPTS"
            reason == "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION" -> "REFRESH_ONLY_THE_STALE_OBSERVATIONS"
            reason in setOf("GITHUB_TOOL_BUDGET_EXHAUSTED", "GITHUB_TASK_BUDGET_EXHAUSTED", "TOOL_BUDGET_EXHAUSTED", "TASK_BUDGET_EXHAUSTED", "VERIFICATION_RESERVE_REACHED") -> "PUBLISH_RETAINED_EVIDENCE_AND_SAVE_REMAINING_CHECKS"
            reason == "SAVED_VIEW_UNAVAILABLE" -> "REOPEN_PINNED_SOURCE_ONCE"
            reason == "DEPENDENCY_DEFERRED" -> "ADMIT_PREREQUISITE_AND_RETRY_DEPENDENT"
            reason in setOf("COMPLETION_REPAIR_BUDGET_EXHAUSTED", "COMPLETION_REPAIR_BUDGET_UNAVAILABLE", "COMPLETION_REPAIR_USAGE_UNAVAILABLE") -> "RESUME_SAVED_EVIDENCE_WITH_REVISED_OUTPUT_ALLOWANCE"
            else -> "RESUME_UNFINISHED_WORK"
        }
    }
}
