package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GatewayInputRejectionDetails
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Small same-run projections; a large acceptance checklist is not needed to explain a failure. */
internal data class DiagnosticFailureEvidence(val recordId: String, val source: String, val payload: JsonObject)

private val terminalRecoveryCodePattern = Regex("^(?:An unexpected error occurred: )?(PROVIDER_WAIT_CHECKPOINT_CHANGED|RESEARCH_RECOVERY_ACTION_CHANGED)(?=$|[:;])")

/** Only the structured failure of the run-owned assistant message is eligible. */
internal fun diagnosticMessageFailureEvidence(messageId: Long, timeline: JsonElement): DiagnosticFailureEvidence? {
    val failure = (timeline as? JsonArray).orEmpty().asReversed().firstNotNullOfOrNull { item ->
        val notice = item as? JsonObject ?: return@firstNotNullOfOrNull null
        (notice["profileFailure"] as? JsonObject)?.takeIf {
            notice["type"] == JsonPrimitive("NOTICE") && notice["modelAuthored"] != JsonPrimitive(true)
        }
    } ?: return null
    fun text(key: String, pattern: Regex): String? = (failure[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { pattern.matches(it) }
    val category = text("category", Regex("[A-Z][A-Z0-9_]{2,95}")) ?: return null
    val payload = buildJsonObject {
        put("failure_code", category)
        text("phase", Regex("[a-z][a-z0-9_]{1,63}"))?.let { put("failed_stage", it) }
        text("invocationId", Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))?.let { put("invocation_id", it) }
        for ((stored, projected) in listOf("dispatched" to "dispatched", "retryAllowed" to "retryable", "requestReplayAllowed" to "request_replay_allowed")) {
            (failure[stored] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put(projected, it) }
        }
        text("remoteAcceptance", Regex("YES|NO|UNKNOWN"))?.let { put("remote_acceptance", it) }
        text("dispatchState", Regex("NOT_DISPATCHED|PRECHECK_REJECTED|DISPATCHED|FAILED|COMPLETED|UNKNOWN"))?.let { put("dispatch_state", it) }
        val recovery = (failure["recoveryActions"] as? JsonArray)?.singleOrNull() as? JsonPrimitive
        recovery?.takeIf { it.isString && it.content.matches(Regex("[A-Z][A-Z0-9_]{2,95}")) }?.let { put("recovery_action", it) }
        if (category == "ENGINEERING_STATE_OR_INPUT_CHANGED") {
            GatewayInputRejectionDetails.retained(failure["engineeringRejection"] as? JsonObject)?.let { details ->
                details["reasonCode"]?.let { put("reason_code", it) }
                details["invalid_fields"]?.let { put("invalid_fields", it) }
            }
        }
        dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.retainedDetails(failure["recoveryDetails"] as? JsonObject)?.let { details ->
            put(
                "latest_recovery_failure",
                JsonObject(
                    details + buildJsonObject {
                        put("code", category)
                        put("failed_stage", "lifecycle")
                        put("task_outcome", "BLOCKED")
                        for ((stored, projected) in listOf("dispatched" to "dispatched", "retryAllowed" to "retryable", "requestReplayAllowed" to "request_replay_allowed", "remoteAcceptance" to "remote_acceptance", "dispatchState" to "dispatch_state")) failure[stored]?.let { put(projected, it) }
                    }
                )
            )
        }
    }
    return DiagnosticFailureEvidence("messages_v2:$messageId:profileFailure", "MESSAGE_PROFILE_FAILURE", payload)
}

internal fun unresolvedGitHubReviewFailure(payload: JsonObject): Boolean =
    payload["state"] in setOf(JsonPrimitive("BLOCKED"), JsonPrimitive("PENDING")) &&
        payload["review_state"] in setOf(JsonPrimitive("FAILED"), JsonPrimitive("INDETERMINATE")) &&
        payload["delivery_state"] == JsonPrimitive("WITHHELD") &&
        (payload["failure_code"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

internal fun diagnosticFailureSummary(
    runId: String?,
    runStatus: String?,
    terminalError: String?,
    appVersion: String,
    appBuild: Int,
    appCommit: String,
    evidence: List<DiagnosticFailureEvidence>,
    limitations: List<JsonObject> = emptyList(),
    capturedAtMillis: Long = System.currentTimeMillis()
): JsonObject {
    // These application-owned guards stop a local resume, after the provider
    // interruption has already been checkpointed. That older HTTP failure must
    // not become the current verdict or lend its invocation to the failed resume.
    val legacyTerminalRecoveryCode = if (runStatus in setOf("FAILED", "PARTIAL", "BLOCKED", "INTERRUPTED")) {
        terminalRecoveryCodePattern.find(terminalError.orEmpty().trim())?.groupValues?.get(1)
    } else {
        null
    }
    val verification = evidence.firstOrNull { it.source == "VERIFICATION_CURRENT" } ?: evidence.firstOrNull { it.source == "VERIFICATION_EVENT" }
    val reviewResolved = runStatus == "COMPLETED" || verification?.payload?.get("completion_verified") == JsonPrimitive(true)
    val recovery = evidence.takeUnless { reviewResolved }.orEmpty().mapNotNull { item ->
        (item.payload["latest_recovery_failure"] as? JsonObject)?.takeIf { it["failure_origin"] == JsonPrimitive("local_recovery") }
            ?.let { DiagnosticFailureEvidence(item.recordId, "RECOVERY_CHECKPOINT", it) }
    }
    val terminalRecoveryCode = legacyTerminalRecoveryCode.takeIf { recovery.isEmpty() && !reviewResolved }
    val retained = (recovery + evidence).filter {
        if (reviewResolved && it.payload["latest_recovery_failure"] != null) return@filter false
        when (it.source) {
            "REVIEW_CHECKPOINT" -> !reviewResolved && unresolvedGitHubReviewFailure(it.payload)
            "MESSAGE_PROFILE_FAILURE" -> !reviewResolved
            else -> true
        }
    }
        .sortedBy {
            when (it.source) {
                "RECOVERY_CHECKPOINT" -> 0
                "REVIEW_CHECKPOINT" -> 1
                else -> 2
            }
        }
    // Review failure owns its causal invocation. Missing reviewer evidence cannot
    // be filled with the ID/stage/recovery of a successful earlier generation.
    val causal = retained.filter { it.source == "RECOVERY_CHECKPOINT" }.ifEmpty { if (terminalRecoveryCode != null) emptyList() else retained.filter { it.source == "REVIEW_CHECKPOINT" }.ifEmpty { retained } }
    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    data class Value(val value: String, val source: String)
    fun readFrom(items: List<DiagnosticFailureEvidence>, vararg keys: String): Value? {
        for (item in items) {
            val objects = listOfNotNull(
                item.payload["verification_failure"] as? JsonObject,
                item.payload["failure_details"] as? JsonObject,
                item.payload["failure_outcome"] as? JsonObject,
                item.payload,
                item.payload["acceptance_ledger"] as? JsonObject
            )
            for (key in keys) {
                for (value in objects) {
                    value.text(key)?.let { return Value(it, item.source) }
                }
            }
        }
        return null
    }
    fun read(vararg keys: String): Value? = readFrom(retained, *keys)
    val sources = linkedMapOf<String, JsonElement>()
    fun record(key: String, value: Value?): JsonElement {
        if (value != null) sources[key] = JsonPrimitive(value.source)
        return value?.value?.let(::JsonPrimitive) ?: JsonNull
    }
    val terminalCode = Regex("\\bcode=([A-Za-z][A-Za-z0-9_]{2,95})\\b").find(terminalError.orEmpty())?.groupValues?.get(1)
        ?: Regex("\\b(?:GITHUB_[A-Z0-9_]+|GATEWAY_[A-Z0-9_]+|CLIENT_TOOL_RESULTS_REQUIRED|CONVERSATION_CONFLICT|EXECUTION_SELECTION_INVALID|WAITING_FOR_APPROVAL|PARTIAL_EVIDENCE)\\b").find(terminalError.orEmpty())?.value
    val retainedFailure = read("code", "verification_failure", "failure_code", "stop_code")
        ?: retained.firstNotNullOfOrNull { item -> (item.payload["stop_reason"] as? JsonObject)?.text("code")?.let { Value(it, item.source) } }
        ?: terminalCode?.let { Value(it, "TERMINAL_ERROR") }
    val failure = terminalRecoveryCode?.let { Value(it, "TERMINAL_ERROR") } ?: retainedFailure
    val taskState = if (terminalRecoveryCode != null || recovery.isNotEmpty()) Value(runStatus ?: "BLOCKED", "AGENT_RUN") else read("task_state")
    val taskOutcome = read("task_outcome")
    val approvalState = read("approval_state")
    val stopCode = if (terminalRecoveryCode != null) failure else read("stop_code") ?: failure
    val failedStage = terminalRecoveryCode?.let { Value("lifecycle", "FAILURE_CODE_POLICY") } ?: readFrom(causal, "failed_stage")
        ?: readFrom(causal, "phase")?.takeIf { it.value.matches(Regex("[a-z][a-z0-9_]{1,63}")) }
        ?: Regex(";\\s*([a-z][a-z0-9_]{2,63})\\)").find(terminalError.orEmpty())?.groupValues?.get(1)?.let { Value(it, "TERMINAL_ERROR") }
    val recoveryAction = readFrom(causal, "recovery_action", "next_action") ?: when (failure?.value) {
        "PROVIDER_WAIT_CHECKPOINT_CHANGED", "RESEARCH_RECOVERY_ACTION_CHANGED" -> "REVIEW_SAVED_RECOVERY"
        "conversation_conflict", "CONVERSATION_CONFLICT" -> "RECONCILE_ACTIVE_GATEWAY_JOB"
        "tool_results_required", "GATEWAY_TOOL_RESULTS_REQUIRED", "CLIENT_TOOL_RESULTS_REQUIRED" -> "RESUME_WITH_RETAINED_TOOL_RESULTS"
        "EXECUTION_SELECTION_INVALID" -> "REFRESH_SELECTED_PROFILE_AND_GATEWAY_READINESS"
        else -> null
    }?.let { Value(it, "FAILURE_CODE_POLICY") }
    val failed = retained.firstNotNullOfOrNull { item ->
        val acceptance = item.payload["acceptance_ledger"] as? JsonObject
        val requirements = listOf(
            acceptance?.get("requirements"),
            item.payload["remaining_requirements"],
            item.payload["requirements"],
            item.payload["acceptance_criteria"]
        ).flatMap { (it as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
        requirements.firstOrNull { requirement ->
            val state = requirement.text("acceptance_state") ?: requirement.text("state") ?: requirement.text("status")
            state != null && state !in setOf("PASS", "VERIFIED", "COMPLETED", "NOT_APPLICABLE", "OUT_OF_SCOPE")
        }?.let { it to item.source }
    }
    val receiptIds = failed?.first?.let { criterion ->
        listOf("receipt_ids", "missing_evidence_ids", "evidence_receipt_ids", "receipt_id").flatMap { key ->
            when (val value = criterion[key]) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                else -> emptyList()
            }
        }
    }.orEmpty().distinct().take(12)
    val testedSha = listOf("tested_sha")
        .firstNotNullOfOrNull { key -> read(key)?.takeIf { it.value.matches(Regex("(?i)[a-f0-9]{40}")) } }
    val observedSha = listOf("head_sha", "expected_head_sha", "source_head_sha", "commit_sha")
        .firstNotNullOfOrNull { key -> read(key)?.takeIf { it.value.matches(Regex("(?i)[a-f0-9]{40}")) } }
    val verdict = when {
        terminalRecoveryCode != null -> terminalRecoveryCode
        taskState?.value == "WAITING_FOR_APPROVAL" || approvalState?.value == "WAITING_FOR_APPROVAL" -> "WAITING_FOR_APPROVAL"
        verification?.payload?.get("completion_verified") == JsonPrimitive(true) || retained.firstOrNull()?.payload?.get("completion_verified") == JsonPrimitive(true) -> "VERIFIED"
        failure != null -> failure.value
        taskOutcome != null -> taskOutcome.value
        else -> runStatus ?: "NOT_RETAINED"
    }
    return buildJsonObject {
        put("kind", "compact_failure_summary")
        put("run_id", runId?.let(::JsonPrimitive) ?: JsonNull)
        put("run_status", runStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("app_version", appVersion)
        put("app_build", appBuild)
        put("app_commit", appCommit)
        put("latest_verdict", verdict)
        put("task_state", record("task_state", taskState))
        put("task_outcome", record("task_outcome", taskOutcome))
        put("stop_code", record("stop_code", stopCode))
        put("approval_state", record("approval_state", approvalState))
        val criterion = failed?.first?.let { it.text("requirement") ?: it.text("label") ?: it.text("id") }
        put("failed_criterion", record("failed_criterion", criterion?.take(360)?.let { Value(it, requireNotNull(failed).second) }))
        put("causal_receipt_ids", JsonArray(receiptIds.map(::JsonPrimitive)))
        if (receiptIds.isNotEmpty()) sources["causal_receipt_ids"] = JsonPrimitive(requireNotNull(failed).second)
        put("invocation_id", record("invocation_id", readFrom(causal, "invocation_id")))
        put("failed_stage", record("failed_stage", failedStage))
        put("failure_code", record("failure_code", failure))
        if (failure?.value == "ENGINEERING_STATE_OR_INPUT_CHANGED") {
            causal.firstNotNullOfOrNull { item ->
                val reason = item.payload["reason_code"] ?: return@firstNotNullOfOrNull null
                val safe = GatewayInputRejectionDetails.retained(JsonObject(item.payload + ("reasonCode" to reason))) ?: return@firstNotNullOfOrNull null
                item to safe
            }?.let { (item, safe) ->
                put("reason_code", safe.getValue("reasonCode"))
                put("invalid_fields", safe.getValue("invalid_fields"))
                sources["reason_code"] = JsonPrimitive(item.source)
                sources["invalid_fields"] = JsonPrimitive(item.source)
            }
        }
        if (terminalRecoveryCode != null && retainedFailure != null && retainedFailure.value != terminalRecoveryCode) {
            put("prior_interruption_code", record("prior_interruption_code", retainedFailure))
        }
        put("recovery_action", record("recovery_action", recoveryAction))
        recovery.firstNotNullOfOrNull { it.payload["original_interruption"] }?.let { put("original_interruption", it) }
        for (key in listOf("dispatched", "retryable", "request_replay_allowed", "remote_acceptance", "dispatch_state")) {
            causal.firstOrNull { it.payload[key] != null && it.payload[key] != JsonNull }?.let { item ->
                put(key, item.payload.getValue(key))
                sources[key] = JsonPrimitive(item.source)
            }
        }
        put("tested_sha", record("tested_sha", testedSha))
        put("tested_sha_status", if (testedSha == null) "NOT_RETAINED" else "EXACT_SHA_RETAINED")
        put("observed_head_sha", record("observed_head_sha", observedSha))
        put("observed_head_scope", "Observed or expected revision only; no test execution implied")
        put("field_sources", JsonObject(sources))
        put("source_record_ids", JsonArray(retained.map { JsonPrimitive(it.recordId) }.distinct()))
        put("projection_limitations", JsonArray(limitations))
        put("scope", "SAME_RUN_RETAINED_EVIDENCE; failure-code recovery is a recommended action, not an observed recovery")
        put("captured_at_millis", capturedAtMillis)
    }
}
