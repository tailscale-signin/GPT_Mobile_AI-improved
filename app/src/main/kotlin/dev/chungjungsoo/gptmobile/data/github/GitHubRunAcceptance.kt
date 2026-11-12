package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A compact, protected link from the delivered verifier verdict to its exact message owner. */
internal object GitHubRunAcceptance {
    private const val SCHEMA = "github.run-acceptance.v1"

    data class Assessment(
        val verification: String,
        val evidenceId: String,
        val requiredCriteria: Int,
        val verifiedCriteria: Int,
        val unresolvedOutcomes: Int,
        val diagnostics: JsonObject? = null,
        val diagnosticPages: List<JsonObject> = emptyList(),
        val taskState: String? = null,
        val stopCode: String? = null,
        val approvalState: String? = null,
        val taskOutcome: String? = null,
        val diagnosticsComplete: Boolean = false
    )

    data class CommitEvidence(val expected: Boolean, val acceptance: Assessment?)

    /** Durable identity survives process death even when an encrypted payload cannot be recovered. */
    suspend fun readForCommit(
        runId: String,
        fence: Long,
        chatId: Int,
        messageId: Int,
        response: String,
        expectedInMemory: Boolean,
        recordExists: suspend () -> Boolean,
        load: suspend () -> JsonObject?
    ): CommitEvidence {
        val durable = recordExists() // An unavailable identity lookup cannot authorize completion.
        val value = load()
        return CommitEvidence(expectedInMemory || durable || value != null, assess(value, runId, fence, chatId, messageId, response))
    }

    fun key(runId: String, fence: Long): String = "github-run-acceptance:" + GitHubEvidenceBook.digest("$runId:$fence")

    /** Called before the terminal database transition; a delivery callback is not durable acceptance. */
    fun requireCompletion(completed: Boolean, acceptanceExpected: Boolean, acceptance: Assessment?) {
        if (!completed || !acceptanceExpected) return
        check(acceptance != null) { "GITHUB_EVIDENCE_RETENTION_INCOMPLETE: The run-linked acceptance receipt is unavailable; completion cannot be committed." }
        check(acceptance.verification != "WAITING_FOR_APPROVAL") {
            "WAITING_FOR_APPROVAL: The staged change is paused for its existing native approval card; resolve that card before marking the run complete."
        }
        check(acceptance.verification != "PARTIAL_EVIDENCE") {
            "PARTIAL_EVIDENCE: Acceptance diagnostics are missing or incomplete pages; a final acceptance verdict cannot be issued."
        }
        check(acceptance.verification != "REJECTED") {
            "APPROVAL_REJECTED: The exact staged mutation was rejected; do not retry or report the change as completed."
        }
        check(acceptance.verification == "VERIFIED") {
            (if (acceptance.unresolvedOutcomes > 0) "RECONCILIATION_REQUIRED" else "GITHUB_TASK_INCOMPLETE") +
                ": The delivered response does not satisfy this run's verified acceptance criteria."
        }
    }

