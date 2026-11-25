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
        val recoveryAttempt = (report["latest_recovery_failure"] as? JsonObject)
            ?: failure?.takeIf { it["failure_origin"] == JsonPrimitive("local_recovery") }
            ?: original?.takeIf { it["failure_origin"] == JsonPrimitive("local_recovery") }
        val currentRecoveryCode = report.text("current_failure_code").ifBlank { recoveryAttempt.text("recovery_code").ifBlank { recoveryAttempt.text("code") } }.ifBlank { report.text("failure_code") }
            .takeIf { it in dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.CODES }
        val layers = listOfNotNull(report, ledger, original, failure, recoveryAttempt)
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
            currentRecoveryCode != null ->
                if (currentRecoveryCode == "PROVIDER_WAIT_OUTCOME_UNKNOWN") Decision(Kind.RECONCILE_REMOTE_OUTCOME, "RECONCILE_REMOTE_OUTCOME", "Review uncertain write") else inspection(recoveryAttempt.text("next_action").ifBlank { "REVIEW_SAVED_RECOVERY" })
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

    /** Read the current child only from the same protected task store; its completion cannot finish acceptance. */
    fun withOutputCheckpoint(report: JsonObject, output: JsonObject?, binding: String): JsonObject {
        if (report["binding"] != JsonPrimitive(binding) || (output?.get("context") as? JsonObject)?.get("task_binding") != JsonPrimitive(binding)) return attach(report)
        val current = requireNotNull(output)
        val code = current.text("current_failure_code").takeIf { it in dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.CODES }
        val attempt = current["latest_recovery_failure"] as? JsonObject
        val projection = buildJsonObject {
            current["state"]?.let { put("state", it) }
            current["resume_allowed"]?.let { put("resume_allowed", it) }
            put("task_binding", binding)
        }
        val refusal = if (current["state"] == JsonPrimitive("BLOCKED_RECOVERY") &&
            code != null &&
            (attempt?.get("checkpoint_binding") == null || attempt["checkpoint_binding"] == JsonPrimitive(binding))
        ) {
            buildJsonObject {
                put("current_failure_code", code)
                attempt?.let { put("latest_recovery_failure", it) }
                put("task_outcome", "BLOCKED")
                put("completion_verified", false)
                put("resume_allowed", false)
            }
        } else {
            JsonObject(emptyMap())
        }
        return attach(JsonObject(report + refusal + ("output_checkpoint" to projection)))
    }

    fun attach(report: JsonObject): JsonObject {
        val decision = resolve(report)
        val pending = (report["remaining_requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { it["id"] }
        val output = report["output_checkpoint"] as? JsonObject
        val scheduler = report.text("provider_wait_scheduler_state")
        val checkpoint = buildJsonObject {
            put("schema", "github.task-recovery-checkpoint.v1")
            put("decision_scope", "TASK")
            report["binding"]?.let { put("binding", it) }
            report["request_digest"]?.let { put("request_digest", it) }
            report["observed_at_ms"]?.let { put("observed_at_ms", it) }
            put("task_complete", decision.kind == Kind.NONE && report["completion_verified"] == JsonPrimitive(true))
            put("response_delivered", report["response_delivered"] == JsonPrimitive(true))
            output?.get("state")?.let { put("output_attempt_state", it) }
            put("pending_requirement_ids", JsonArray(pending))
            put("effective_recovery", decision.json())
            put(
                "wake_owner",
                when {
                    decision.kind == Kind.NONE -> "NONE"
                    decision.kind == Kind.WAIT_FOR_PROVIDER && report["provider_wait_persisted"] == JsonPrimitive(true) && scheduler == "ENQUEUED" -> "PROVIDER_WAIT_SCHEDULER"
                    decision.kind == Kind.WAIT_FOR_PROVIDER && report["provider_wait_persisted"] == JsonPrimitive(true) -> "PROVIDER_WAIT_REATTACHMENT"
                    decision.continueWork || decision.kind == Kind.WAIT_FOR_PROVIDER -> "UNCONFIRMED"
                    else -> "USER_ACTION"
                }
            )
            put("request_replay_allowed", false)
        }
        // This projection is saved inside the same verdict as pending work. A child
        // output checkpoint cannot retire acceptance or invent a registered wake.
        return JsonObject(report + mapOf("effective_recovery" to decision.json(), "task_recovery_checkpoint" to checkpoint))
    }

    fun publicationText(text: String, report: JsonObject, receipts: Map<String, JsonObject>): String {
        val decision = resolve(report)
        val original = report["original_failure"] as? JsonObject ?: return text
        if (decision.kind != Kind.INSPECT_FAILURE || decision.action != "INSPECT_EXECUTION_FAILURE") return text
        val invocation = original.text("invocation_id").takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) }
        val reads = receipts.values.count {
            it["trusted"] == JsonPrimitive(true) &&
                it["success"] == JsonPrimitive(true) &&
                (it["arguments"] as? JsonObject)?.let { args -> dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(it.text("tool"), args) } == true
        }
        val beforeAction = text.substringBeforeLast("\n\n**Next action:** ", text).trimEnd()
        return buildString {
            append(beforeAction)
            append("\n\n**Next action** · Review the failed provider request")
            invocation?.let { append(" `$it`") }
            append(" and $reads retained successful read receipts. Check remote acceptance before starting a fresh task from the saved evidence. The failed request remains paused.")
        }
    }

    private fun inspection(action: String): Decision = Decision(
        Kind.INSPECT_FAILURE,
        when (action) {
            "RESUME_VERIFICATION" -> "INSPECT_VERIFICATION_FAILURE"
            "", "NONE", "RESUME_UNFINISHED_WORK", "CONTINUE_FROM_THE_FIRST_UNVERIFIED_CRITERION" -> "INSPECT_EXECUTION_FAILURE"
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
