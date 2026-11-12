package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A bounded projection of durable receipts; model completion never substitutes for acceptance. */
internal object GitHubExecutionLedger {
    enum class ReceiptState { PRECHECK_REJECTED, DISPATCHED, PROPOSAL_PREPARED, WAITING_FOR_APPROVAL, REJECTED, COMPLETED, CANCELLED, FAILED, UNKNOWN_OUTCOME }
    enum class ObservationKind { REMOTE_API_SUCCESS, MUTATION_COMPLETED, MUTATION_ACCEPTED, MUTATION_VERIFIED, PROPOSAL_PREPARED, CACHE_HIT, LOCAL_RESULT, SUPPRESSED, PENDING_APPROVAL, REJECTED, PRECHECK_REJECTED, FAILED, UNKNOWN_OUTCOME }
    private data class ReconciliationKey(
        val binding: String,
        val tool: String,
        val repository: String,
        val branch: String,
        val arguments: JsonObject,
        val executionId: String? = null,
        val correlationId: String? = null
    )

    fun receiptState(receipt: JsonObject): ReceiptState {
        val execution = receipt["execution"] as? JsonObject
        val phase = execution?.text("phase").orEmpty().uppercase()
        val outcome = execution?.text("outcome").orEmpty().uppercase()
        val unknown = (execution?.get("unknown_mutations") as? JsonPrimitive)?.longOrNull ?: 0
        val operationOutcome = GitHubOperationOutcome.snapshot(receipt)
        return when {
            unknown > 0 ||
                phase in setOf("UNKNOWN_OUTCOME", "OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED") ||
                outcome in setOf("UNKNOWN", "UNKNOWN_OUTCOME") ||
                receipt.text("error_code") in ToolResultEnvelope.unknownOutcomeCodes ||
                receipt.text("error_code") in setOf("GATEWAY_OUTCOME_UNKNOWN", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN") -> ReceiptState.UNKNOWN_OUTCOME
            isRejectedApprovalReceipt(receipt) -> ReceiptState.REJECTED
            GitHubPendingApproval.taskId(receipt) != null || operationOutcome["approval_pending"] == JsonPrimitive(true) -> ReceiptState.WAITING_FOR_APPROVAL
            operationOutcome["proposal_prepared"] == JsonPrimitive(true) -> ReceiptState.PROPOSAL_PREPARED
            receipt["success"] == JsonPrimitive(true) ||
                receipt.text("error_code") == "RETAINED_RESULT_CAPACITY" &&
                execution?.get("upstream_completed") == JsonPrimitive(true) &&
                execution?.text("upstream_execution_state") == "COMPLETED" -> ReceiptState.COMPLETED
            phase in setOf("PRECHECK_REJECTED", "PREFLIGHT_REJECTED") || receipt["dispatched"] == JsonPrimitive(false) -> ReceiptState.PRECHECK_REJECTED
            phase == "DISPATCHED" && receipt["success"] == null -> ReceiptState.DISPATCHED
            receipt["success"] == JsonPrimitive(false) && receipt["dispatched"] == JsonPrimitive(true) -> ReceiptState.FAILED
            else -> ReceiptState.UNKNOWN_OUTCOME
        }
    }

    fun observationKind(receipt: JsonObject): ObservationKind {
        val state = receiptState(receipt)
        val root = receipt.result()
        val execution = receipt["execution"] as? JsonObject
        return when {
            state == ReceiptState.UNKNOWN_OUTCOME -> ObservationKind.UNKNOWN_OUTCOME
            state == ReceiptState.WAITING_FOR_APPROVAL -> ObservationKind.PENDING_APPROVAL
            state == ReceiptState.REJECTED -> ObservationKind.REJECTED
            state == ReceiptState.PROPOSAL_PREPARED -> ObservationKind.PROPOSAL_PREPARED
            root?.text("schema") == "github.repeat.v1" -> ObservationKind.SUPPRESSED
            state == ReceiptState.PRECHECK_REJECTED -> ObservationKind.PRECHECK_REJECTED
            state != ReceiptState.COMPLETED -> ObservationKind.FAILED
            receipt["shared_result"] == JsonPrimitive(true) || GitHubEvidenceOrigin.of(receipt) == "CACHED" -> ObservationKind.CACHE_HIT
            receipt["remote_mutation"] == JsonPrimitive(true) && GitHubOperationOutcome.snapshot(receipt)["remote_outcome_verified"] == JsonPrimitive(true) -> ObservationKind.MUTATION_VERIFIED
            (execution?.get("accepted_mutations") as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true -> ObservationKind.MUTATION_ACCEPTED
            GitHubEvidenceOrigin.of(receipt) == "LIVE" -> ObservationKind.REMOTE_API_SUCCESS
            else -> ObservationKind.LOCAL_RESULT
        }
    }

    /** A saved approval is secondary evidence when an independent execution stage failed. */
    fun hasPrimaryFailure(checkpoint: JsonObject): Boolean {
        val failure = checkpoint["original_failure"] as? JsonObject ?: return false
        return failure.text("code") !in setOf("WAITING_FOR_APPROVAL", "APPROVAL_REQUIRED", "USER_INPUT_REQUIRED", "REVIEW_INPUT_REQUIRED", "APPROVAL_REJECTED")
    }

    fun attach(checkpoint: JsonObject, receipts: Map<String, JsonObject>): JsonObject {
        val states = receipts.values.groupingBy(::receiptState).eachCount()
        val uncertain = receipts.filterValues { receiptState(it) == ReceiptState.UNKNOWN_OUTCOME }
        val reconciled = reconciliationProofs(receipts)
        val unresolved = uncertain.size - reconciled.size
        val pendingApprovals = GitHubPendingApproval.snapshot(receipts)
        val resolvedApprovals = receipts.values.mapNotNull { proposal ->
            GitHubPendingApproval.resolution(proposal, receipts.values)?.let { resolution -> proposal to resolution }
        }
        val resolutions = resolvedApprovals.toMap()
        val rejectedApprovals = resolvedApprovals.filter { (_, resolution) -> isRejectedApprovalReceipt(resolution) }
        val currentStates = receipts.values.groupingBy { receipt ->
            when {
                isRejectedApprovalReceipt(resolutions[receipt]) -> ReceiptState.REJECTED
                resolutions[receipt]?.result()?.text("approval_state") == "PUBLISHED" -> ReceiptState.COMPLETED
                resolutions[receipt]?.result()?.text("approval_state") == "CANCELLED_BEFORE_PUBLICATION" -> ReceiptState.CANCELLED
                else -> receiptState(receipt)
            }
        }.eachCount()
        val acceptance = checkpoint["acceptance_ledger"] as? JsonObject
        val requirements = (acceptance?.get("requirements") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["required"] != JsonPrimitive(false) }
        val accepted = requirements.count { it["state"] == JsonPrimitive("PASS") }
        val failureIsPrimary = hasPrimaryFailure(checkpoint)
        val completed = !failureIsPrimary && unresolved == 0 && pendingApprovals.isEmpty() && rejectedApprovals.isEmpty() && checkpoint["completion_verified"] == JsonPrimitive(true) && acceptance?.get("completion_verified") == JsonPrimitive(true)
        val originalFailure = checkpoint["original_failure"] as? JsonObject
        val code = checkpoint.text("failure_code").ifBlank { originalFailure?.text("code").orEmpty() }.ifBlank { checkpoint.text("verification_failure") }
        val failedStage = checkpoint.text("failed_stage")
        val outcome = checkpoint.text("task_outcome")
        val deadlineReached = originalFailure?.text("stop_reason") == "ACCEPTED_TASK_DEADLINE_REACHED"
        val reviewFailure = code.startsWith("GITHUB_REVIEW_") ||
            code.startsWith("GITHUB_EXTRACTION_") ||
            failedStage in setOf("answer_review", "claim_extraction") ||
            checkpoint["verification_failure"] is JsonObject
        val verification = when {
            reviewFailure -> "FAILED"
            unresolved > 0 || deadlineReached -> "PENDING"
            !failureIsPrimary && pendingApprovals.isNotEmpty() -> "WAITING_FOR_APPROVAL"
            !failureIsPrimary && rejectedApprovals.isNotEmpty() -> "REJECTED"
            checkpoint["claims_verified"] == JsonPrimitive(true) || completed -> "VERIFIED"
            checkpoint["verification_failure"] != null && code != "GITHUB_TASK_INCOMPLETE" -> "FAILED"
            else -> "PENDING"
        }
        val requestedTask = when {
            deadlineReached -> "BLOCKED"
            completed -> "VERIFIED_COMPLETE"
            failureIsPrimary -> if (outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED")) "BLOCKED" else "INCOMPLETE"
            pendingApprovals.isNotEmpty() -> "WAITING_FOR_APPROVAL"
            rejectedApprovals.isNotEmpty() -> "REJECTED"
            outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") -> "BLOCKED"
            checkpoint.text("phase") == "IN_PROGRESS" -> "IN_PROGRESS"
            else -> "INCOMPLETE"
        }
        val recovery = when {
            unresolved > 0 -> "RECONCILE_REMOTE_OUTCOME"
            deadlineReached -> "NEW_TASK_REQUIRED"
            failureIsPrimary && outcome == "CONFIGURATION_INVALIDATED" -> "RESTORE_AUTHORIZED_CONFIGURATION"
            failureIsPrimary && reviewFailure -> "RESUME_VERIFICATION"
            failureIsPrimary -> "INSPECT_EXECUTION_FAILURE"
            pendingApprovals.isNotEmpty() -> "WAITING_FOR_APPROVAL"
            rejectedApprovals.isNotEmpty() -> "RECONCILE_FIXTURE_AFTER_APPROVAL_REJECTION"
            code in setOf("APPROVAL_REQUIRED", "USER_INPUT_REQUIRED", "REVIEW_INPUT_REQUIRED") -> "USER_INPUT_REQUIRED"
            outcome == "CONFIGURATION_INVALIDATED" || code in setOf("DISPATCH_POLICY_CHANGED", "PERMISSION_DENIED", "PERMISSION_REVOKED", "AUTH_REQUIRED", "AUTH_FAILED", "AUTH_FAILURE") -> "RESTORE_AUTHORIZED_CONFIGURATION"
            completed && checkpoint["response_delivered"] == JsonPrimitive(true) -> "NONE"
            reviewFailure -> "RESUME_VERIFICATION"
            completed -> "DELIVER_VERIFIED_RESPONSE"
            else -> "RESUME_UNFINISHED_WORK"
        }
        val ledger = buildJsonObject {
            put("schema", "github.execution-ledger.v1")
            put("receipt_count", receipts.size)
            put("presentation_failures", receipts.values.count { (it["execution"] as? JsonObject)?.text("presentation_state") == "CAPACITY_REJECTED" })
            put("receipt_states", buildJsonObject { ReceiptState.entries.forEach { put(it.name, states.getOrDefault(it, 0)) } })
            put("receipt_states_scope", "HISTORICAL_OBSERVATIONS")
            put("current_receipt_states", buildJsonObject { ReceiptState.entries.forEach { put(it.name, currentStates.getOrDefault(it, 0)) } })
            put("observation_kinds", buildJsonObject { ObservationKind.entries.forEach { kind -> put(kind.name, receipts.values.count { observationKind(it) == kind }) } })
            put("pending_approvals", pendingApprovals)
            put(
                "resolved_approvals",
                JsonArray(
                    resolvedApprovals.map { (proposal, resolution) ->
                        buildJsonObject {
                            val result = resolution.result()
                            val rejected = isRejectedApprovalReceipt(resolution)
                            put("proposal_receipt", proposal["receipt_id"] ?: kotlinx.serialization.json.JsonNull)
                            put("resolution_receipt", resolution["receipt_id"] ?: kotlinx.serialization.json.JsonNull)
                            put("state", if (rejected) "REJECTED" else result?.text("approval_state").orEmpty())
                            put(
                                "decision_outcome",
                                if (rejected) {
                                    "REJECTED"
                                } else if (result?.text("approval_state") == "PUBLISHED") {
                                    "COMPLETED"
                                } else {
                                    "CANCELLED"
                                }
                            )
                            result?.text("engineering_task_id")?.takeIf(String::isNotBlank)?.let { put("task_id", it) }
                            result?.text("branch")?.takeIf(String::isNotBlank)?.let { put("branch", it) }
                            result?.text("expected_head_sha")?.takeIf(String::isNotBlank)?.let { put("expected_head_sha", it) }
                            result?.text("patch_digest")?.takeIf(String::isNotBlank)?.let { put("patch_digest", it) }
                            resolution.text("local_resolution_event").takeIf(String::isNotBlank)?.let { put("local_resolution_event", it) }
                            result?.get("keep_fixture")?.let { put("keep_fixture", it) }
                            put("remote_state_verified", result?.get("remote_state_verified") == JsonPrimitive(true))
                            put("dispatch_attribution", "NATIVE_PUBLICATION_DISPATCH_UNAVAILABLE_USE_REMOTE_STATE_PROOF")
                        }
                    }
                )
            )
            put("unresolved_remote_outcomes", unresolved)
            put("reconciled_remote_outcomes", reconciled.size)
            put("reconciliation_proofs", JsonObject(reconciled))
            put("receipt_scope", "Retained observations; a shared result does not prove another dispatch")
            put("requested_task", requestedTask)
            put("required_criteria", requirements.size)
            put("verified_criteria", accepted)
            put("remaining_criteria", (requirements.size - accepted).coerceAtLeast(0))
            put("verification", verification)
            put(
                "delivery",
                if (checkpoint["response_delivered"] == JsonPrimitive(true)) {
                    "DELIVERED"
                } else if (checkpoint["response_prepared"] == JsonPrimitive(true) || checkpoint["model_round_finished"] == JsonPrimitive(true)) {
                    "PREPARED"
                } else {
                    "PENDING"
                }
            )
            put("recovery", recovery)
            put("user_input_required", pendingApprovals.isNotEmpty() || deadlineReached || recovery == "USER_INPUT_REQUIRED")
            if (pendingApprovals.isNotEmpty() || deadlineReached) put("retryable_without_new_input", false) else (checkpoint["retryable_without_new_input"] as? JsonPrimitive)?.booleanOrNull?.let { put("retryable_without_new_input", it) }
            if (rejectedApprovals.isNotEmpty()) put("retryable_without_new_input", false)
        }
        val limitation = if (unresolved > 0) {
            buildJsonObject {
                put("completion_verified", false)
                put("partial", true)
                put("resume_required", true)
                put("task_outcome", if (outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED")) outcome else "PARTIAL")
                put("task_state", if (outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED")) "BLOCKED" else "PARTIAL")
                put("blocking_condition", "RECONCILE_REMOTE_OUTCOME")
                put("stop_code", "RECONCILE_REMOTE_OUTCOME")
                put("pending_approvals", pendingApprovals)
                if (pendingApprovals.isNotEmpty()) {
                    put("user_input_required", true)
                    put("retryable_without_new_input", false)
                }
            }
        } else if (!failureIsPrimary && !deadlineReached && pendingApprovals.isNotEmpty()) {
            buildJsonObject {
                put("completion_verified", false)
                put("partial", true)
                put("resume_required", true)
                put("task_outcome", "WAITING_FOR_APPROVAL")
                put("task_state", "WAITING_FOR_APPROVAL")
                put("stop_code", "WAITING_FOR_APPROVAL")
                put("approval_state", "WAITING_FOR_APPROVAL")
                put("blocking_condition", "WAITING_FOR_APPROVAL")
                put("user_input_required", true)
                put("retryable_without_new_input", false)
                put("pending_approvals", pendingApprovals)
            }
        } else if (!failureIsPrimary && !deadlineReached && rejectedApprovals.isNotEmpty()) {
            val keepFixture = rejectedApprovals.any { (_, resolution) -> resolution.result()?.get("keep_fixture") == JsonPrimitive(true) }
            buildJsonObject {
                put("completion_verified", false)
                put("partial", true)
                put("resume_required", false)
                put("task_outcome", "REJECTED")
                put("task_state", "REJECTED")
                put("stop_code", "APPROVAL_REJECTED")
                put("approval_state", "REJECTED")
                put("blocking_condition", "APPROVAL_REJECTED")
                put("recovery_action", if (keepFixture) "KEEP_FIXTURE" else "RECONCILE_FIXTURE_AFTER_APPROVAL_REJECTION")
                put("user_input_required", false)
                put("retryable_without_new_input", false)
                put("fixture_lifecycle", if (keepFixture) "INTENTIONALLY_RETAINED_BY_USER" else "RECONCILE_CLEANUP_AND_VERIFY_ABSENCE")
                put(
                    "rejected_approvals",
                    JsonArray(
                        rejectedApprovals.map { (_, resolution) ->
                            buildJsonObject {
                                val result = resolution.result()
                                put("task_id", result?.get("engineering_task_id") ?: kotlinx.serialization.json.JsonNull)
                                put("branch", result?.get("branch") ?: kotlinx.serialization.json.JsonNull)
                                put("expected_head_sha", result?.get("expected_head_sha") ?: kotlinx.serialization.json.JsonNull)
                                put("patch_digest", result?.get("patch_digest") ?: kotlinx.serialization.json.JsonNull)
                                put("keep_fixture", result?.get("keep_fixture") ?: JsonPrimitive(false))
                            }
                        }
                    )
                )
            }
        } else if (failureIsPrimary) {
            buildJsonObject {
                put("completion_verified", false)
                put("partial", true)
                put("resume_required", true)
                put("task_outcome", outcome.takeIf { it in setOf("BLOCKED", "CONFIGURATION_INVALIDATED", "FAILED") } ?: "PARTIAL")
                put("task_state", if (outcome == "CONFIGURATION_INVALIDATED") "BLOCKED" else outcome.takeIf { it in setOf("BLOCKED", "FAILED") } ?: "PARTIAL")
                put("blocking_condition", if (deadlineReached) "ACCEPTED_TASK_DEADLINE_REACHED" else code.ifBlank { "EXECUTION_FAILURE" })
                put("stop_code", if (deadlineReached) "ACCEPTED_TASK_DEADLINE_REACHED" else code.ifBlank { "EXECUTION_FAILURE" })
                put("pending_approvals", pendingApprovals)
                if (pendingApprovals.isNotEmpty()) put("approval_state", "WAITING_FOR_APPROVAL")
                if (rejectedApprovals.isNotEmpty()) put("approval_state", "REJECTED")
                put("user_input_required", pendingApprovals.isNotEmpty() || deadlineReached)
                if (pendingApprovals.isNotEmpty() || rejectedApprovals.isNotEmpty() || deadlineReached) put("retryable_without_new_input", false)
            }
        } else {
            JsonObject(emptyMap())
        }
        return JsonObject(checkpoint + limitation + ("execution_ledger" to ledger))
    }

    /** Receipt IDs settled by exact native readback, shared by checkpoint and continuation policy. */
    fun reconciledReceiptIds(receipts: Map<String, JsonObject>): Set<String> = reconciliationProofs(receipts).keys

    private fun reconciliationProofs(receipts: Map<String, JsonObject>): Map<String, JsonPrimitive> {
        val proofs = receipts.mapNotNull { (id, receipt) ->
            if (isNativeReconciliation(receipt)) reconciliationKey(receipt)?.let { it to id } else null
        }.toMap()
        return receipts.filterValues { receiptState(it) == ReceiptState.UNKNOWN_OUTCOME }.mapNotNull { (id, receipt) ->
            reconciliationKey(receipt)?.let(proofs::get)?.let { id to JsonPrimitive(it) }
        }.toMap()
    }

    /** Only operation-specific native readback can settle an exact uncertain claim. */
    private fun isNativeReconciliation(receipt: JsonObject): Boolean {
        if (receipt["trusted"] != JsonPrimitive(true) || receipt["verification_eligible"] == JsonPrimitive(false) || receiptState(receipt) != ReceiptState.COMPLETED) return false
        val arguments = receipt["arguments"] as? JsonObject ?: return false
        val native = if (receipt.text("tool").substringBefore("__") == "github") arguments else GitHubOperationTool.nativeArguments(receipt.text("tool"), arguments) ?: return false
        val result = receipt.result() ?: return false
        if (native.text("action") in setOf("mark_ready_for_review", "convert_pull_request_to_draft")) {
            val expected = native.text("expected_head_sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return false
            val number = native.text("pull_number").toLongOrNull()?.takeIf { it > 0 } ?: return false
            val execution = result["execution"] as? JsonObject ?: return false
            return GitHubEvidenceOrigin.of(receipt) == "LIVE" &&
                result["reconciled"] == JsonPrimitive(true) &&
                result["remote_state_verified"] == JsonPrimitive(true) &&
                result.text("verification") == (if (native.text("action") == "convert_pull_request_to_draft") "REMOTE_PR_DRAFT_AT_EXPECTED_HEAD" else "REMOTE_PR_READY_AT_EXPECTED_HEAD") &&
                result.text("repository").equals(receipt.text("repository"), ignoreCase = true) &&
                result.text("pull_number").toLongOrNull() == number &&
                result.text("node_id").isNotBlank() &&
                result.text("state") == "open" &&
                result["draft"] == JsonPrimitive(native.text("action") == "convert_pull_request_to_draft") &&
                result.text("head_sha") == expected &&
                execution.text("action") == "graphql.mutation" &&
                execution.text("status") == "COMPLETED" &&
                execution["remote_reconciled"] == JsonPrimitive(true) &&
                execution["replayed_receipt"] == JsonPrimitive(true)
        }
        if (native.text("action") == "create_branch") {
            val expected = native.text("expected_head_sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return false
            val branch = runCatching { normalizeGitHubBranch(native.text("branch")) }.getOrNull() ?: return false
            val ref = result["receipt"] as? JsonObject ?: return false
            return GitHubEvidenceOrigin.of(receipt) == "LIVE" &&
                result["reconciled"] == JsonPrimitive(true) &&
                result["remote_state_verified"] == JsonPrimitive(true) &&
                result.text("verification") == "REMOTE_REF_AND_EXPECTED_HEAD" &&
                result.text("head_sha") == expected &&
                result.text("base_sha") == expected &&
                result.text("branch") == branch &&
                ref.text("ref") == "refs/heads/$branch" &&
                (ref["object"] as? JsonObject)?.text("sha") == expected
        }
        if (native.text("action") !in setOf("commit_files", "commit_staged_patch")) return false
        val sha = result.text("sha")
        return result["reconciled"] == JsonPrimitive(true) &&
            result.text("verification") == "REMOTE_REF_PARENT_MESSAGE_AND_COMPLETE_TREE" &&
            sha.matches(Regex("[a-f0-9]{40}")) &&
            result.text("tree_sha").matches(Regex("[a-f0-9]{40}")) &&
            result.text("expected_head_sha") == sha &&
            result.text("parent_sha") == native.text("expected_head_sha").takeIf { it.matches(Regex("[a-f0-9]{40}")) } &&
            result.text("branch") == receipt.text("branch").takeIf(String::isNotBlank) &&
            (native.text("action") != "commit_staged_patch" || result.text("stage_id") == native.text("stage_id").takeIf(String::isNotBlank))
    }

    private fun reconciliationKey(receipt: JsonObject): ReconciliationKey? {
        if (receipt["trusted"] != JsonPrimitive(true)) return null
        val scope = listOf("binding", "tool", "repository", "branch").map { receipt.text(it).takeIf(String::isNotBlank) ?: return null }
        val arguments = receipt["arguments"] as? JsonObject ?: return null
        val native = if (scope[1].substringBefore("__") == "github") arguments else GitHubOperationTool.nativeArguments(scope[1], arguments)
        if (native != null && native.text("action") in setOf("mark_ready_for_review", "convert_pull_request_to_draft")) {
            val execution = receipt.result()?.get("execution") as? JsonObject ?: return null
            if (execution.text("action") != "graphql.mutation") return null
            val executionId = execution.text("execution_id").takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
            val correlationId = execution.text("correlation_id").takeIf(String::isNotBlank) ?: return null
            val number = native.text("pull_number").toLongOrNull()?.takeIf { it > 0 } ?: return null
            val expected = native.text("expected_head_sha").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
            val request = buildJsonObject {
                put("action", native.text("action"))
                put("pull_number", number)
                put("expected_head_sha", expected)
                native["expected_base_sha"]?.let { put("expected_base_sha", it) }
            }
            return ReconciliationKey(scope[0], scope[1], scope[2], scope[3], request, executionId, correlationId)
        }
        return ReconciliationKey(scope[0], scope[1], scope[2], scope[3], arguments)
    }

    /** Cancellation alone is ambiguous; only the exact durable Reject event produces REJECTED. */
    private fun isRejectedApprovalReceipt(receipt: JsonObject?): Boolean = receipt?.let(GitHubPendingApproval::isRejectedResolution) == true

    private fun JsonObject.result(): JsonObject? = (get("result") as? JsonObject)?.let {
        // A GraphQL mutation's data is part of its response, beside the protected
        // execution receipt. Do not discard that receipt when unwrapping tool views.
        if ((it["execution"] as? JsonObject)?.text("action") == "graphql.mutation") it else (it["data"] as? JsonObject) ?: it
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }.orEmpty()
}
