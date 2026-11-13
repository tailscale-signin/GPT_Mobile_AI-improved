package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Exact verdict/checkpoint only. Missing counters stay absent, and no retained operation is replayed. */
internal object GitHubAcceptanceReportDiagnostics {
    fun fields(verdict: JsonObject, retainedReport: Boolean): JsonObject = buildJsonObject {
        val ledger = verdict["acceptance_ledger"] as? JsonObject ?: return@buildJsonObject
        put("diagnostic_area", "ACCEPTANCE")
        put("diagnostic_capture_mode", "INCIDENT")
        put("acceptance_evidence_scope", if (retainedReport) "RETAINED_VERDICT" else "CURRENT_VERDICT")
        val unmet = listOf("requirements", "capabilities").asSequence()
            .flatMap { (ledger[it] as? JsonArray).orEmpty().asSequence() }.filterIsInstance<JsonObject>()
            .filter { it["required"] == JsonPrimitive(true) && it["state"] != JsonPrimitive("PASS") }
            .toList()
        put("unmet_criterion_count", unmet.size)
        for ((section, target) in listOf("requirements" to "unmet_requirement_count", "capabilities" to "unmet_capability_count")) {
            val rows = ledger[section] as? JsonArray ?: continue
            put(target, rows.filterIsInstance<JsonObject>().count { it["required"] == JsonPrimitive(true) && it["state"] != JsonPrimitive("PASS") })
        }
        val selected = unmet.take(8)
        put("unmet_criterion_omitted_count", unmet.size - selected.size)
        if (selected.isNotEmpty()) {
            val projection = GitHubAcceptanceDiagnostics.snapshot(buildJsonObject { put("requirements", JsonArray(selected)) }, limit = 8)
            val rows = (projection["criteria"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            // An absent source ID or reason is unknown; do not invent a hashed placeholder.
            if (selected.all { it.text("id").isNotBlank() }) {
                put("unmet_criterion_id_hashes", JsonArray(rows.map { requireNotNull(it["id_sha256"]) }))
            }
            if (selected.all { it.text("reason").isNotBlank() }) {
                put("unmet_criterion_reason_hashes", JsonArray(selected.map { JsonPrimitive(GitHubEvidenceBook.digest("gptmobile.acceptance-reason.v1:${it.text("reason")}")) }))
            }
            for ((source, target) in listOf("category" to "unmet_criterion_categories", "state" to "unmet_criterion_states", "blocker" to "unmet_criterion_blockers")) {
                put(target, JsonArray(rows.map { requireNotNull(it[source]) }))
            }
        }
        val checklist = verdict["test_checklist"] as? JsonObject
        val budget = checklist?.get("stage_budget") as? JsonObject ?: verdict["stage_budget"] as? JsonObject
        for (key in listOf("effective_call_limit", "used_calls", "remaining_calls", "verification_reserve")) {
            budget?.number(key)?.let { put(key, it) }
        }
        (verdict["stop_reason"] as? JsonObject)?.number("pending_continuations")?.let { put("pending_continuations", it) }
        (verdict["execution_ledger"] as? JsonObject)?.number("unresolved_remote_outcomes")?.let { put("unresolved_outcomes", it) }
        val checkpoint = buildJsonObject {
            put("acceptance_ledger", ledger)
            budget?.let { put("stage_budget", it) }
        }
        put("checkpoint_hash", GitHubEvidenceBook.digest(checkpoint.toString()))
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..16_000_000 }
}
