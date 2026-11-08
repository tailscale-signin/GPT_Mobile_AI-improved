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
        val diagnostics: JsonObject? = null
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
        val complete = delivered &&
            responseHash != null &&
            report["completion_verified"] == JsonPrimitive(true) &&
            report.text("task_outcome") == "COMPLETED" &&
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
            put("binding", report.text("binding"))
            put("verdict_sha256", GitHubEvidenceBook.digest(report.toString()))
            put("response_delivered", delivered)
            responseHash?.let { put("response_sha256", it) }
            put("required_criteria", criteria.size)
            put("verified_criteria", verified)
            put("unresolved_remote_outcomes", unresolved ?: -1)
            val diagnostics = (acceptance?.get("diagnostic_projection") as? JsonObject)?.takeIf(GitHubAcceptanceDiagnostics::valid)
                ?: GitHubAcceptanceDiagnostics.snapshot(acceptance ?: JsonObject(emptyMap()))
            put("diagnostic_projection", diagnostics)
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
        val recorded = value.text("verification").takeIf { it in setOf("VERIFIED", "PARTIAL", "BLOCKED", "FAILED", "UNKNOWN_OUTCOME") } ?: return null
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
        return Assessment(state, key(runId, fence), required, verified, unresolved, (value["diagnostic_projection"] as? JsonObject)?.takeIf(GitHubAcceptanceDiagnostics::valid))
    }

    /** One bounded record per criterion preserves readable blockers when the surrounding log is truncated. */
    fun diagnosticLines(assessment: Assessment): List<String> {
        val projection = assessment.diagnostics?.takeIf(GitHubAcceptanceDiagnostics::valid) ?: return listOf("schema=github.acceptance-diagnostics.v1 · status=NOT_RETAINED · required=${assessment.requiredCriteria} · verified=${assessment.verifiedCriteria}")
        val digest = projection.text("criteria_sha256")
        return buildList {
            add("schema=github.acceptance-diagnostics.v1 · total=${projection["criterion_count"]} · included=${projection["included_count"]} · omitted=${projection["omitted_count"]} · criteria_sha256=$digest")
            (projection["criteria"] as JsonArray).filterIsInstance<JsonObject>().forEach { row ->
                val counts = (row["evidence_class_counts"] as JsonObject).filterValues { (it as? JsonPrimitive)?.intOrNull?.let { count -> count > 0 } == true }
                add("criterion=${row["ordinal"]} · id_sha256=${row.text("id_sha256")} · kind=${row.text("kind")} · category=${row.text("category")} · label=${row.text("label")} · operation=${row.text("operation").ifBlank { "NOT_APPLICABLE" }} · required=${row["required"]} · state=${row.text("state")} · blocker=${row.text("blocker")} · next_action=${row.text("next_action")} · evidence_count=${row["evidence_receipt_count"]} · evidence_classes=${JsonObject(counts)} · criterion_sha256=${GitHubEvidenceBook.digest(row.toString())} · criteria_sha256=$digest")
            }
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