    /** Called only with the authoritative verdict returned after its durable delivery checkpoint. */
    fun capture(report: JsonObject, runId: String, fence: Long, chatId: Int, messageId: Int): JsonObject {
        val acceptance = report["acceptance_ledger"] as? JsonObject
        val execution = report["execution_ledger"] as? JsonObject
        val criteria = (acceptance?.get("requirements") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it["required"] != JsonPrimitive(false) }
        val verified = criteria.count { it["state"] == JsonPrimitive("PASS") }
        val unresolved = execution?.number("unresolved_remote_outcomes")
        val delivered = report["response_delivered"] == JsonPrimitive(true)
        val responseHash = report.text("response_sha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        val pendingApprovals = (execution?.get("pending_approvals") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val resolvedApprovals = (execution?.get("resolved_approvals") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val deadlineReached = report.text("blocking_condition") == "ACCEPTED_TASK_DEADLINE_REACHED"
        val primaryFailure = GitHubExecutionLedger.hasPrimaryFailure(report)
        val approvalIsPrimary = !deadlineReached && !primaryFailure && (unresolved ?: 0) == 0
        val hasRejectedApproval = resolvedApprovals.any { it.text("decision_outcome") == "REJECTED" || it.text("local_resolution_event") == "approval_card_rejected" }
        val hasPendingApproval = pendingApprovals.isNotEmpty() || report.text("task_state") == "WAITING_FOR_APPROVAL" || report.text("blocking_condition") == "WAITING_FOR_APPROVAL"
        val rejectedApproval = approvalIsPrimary && hasRejectedApproval
        val pendingApproval = approvalIsPrimary && hasPendingApproval
        val taskState = when {
            pendingApproval -> "WAITING_FOR_APPROVAL"
            rejectedApproval -> "REJECTED"
            else -> report.text("task_state")
        }
        val stopReason = report["stop_reason"] as? JsonObject
        val stopCode = when {
            pendingApproval -> "WAITING_FOR_APPROVAL"
            rejectedApproval -> "APPROVAL_REJECTED"
            else -> report.text("stop_code").ifBlank { stopReason?.text("code").orEmpty() }.takeIf(String::isNotBlank)
        }
        val approvalState = when {
            hasPendingApproval -> pendingApprovals.firstNotNullOfOrNull { it.text("approval_state").takeIf(String::isNotBlank) ?: it.text("state").takeIf(String::isNotBlank) } ?: "WAITING_FOR_APPROVAL"
            hasRejectedApproval -> "REJECTED"
            else -> report.text("approval_state").takeIf(String::isNotBlank)
        }
        val taskOutcome = when {
            pendingApproval -> "WAITING_FOR_APPROVAL"
            rejectedApproval -> "REJECTED"
            else -> report.text("task_outcome")
        }
        val diagnostics = (acceptance?.get("diagnostic_projection") as? JsonObject)?.takeIf(GitHubAcceptanceDiagnostics::valid)
            ?: GitHubAcceptanceDiagnostics.snapshot(acceptance ?: JsonObject(emptyMap()))
        val pages = (acceptance?.get("diagnostic_pages") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .takeIf { GitHubAcceptanceDiagnostics.validPages(it) && it.first().text("criteria_sha256") == diagnostics.text("criteria_sha256") }
        val diagnosticsComplete = diagnostics.number("omitted_count") == 0 || pages != null
        val complete = delivered &&
            responseHash != null &&
            !primaryFailure &&
            !hasPendingApproval &&
            !hasRejectedApproval &&
            report["completion_verified"] == JsonPrimitive(true) &&
            report.text("task_outcome") == "COMPLETED" &&
            !rejectedApproval &&
            report.text("task_state") == "COMPLETED" &&
            report["evidence_retention_complete"] == JsonPrimitive(true) &&
            acceptance?.get("completion_verified") == JsonPrimitive(true) &&
            criteria.isNotEmpty() &&
            verified == criteria.size &&
            execution?.text("requested_task") == "VERIFIED_COMPLETE" &&
            unresolved == 0 &&
            (execution?.number("receipt_count") ?: 0) > 0
        val state = when {
            unresolved != null && unresolved > 0 -> "UNKNOWN_OUTCOME"
            pendingApproval -> "WAITING_FOR_APPROVAL"
            rejectedApproval -> "REJECTED"
            !diagnosticsComplete -> "PARTIAL_EVIDENCE"
            complete -> "VERIFIED"
            report.text("task_outcome") in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") -> "BLOCKED"
            report.text("task_outcome") == "FAILED" || criteria.any { it["state"] == JsonPrimitive("FAIL") } -> "FAILED"
            else -> "PARTIAL"
        }
        return buildJsonObject {
            put("schema", SCHEMA)
            put("run_id", runId)
            put("ownership_fence", fence)
            put("chat_id", chatId)
            put("message_id", messageId)
            put("verification", state)
            taskState.takeIf(String::isNotBlank)?.let { put("task_state", it) }
            stopCode?.let { put("stop_code", it) }
            approvalState?.let { put("approval_state", it) }
            taskOutcome.takeIf(String::isNotBlank)?.let { put("task_outcome", it) }
            put("binding", report.text("binding"))
            put("verdict_sha256", GitHubEvidenceBook.digest(report.toString()))
            put("response_delivered", delivered)
            responseHash?.let { put("response_sha256", it) }
            put("required_criteria", criteria.size)
            put("verified_criteria", verified)
            put("unresolved_remote_outcomes", unresolved ?: -1)
            put("diagnostics_complete", diagnosticsComplete)
            put("diagnostic_projection", diagnostics)
            if (pages != null) put("diagnostic_pages", JsonArray(pages))
        }
    }

    fun assess(value: JsonObject?, runId: String, fence: Long, chatId: Int, messageId: Int, response: String): Assessment? {
        if (value == null ||
            value.text("schema") != SCHEMA ||
            value.text("run_id") != runId ||
            (value["ownership_fence"] as? JsonPrimitive)?.longOrNull != fence ||
            value.number("chat_id") != chatId ||
            value.number("message_id") != messageId
        ) {
            return null
        }
        val required = value.number("required_criteria")?.takeIf { it >= 0 } ?: return null
        val verified = value.number("verified_criteria")?.takeIf { it in 0..required } ?: return null
        val unresolved = value.number("unresolved_remote_outcomes") ?: return null
        val recorded = value.text("verification").takeIf { it in setOf("VERIFIED", "PARTIAL", "PARTIAL_EVIDENCE", "BLOCKED", "FAILED", "UNKNOWN_OUTCOME", "WAITING_FOR_APPROVAL", "REJECTED") } ?: return null
        val state = when {
            unresolved > 0 -> "UNKNOWN_OUTCOME"
            recorded == "VERIFIED" &&
                (
                    unresolved != 0 ||
                        required == 0 ||
                        verified != required ||
                        value["response_delivered"] != JsonPrimitive(true) ||
                        value.text("response_sha256") != GitHubEvidenceBook.digest(response)
                    ) -> "PARTIAL"
            else -> recorded
        }
        val diagnostics = (value["diagnostic_projection"] as? JsonObject)?.takeIf(GitHubAcceptanceDiagnostics::valid)
        val pages = (value["diagnostic_pages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .takeIf { GitHubAcceptanceDiagnostics.validPages(it) && it.first().text("criteria_sha256") == diagnostics?.text("criteria_sha256") }.orEmpty()
        val diagnosticsComplete = diagnostics?.number("omitted_count") == 0 || pages.isNotEmpty()
        val finalState = when {
            unresolved > 0 -> "UNKNOWN_OUTCOME"
            recorded == "WAITING_FOR_APPROVAL" -> "WAITING_FOR_APPROVAL"
            recorded == "REJECTED" -> "REJECTED"
            !diagnosticsComplete -> "PARTIAL_EVIDENCE"
            else -> state
        }
        return Assessment(
            finalState,
            key(runId, fence),
            required,
            verified,
            unresolved,
            diagnostics,
            pages,
            value.text("task_state").takeIf(String::isNotBlank),
            value.text("stop_code").takeIf(String::isNotBlank),
            value.text("approval_state").takeIf(String::isNotBlank),
            value.text("task_outcome").takeIf(String::isNotBlank),
            diagnosticsComplete
        )
    }

    /** One bounded record per criterion preserves readable blockers when the surrounding log is truncated. */
    fun diagnosticLines(assessment: Assessment, runId: String? = null): List<String> {
        val projection = assessment.diagnostics?.takeIf(GitHubAcceptanceDiagnostics::valid) ?: return listOf(
            buildJsonObject {
                put("schema", "github.acceptance-summary.v1")
                runId?.let { put("run_id", it) }
                put("status", "NOT_RETAINED")
                put("required", assessment.requiredCriteria)
                put("verified", assessment.verifiedCriteria)
            }.toString()
        )
        val digest = projection.text("criteria_sha256")
        val pages = assessment.diagnosticPages.takeIf { GitHubAcceptanceDiagnostics.validPages(it) && it.first().text("criteria_sha256") == digest }
        val rows = (pages ?: listOf(projection)).flatMap { (it["criteria"] as JsonArray).filterIsInstance<JsonObject>() }
        val total = projection.number("criterion_count") ?: rows.size
        return buildList {
            add(
                buildJsonObject {
                    put("schema", "github.acceptance-summary.v1")
                    runId?.let { put("run_id", it) }
                    put("verification", assessment.verification)
                    put("total", total)
                    put("included", rows.size)
                    put("omitted", total - rows.size)
                    put("pages", pages?.size ?: 1)
                    put("criteria_sha256", digest)
                    put("scope", "REQUEST_ACCEPTANCE")
                    put("diagnostics_complete", assessment.diagnosticsComplete)
                    put("required", assessment.requiredCriteria)
                    put("verified", assessment.verifiedCriteria)
                    put("counts_scope", if (rows.size == total) "ALL_RETAINED_CRITERIA" else "INCLUDED_CRITERIA_ONLY")
                    put("requirement_counts", GitHubAcceptanceLedger.counts(rows.filter { it.text("kind") == "REQUIREMENT" }))
                    put("capability_counts", GitHubAcceptanceLedger.counts(rows.filter { it.text("kind") == "CAPABILITY" }))
                    assessment.taskState?.let { put("task_state", it) }
                    assessment.stopCode?.let { put("stop_code", it) }
                    assessment.approvalState?.let { put("approval_state", it) }
                    assessment.taskOutcome?.let { put("task_outcome", it) }
                }.toString()
            )
            rows.sortedBy { if (it["required"] == JsonPrimitive(true) && it.text("state") != "PASS") 0 else 1 }.forEach { row ->
                add(
                    buildJsonObject {
                        put("schema", "github.acceptance-criterion.v1")
                        runId?.let { put("run_id", it) }
                        put("criterion", row)
                        put("criterion_sha256", GitHubEvidenceBook.digest(row.toString()))
                        put("criteria_sha256", digest)
                        put("scope", if (row.text("kind") == "CAPABILITY") "INTEGRATION_CAPABILITY" else "TASK_REQUIREMENT")
                    }.toString()
                )
            }
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
