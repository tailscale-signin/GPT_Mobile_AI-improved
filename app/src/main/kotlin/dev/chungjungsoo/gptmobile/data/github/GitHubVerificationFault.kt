package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The emergency report must not call the renderer or verifier that just failed. */
internal object GitHubVerificationFault {
    const val CODE = "GITHUB_VERIFICATION_INTERNAL_ERROR"
    enum class Phase { DESCENDANTS, AUTHORITY, SNAPSHOT, RECEIPT_RENDERING, CLAIM_PLANNING, CLAIM_EXTRACTION, ACCEPTANCE_RENDERING, PUBLICATION }

    data class Partial(val text: String, val report: JsonObject, val failure: ProviderEvent.Failed)

    /** A refused publication guard permits exception diagnostics, never saved evidence access. */
    fun publicationDenied(error: Exception, original: ProviderEvent.Failed?): ProviderEvent.Failed {
        val fault = buildJsonObject {
            IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.GITHUB_VERIFICATION_CATCH).forEach { (key, value) -> put(key, value) }
            put("code", CODE)
            put("failed_stage", "verification")
            put("failure_origin", "APPLICATION")
            put("diagnostic_area", "PROVIDER")
            put("provider_failure_code", CODE)
            put("provider_failure_stage", "CLAIM_VERIFICATION")
            put("provider_failure_domain", "APPLICATION_VERIFICATION")
            put("verification_phase", Phase.AUTHORITY.name)
            put("response_delivered", false)
            put("completion_verified", false)
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("resume_allowed", false)
            put("recovery_action", "REVIEW_SAVED_RECOVERY")
        }
        val details = if (original == null) {
            fault
        } else {
            buildJsonObject {
                fault.filterKeys { it !in setOf("code", "provider_failure_code", "provider_failure_stage", "provider_failure_domain") }.forEach { (key, value) -> put(key, value) }
                original.errorCode?.let { put("code", it) }
                if (original.failureDetails?.get("requires_reconciliation") == JsonPrimitive(true)) put("requires_reconciliation", true)
                put("verification_failure", fault)
            }
        }
        return ProviderEvent.Failed(
            "Repository answer publication is blocked because access could not be revalidated. Review access and saved recovery before resuming.",
            errorCode = original?.errorCode ?: CODE,
            transportComplete = original?.transportComplete ?: false,
            taskOutcome = "BLOCKED",
            failureDetails = details,
            invocationId = original?.invocationId
        )
    }

    fun partial(error: Exception, phase: Phase, receipts: Map<String, JsonObject>, original: ProviderEvent.Failed?): Partial {
        val fault = buildJsonObject {
            IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.GITHUB_VERIFICATION_CATCH).forEach { (key, value) -> put(key, value) }
            put("code", CODE)
            put("failed_stage", "verification")
            put("failure_origin", "APPLICATION")
            put("diagnostic_area", "PROVIDER")
            put("provider_failure_code", CODE)
            put("provider_failure_stage", "CLAIM_VERIFICATION")
            put("provider_failure_domain", "APPLICATION_VERIFICATION")
            put("verification_phase", phase.name)
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("resume_allowed", false)
            put("user_input_required", false)
            put("recovery_action", "INSPECT_VERIFICATION_FAILURE")
            put("partial_output_preserved", true)
            put("retained_receipt_count", receipts.size)
        }
        // These are counts of stored observations, not proof of unique dispatches,
        // successful mutations, fresh remote state or completion of the user's task.
        val successes = receipts.values.count { it["success"] == JsonPrimitive(true) }
        val failures = receipts.values.count { it["success"] == JsonPrimitive(false) }
        val observations = observations(receipts)
        val findings = if (observations.isEmpty()) {
            ""
        } else {
            "\n\nSaved observations (captured state; freshness and task acceptance have not been rechecked):\n" +
                observations.joinToString("\n") { observation ->
                    "- " + observation.entries.joinToString("; ") { (key, value) -> "$key: ${display((value as? JsonPrimitive)?.content.orEmpty())}" }
                }
        }
        val text = "Repository verification stopped because of an internal application error. " +
            "The saved evidence is still available: ${receipts.size} receipt observations ($successes report success, $failures report failure). " +
            "These observations do not establish that the requested task is complete." + findings + "\n\n" +
            "Inspect the saved results and the verification diagnostic before resuming. Completed operations must not be repeated; reconcile any uncertain remote outcome first.\n\n" +
            "Diagnostic: `$CODE` during `${phase.name}`."
        val event = original?.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + ("verification_failure" to fault)))
            ?: ProviderEvent.Failed("The saved results are available, but an internal verification error prevented completion checks.", errorCode = CODE, taskOutcome = "PARTIAL", failureDetails = fault)
        val report = buildJsonObject {
            put("partial", true)
            put("task_outcome", "PARTIAL")
            put("completion_verified", false)
            put("claims_verified", false)
            put("verification_internal_error", true)
            put("verification_failure", fault)
            put("failure_code", original?.errorCode ?: CODE)
            put("failed_stage", "verification")
            put("response_delivered", false)
            put("resume_required", true)
            put("resume_allowed", false)
            put("retryable_without_new_input", false)
            put("continuation_required", false)
            put("user_input_required", false)
            put("recovery_action", "INSPECT_VERIFICATION_FAILURE")
            put("receipt_count", receipts.size)
            put("receipt_scope", "RETAINED_OBSERVATIONS")
            put("retained_observations", JsonArray(observations))
            original?.errorCode?.let { put("original_failure_code", it) }
            put(
                "execution_ledger",
                buildJsonObject {
                    put("schema", "github.execution-ledger.v1")
                    put("receipt_count", receipts.size)
                    put("verification", "FAILED")
                    put("requested_task", "INCOMPLETE")
                    put("recovery", "INSPECT_VERIFICATION_FAILURE")
                    put("retryable_without_new_input", false)
                    put("user_input_required", false)
                }
            )
        }
        return Partial(text, report, event)
    }

    /** Read only primitive source facts; no ledger, model, coercing cast or shared formatter is used. */
    private fun observations(receipts: Map<String, JsonObject>): List<JsonObject> {
        fun JsonObject.text(key: String, limit: Int = 180): String? = (get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.take(limit)
        fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }
            ?.longOrNull?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        val observations = mutableListOf<JsonObject>()
        for ((id, receipt) in receipts.entries.toList().takeLast(64).asReversed()) {
            if (observations.size >= 8) break
            if (receipt["trusted"] != JsonPrimitive(true) || receipt["success"] != JsonPrimitive(true) || receipt["verification_eligible"] == JsonPrimitive(false)) continue
            if (!id.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))) continue
            val summary = (receipt["semantic_summary"] as? JsonObject)?.takeIf { it["schema"] == JsonPrimitive("github.semantic-summary.v1") }
            val result = receipt["result"] as? JsonObject
            val data = (result?.get("data") as? JsonObject) ?: result
            val tool = receipt.text("tool").orEmpty().substringBefore("__")
            val sha = sequenceOf(receipt.text("source_head_sha"), summary?.text("commit_sha"), summary?.text("head_sha"), data?.text("commit_sha"))
                .filterNotNull().firstOrNull { it.matches(Regex("[a-fA-F0-9]{40}")) }
            val resourceRows = (summary?.get("resource_observations") as? JsonArray)
                ?: if (tool in setOf("github_issue", "github_pr")) {
                    listOf("items", "issues", "pull_requests").firstNotNullOfOrNull { data?.get(it) as? JsonArray } ?: data?.let { JsonArray(listOf(it)) }
                } else {
                    null
                }
            val sourceRows = (summary?.get("source_excerpts") as? JsonArray) ?: (summary?.get("search_observations") as? JsonArray)
                ?: if (tool in setOf("github_read", "github_search")) data?.get("items") as? JsonArray else null
            val checks = ((data?.get("check_details") as? JsonObject)?.get("check_runs") ?: data?.get("check_runs")) as? JsonArray
            val rows = resourceRows ?: sourceRows ?: checks ?: summary?.get("latest_workflow")?.let { JsonArray(listOf(it)) }
            for (row in rows.orEmpty().take(2).filterIsInstance<JsonObject>()) {
                if (observations.size >= 8) break
                val facts = buildJsonObject {
                    if (resourceRows != null) {
                        row.number("number")?.let { put("number", it) }
                        row.text("title")?.let { put("title", it) }
                        row.text("state", 32)?.takeIf { it in setOf("open", "closed", "merged") }?.let { put("state", it) }
                    } else if (sourceRows != null) {
                        row.text("path", 256)?.let { put("path", it) }
                        row.number("line")?.let { put("line", it) }
                        val start = row.number("start_line")
                        val end = row.number("end_line")
                        if (start != null && end != null && end >= start) {
                            put("start_line", start)
                            put("end_line", end)
                        }
                    } else {
                        row.text("name")?.let { put("check_name", it) }
                        row.text("status", 32)?.takeIf { it in setOf("queued", "in_progress", "completed", "waiting", "pending") }?.let { put("status", it) }
                        row.text("conclusion", 32)?.takeIf { it in setOf("success", "failure", "skipped", "cancelled", "timed_out", "neutral", "action_required") }?.let { put("conclusion", it) }
                    }
                }
                if (facts.isEmpty()) continue
                observations += buildJsonObject {
                    put("receipt_id", id)
                    sha?.let { put("captured_sha", it) }
                    facts.forEach { (key, value) -> put(key, value) }
                }
            }
        }
        return observations
    }

    private fun display(value: String): String = value.filterNot(Char::isISOControl)
        .replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
        .replace("[", "\\[").replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;")
}
