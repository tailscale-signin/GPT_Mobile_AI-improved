package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One derived decision for task recovery. A provider checkpoint never grants mutation replay. */
internal object GitHubEffectiveRecovery {
    enum class Kind { NONE, CONTINUE_WORK, VERIFY_SAVED_RESULTS, WAIT_FOR_PROVIDER, REFRESH_AUTHORITY, RECONCILE_REMOTE_OUTCOME, REVIEW_APPROVAL, INSPECT_FAILURE }

    data class Decision(val kind: Kind, val action: String, val label: String, val retryAtMillis: Long? = null) {
        val verificationOnly: Boolean get() = kind == Kind.VERIFY_SAVED_RESULTS
        val continueWork: Boolean get() = kind == Kind.CONTINUE_WORK

        fun json(): JsonObject = buildJsonObject {
            put("schema", "github.effective-recovery.v1")
            put("kind", kind.name)
            put("action", action)
            put("verification_only", verificationOnly)
            put("resume_allowed", continueWork)
            put("user_verification_retry_allowed", verificationOnly)
            put("request_replay_allowed", false)
            retryAtMillis?.let { put("retry_not_before_ms", it) }
        }
    }

    fun resolve(report: JsonObject): Decision {
        val ledger = report["execution_ledger"] as? JsonObject
        val original = report["original_failure"] as? JsonObject
        val failure = report["verification_failure"] as? JsonObject
        val layers = listOfNotNull(report, ledger, original, failure)
        val code = original.text("code").ifBlank { report.text("original_failure_code") }.ifBlank { report.text("failure_code") }.ifBlank { failure.text("code") }
        val stop = report["stop_reason"] as? JsonObject
        val action = sequenceOf(
            original.text("recovery_action"),
            original.text("next_action"),
            report.text("recovery_action"),
            if (original == null) failure.text("recovery_action") else "",
            stop.text("recovery_action"),
            ledger.text("recovery")
        )
            .firstOrNull(String::isNotBlank).orEmpty()
        fun anyTrue(key: String) = layers.any { it[key] == JsonPrimitive(true) }
        fun anyFalse(key: String) = layers.any { it[key] == JsonPrimitive(false) }
        val retryAt = layers.mapNotNull { (it["retry_not_before_ms"] as? JsonPrimitive)?.longOrNull }.filter { it > 0 }.maxOrNull()
        val pendingApproval = layers.any { (it["pending_approvals"] as? JsonArray)?.isNotEmpty() == true } ||
            code == "WAITING_FOR_APPROVAL" ||
            action == "WAITING_FOR_APPROVAL"
        val verifiedCompletion = report["completion_verified"] == JsonPrimitive(true) &&
            report["response_delivered"] == JsonPrimitive(true) &&
            !anyFalse("evidence_retention_complete") &&
            !anyTrue("user_input_required") &&
            report.text("task_outcome") !in setOf("PARTIAL", "BLOCKED", "REJECTED", "WAITING_PROVIDER_LIMIT", "WAITING_FOR_CONNECTION") &&
            (ledger == null || ledger.text("requested_task") == "VERIFIED_COMPLETE")
        return when {
            (ledger?.get("unresolved_remote_outcomes") as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true ||
                anyTrue("requires_reconciliation") ||
                action == "RECONCILE_REMOTE_OUTCOME" ||
                code in setOf("PROVIDER_WAIT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED") ->
                Decision(Kind.RECONCILE_REMOTE_OUTCOME, "RECONCILE_REMOTE_OUTCOME", "Review uncertain write")
            report["task_outcome"] == JsonPrimitive("CONFIGURATION_INVALIDATED") ||
                code in setOf("DISPATCH_POLICY_CHANGED", "PERMISSION_REVOKED", "PERMISSION_DENIED", "AUTH_REQUIRED", "AUTH_FAILED", "AUTH_FAILURE") ||
                action in setOf("RESTORE_AUTHORIZED_CONFIGURATION", "REFRESH_CHANGED_AUTHORITY") ->
                Decision(Kind.REFRESH_AUTHORITY, "RESTORE_AUTHORIZED_CONFIGURATION", "Review changed authority")
            pendingApproval -> Decision(Kind.REVIEW_APPROVAL, "WAITING_FOR_APPROVAL", "Review approval")
            // Durable completion supersedes a retained provider cooldown. Current
            // authority, approval and uncertain-outcome blockers above still win.
            verifiedCompletion -> Decision(Kind.NONE, "NONE", "Completed")
            retryAt != null || action == "WAIT_PROVIDER_DEADLINE" || code in setOf("PROVIDER_RATE_LIMITED", "GITHUB_RATE_LIMITED", "GITHUB_CIRCUIT_OPEN") ->
                Decision(Kind.WAIT_FOR_PROVIDER, "WAIT_PROVIDER_DEADLINE", "Review provider deadline", retryAt)
            anyTrue("user_input_required") ||
                anyFalse("evidence_retention_complete") ||
                report["task_outcome"] in setOf(JsonPrimitive("BLOCKED"), JsonPrimitive("REJECTED")) ->
                inspection(action)
            report["evidence_retention_complete"] == JsonPrimitive(true) &&
                (original == null || original.text("code") in verificationCodes) &&
                (code in verificationCodes || action == "RESUME_VERIFICATION") ->
                // Explicit user action starts one verifier-only pass. This never permits
                // automatic provider replay or reopening tool acquisition.
                Decision(Kind.VERIFY_SAVED_RESULTS, "RESUME_VERIFICATION", "Resume verification")
            anyFalse("resume_allowed") || anyFalse("retryable_without_new_input") || anyFalse("logical_continuation_allowed") ->
                inspection(action)
            action == "RESUME_UNFINISHED_WORK" ->
                Decision(Kind.CONTINUE_WORK, action, "Resume unfinished work")
            else -> inspection(action)
        }
    }

    fun attach(report: JsonObject): JsonObject = JsonObject(report + ("effective_recovery" to resolve(report).json()))

    private fun inspection(action: String): Decision = Decision(
        Kind.INSPECT_FAILURE,
        when (action) {
            "RESUME_VERIFICATION" -> "INSPECT_VERIFICATION_FAILURE"
            "", "NONE", "RESUME_UNFINISHED_WORK" -> "INSPECT_EXECUTION_FAILURE"
            else -> action
        },
        "Review recovery details"
    )

    private val verificationCodes = setOf(
        "GITHUB_VERIFICATION_INTERNAL_ERROR",
        "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION",
        "GITHUB_EXTRACTION_TIMEOUT",
        "GITHUB_EXTRACTION_UNAVAILABLE",
        "GITHUB_EXTRACTION_FAILED"
    )

    private fun JsonObject?.text(key: String): String = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
