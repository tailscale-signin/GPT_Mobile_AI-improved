package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Small same-run projections; a large acceptance checklist is not needed to explain a failure. */
internal data class DiagnosticFailureEvidence(val recordId: String, val source: String, val payload: JsonObject)

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
    val verification = evidence.firstOrNull { it.source == "VERIFICATION_CURRENT" } ?: evidence.firstOrNull { it.source == "VERIFICATION_EVENT" }
    val reviewResolved = runStatus == "COMPLETED" || verification?.payload?.get("completion_verified") == JsonPrimitive(true)
    val retained = evidence.filter { it.source != "REVIEW_CHECKPOINT" || !reviewResolved && unresolvedGitHubReviewFailure(it.payload) }
        .sortedBy { if (it.source == "REVIEW_CHECKPOINT") 0 else 1 }
    // Review failure owns its causal invocation. Missing reviewer evidence cannot
    // be filled with the ID/stage/recovery of a successful earlier generation.
    val causal = retained.filter { it.source == "REVIEW_CHECKPOINT" }.ifEmpty { retained }
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
    val failure = read("code", "verification_failure", "failure_code", "stop_code")
        ?: retained.firstNotNullOfOrNull { item -> (item.payload["stop_reason"] as? JsonObject)?.text("code")?.let { Value(it, item.source) } }
        ?: terminalCode?.let { Value(it, "TERMINAL_ERROR") }
    val taskState = read("task_state")
    val taskOutcome = read("task_outcome")
    val approvalState = read("approval_state")
    val stopCode = read("stop_code") ?: failure
    val failedStage = readFrom(causal, "failed_stage")
        ?: readFrom(causal, "phase")?.takeIf { it.value.matches(Regex("[a-z][a-z0-9_]{1,63}")) }
        ?: Regex(";\\s*([a-z][a-z0-9_]{2,63})\\)").find(terminalError.orEmpty())?.groupValues?.get(1)?.let { Value(it, "TERMINAL_ERROR") }
    val recovery = readFrom(causal, "recovery_action") ?: when (failure?.value) {
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
        put("recovery_action", record("recovery_action", recovery))
        put("tested_sha", record("tested_sha", testedSha))
        put("tested_sha_status", if (testedSha == null) "NOT_RETAINED" else "EXACT_SHA_RETAINED")
        put("observed_head_sha", record("observed_head_sha", observedSha))
        put("observed_head_scope", "Observed or expected revision only; no test execution implied")
        put("field_sources", JsonObject(sources))
        put("source_record_ids", JsonArray(retained.map { JsonPrimitive(it.recordId) }))
        put("projection_limitations", JsonArray(limitations))
        put("scope", "SAME_RUN_RETAINED_EVIDENCE; failure-code recovery is a recommended action, not an observed recovery")
        put("captured_at_millis", capturedAtMillis)
    }
}
