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
    enum class ReceiptState { PRECHECK_REJECTED, DISPATCHED, COMPLETED, FAILED, UNKNOWN_OUTCOME }
    private data class ReconciliationKey(val binding: String, val tool: String, val repository: String, val branch: String, val arguments: JsonObject)

    fun receiptState(receipt: JsonObject): ReceiptState {
        val execution = receipt["execution"] as? JsonObject
        val phase = execution?.text("phase").orEmpty().uppercase()
        val outcome = execution?.text("outcome").orEmpty().uppercase()
        val unknown = (execution?.get("unknown_mutations") as? JsonPrimitive)?.longOrNull ?: 0
        return when {
            unknown > 0 ||
                phase in setOf("UNKNOWN_OUTCOME", "OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED") ||
                outcome in setOf("UNKNOWN", "UNKNOWN_OUTCOME") ||
                receipt.text("error_code") in ToolResultEnvelope.unknownOutcomeCodes ||
                receipt.text("error_code") in setOf("GATEWAY_OUTCOME_UNKNOWN", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN") -> ReceiptState.UNKNOWN_OUTCOME
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

    fun attach(checkpoint: JsonObject, receipts: Map<String, JsonObject>): JsonObject {
        val states = receipts.values.groupingBy(::receiptState).eachCount()
        val uncertain = receipts.filterValues { receiptState(it) == ReceiptState.UNKNOWN_OUTCOME }
        val proofs = receipts.mapNotNull { (id, receipt) ->
            if (isCommitReconciliation(receipt)) reconciliationKey(receipt)?.let { it to id } else null
        }.toMap()
        val reconciled = uncertain.mapNotNull { (id, receipt) ->
            reconciliationKey(receipt)?.let(proofs::get)?.let { id to JsonPrimitive(it) }
        }.toMap()
        val unresolved = uncertain.size - reconciled.size
        val acceptance = checkpoint["acceptance_ledger"] as? JsonObject
        val requirements = (acceptance?.get("requirements") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["required"] != JsonPrimitive(false) }
        val accepted = requirements.count { it["state"] == JsonPrimitive("PASS") }
        val completed = unresolved == 0 && checkpoint["completion_verified"] == JsonPrimitive(true) && acceptance?.get("completion_verified") == JsonPrimitive(true)
        val code = checkpoint.text("failure_code").ifBlank { checkpoint.text("verification_failure") }
        val failedStage = checkpoint.text("failed_stage")
        val outcome = checkpoint.text("task_outcome")
        val reviewFailure = code.startsWith("GITHUB_REVIEW_") ||
            code.startsWith("GITHUB_EXTRACTION_") ||
            failedStage in setOf("answer_review", "claim_extraction") ||
            checkpoint["verification_failure"] is JsonObject
        val verification = when {
            reviewFailure -> "FAILED"
            unresolved > 0 -> "PENDING"
            checkpoint["claims_verified"] == JsonPrimitive(true) || completed -> "VERIFIED"
            checkpoint["verification_failure"] != null && code != "GITHUB_TASK_INCOMPLETE" -> "FAILED"
            else -> "PENDING"
        }
        val requestedTask = when {
            completed -> "VERIFIED_COMPLETE"
            outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") -> "BLOCKED"
            checkpoint.text("phase") == "IN_PROGRESS" -> "IN_PROGRESS"
            else -> "INCOMPLETE"
        }
        val recovery = when {
            unresolved > 0 -> "RECONCILE_REMOTE_OUTCOME"
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
            put("user_input_required", recovery == "USER_INPUT_REQUIRED")
            (checkpoint["retryable_without_new_input"] as? JsonPrimitive)?.booleanOrNull?.let { put("retryable_without_new_input", it) }
        }
        val limitation = if (unresolved > 0) {
            buildJsonObject {
                put("completion_verified", false)
                put("partial", true)
                put("resume_required", true)
                put("task_outcome", if (outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED")) outcome else "PARTIAL")
                put("task_state", if (outcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED")) "BLOCKED" else "PARTIAL")
            }
        } else {
            JsonObject(emptyMap())
        }
        return JsonObject(checkpoint + limitation + ("execution_ledger" to ledger))
    }

    /** Only the native read-only ref/parent/message/full-tree proof can resolve a lost commit. */
    private fun isCommitReconciliation(receipt: JsonObject): Boolean {
        if (receipt["trusted"] != JsonPrimitive(true) || receipt["verification_eligible"] == JsonPrimitive(false) || receiptState(receipt) != ReceiptState.COMPLETED) return false
        val arguments = receipt["arguments"] as? JsonObject ?: return false
        val native = if (receipt.text("tool").substringBefore("__") == "github") arguments else GitHubOperationTool.nativeArguments(receipt.text("tool"), arguments) ?: return false
        if (native.text("action") !in setOf("commit_files", "commit_staged_patch")) return false
        val result = receipt.result() ?: return false
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
        return ReconciliationKey(scope[0], scope[1], scope[2], scope[3], arguments)
    }

    private fun JsonObject.result(): JsonObject? = (get("result") as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }.orEmpty()
}
