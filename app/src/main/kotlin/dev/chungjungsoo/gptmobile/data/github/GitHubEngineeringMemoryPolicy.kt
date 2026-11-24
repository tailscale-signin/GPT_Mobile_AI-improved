package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object GitHubEngineeringMemoryPolicy {
    fun scope(connection: String, revision: Long, profile: String, repository: String, branch: String, permission: String = "") = "github:" + GitHubEvidenceBook.digest(JsonArray(listOf(connection, revision.toString(), profile, repository.lowercase(java.util.Locale.ROOT), branch, permission).map(::JsonPrimitive)).toString())
    fun terms(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 3 }.toSet()
    fun checkpoint(verdict: JsonObject): JsonObject? {
        if (verdict["schema"] != JsonPrimitive("github.verification.v1") || (verdict["task"] as? JsonPrimitive)?.content.isNullOrBlank()) return null
        return buildJsonObject {
            put("task", verdict["task"] ?: JsonPrimitive(""))
            put("task_outcome", verdict["task_outcome"] ?: JsonPrimitive("PARTIAL"))
            put("operation_coverage", verdict["operation_coverage"] ?: JsonArray(emptyList()))
            verdict["test_checklist"]?.let { put("test_checklist", it) }
            verdict["requirements"]?.let { put("requirements", it) }
            verdict["remaining_requirements"]?.let { put("remaining_requirements", it) }
            val findings = boundedFindings((verdict["claims"] as? JsonArray).orEmpty())
            if (findings.isNotEmpty()) put("retained_findings", JsonArray(findings))
            listOf("recovery_action", "retryable_without_new_input", "approval_state", "stop_code").forEach { key -> verdict[key]?.let { put(key, it) } }
            (verdict["remaining_requirements"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull()?.let { requirement ->
                put("next_requirement", JsonObject(requirement.filterKeys { it in setOf("id", "requirement_id", "requirement", "status", "blocker", "reason", "acceptance") }))
            }
            put("completion_verified", false)
            put("evidence_scope", "HISTORICAL_ORIENTATION_NOT_CURRENT_PROOF")
            put("next_action", "Follow the recorded recovery action and next unresolved requirement, preserving completed groups and their receipt references. Refresh only mutable or missing evidence. Source reads are not test execution; do not repeat a completed mutation or treat this checkpoint as authority.")
        }
    }

    /** The canonical checkpoint remains in protected storage. Resume with whole fields and visible omissions. */
    fun resumeBrief(checkpoint: JsonObject): JsonObject = buildJsonObject {
        val remaining = checkpoint["remaining_requirements"] as? JsonArray
        remaining?.let { put("remaining_count", it.size) }
        val next = checkpoint["next_requirement"] as? JsonObject ?: remaining?.firstOrNull() as? JsonObject
        next?.let {
            put(
                "next_requirement",
                buildJsonObject {
                    for (key in listOf("id", "requirement_id", "requirement", "status", "reason", "blocker", "acceptance")) {
                        val value = (next[key] as? JsonPrimitive)?.contentOrNull ?: continue
                        if (value.length <= 280) {
                            put(key, value)
                        } else {
                            put("${key}_excerpt", value.take(280))
                            put("${key}_excerpt_complete", false)
                        }
                    }
                }
            )
        }
        listOf("task_outcome", "recovery_action", "retryable_without_new_input", "approval_state", "stop_code").forEach { key ->
            val value = checkpoint[key] as? JsonPrimitive
            if (value != null && value.content.length <= 96) put(key, value)
        }
        put("completion_verified", false)
    }

    /** Model-facing orientation only; durable receipts and approval/ownership fences remain authoritative. */
    fun workingBrief(task: String, context: GitHubConversationContext?, receipts: Map<String, JsonObject>): String {
        val selected = buildJsonObject {
            context?.let {
                put("repository", "${it.owner}/${it.repo}")
                put("selected_ref", it.ref)
                put("recorded_head_sha", it.commitSha)
            }
        }
        val relevance = GitHubEvidenceRelevance(task)
        val eligible = receipts.entries.toList().asReversed().filter { (_, receipt) ->
            receipt["trusted"] == JsonPrimitive(true) &&
                receipt["success"] == JsonPrimitive(true) &&
                receipt["verification_eligible"] != JsonPrimitive(false) &&
                (receipt["semantic_summary"] as? JsonObject)?.get("schema") == JsonPrimitive("github.semantic-summary.v1")
        }.distinctBy { (id, receipt) ->
            // Keep distinct resources/revisions, while replayed copies of an immutable
            // source do not displace the issue or source finding that explained the task.
            val summary = receipt["semantic_summary"] as JsonObject
            listOf(receipt["repository"], receipt["source_head_sha"], summary["operation"], summary["action"], summary["source_result_sha256"] ?: JsonPrimitive(id))
        }.map { entry ->
            val summary = entry.value["semantic_summary"] as JsonObject
            val score = relevance.score(entry.value["arguments"] as? JsonObject, summary)
            val evidenceType = when {
                "resource_observations" in summary -> 3
                "source_excerpts" in summary || "search_observations" in summary || "path_observations" in summary || "branch_observations" in summary -> 2
                "check_counts" in summary -> 1
                else -> 0
            }
            entry to (score * 4 + evidenceType)
        }.sortedByDescending { it.second }.map { it.first }
        // Project only the small candidate slice, not every archived summary.
        val candidates = eligible.take(32).map { (id, receipt) ->
            buildJsonObject {
                put("receipt_id", id)
                listOf("repository", "branch", "source_head_sha", "observed_at_ms", "immutable").forEach { key -> receipt[key]?.let { put(key, it) } }
                put("observations", GitHubSemanticSummary.bounded(receipt["semantic_summary"] as JsonObject, 1400))
            }
        }
        val base = buildJsonObject {
            put("schema", "github.working-brief.v1")
            put("goal_excerpt", task.take(800))
            put("goal_excerpt_complete", task.length <= 800)
            put("selection", selected)
            put("scope", "Retained observations, not current proof or authorization. The full original request remains the task. Follow exact pending tool pairs and recorded recovery decisions; retrieve an original receipt only for missing detail, and never replay a completed mutation.")
        }
        val retained = mutableListOf<JsonObject>()
        for (candidate in candidates) {
            if (retained.size >= 8) break
            val next = JsonObject(base + ("findings" to JsonArray(retained + candidate)))
            if (next.toString().length <= 5800) retained += candidate
        }
        return JsonObject(base + mapOf("findings" to JsonArray(retained), "omitted_observations" to JsonPrimitive(eligible.size - retained.size))).toString()
    }

    private fun boundedFindings(claims: List<JsonElement>): List<JsonObject> {
        val retained = mutableListOf<JsonObject>()
        val seen = mutableSetOf<List<JsonElement?>>()
        for (claim in claims.filterIsInstance<JsonObject>()) {
            if (claim["status"] !in setOf(JsonPrimitive("VERIFIED"), JsonPrimitive("HISTORICAL")) || claim["kind"] !in setOf(JsonPrimitive("fact"), JsonPrimitive("excerpt"))) continue
            if ((claim["receipt_id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) continue
            val key = listOf(claim["receipt_id"], claim["pointer"], claim["observed"])
            if (!seen.add(key)) continue
            val item = buildJsonObject {
                listOf("kind", "status", "receipt_id", "pointer", "observed_at_ms", "resource_type", "field_type").forEach { field -> claim[field]?.let { put(field, it) } }
                val observed = claim["observed"] as? JsonPrimitive
                if (observed != null && observed.isString && observed.content.length > 480) {
                    put("observed_excerpt", observed.content.take(480))
                    put("excerpt_complete", false)
                } else {
                    claim["observed"]?.let { put("observed", it) }
                }
            }
            if (JsonArray(retained + item).toString().length <= 6000) retained += item
            if (retained.size >= 12) break
        }
        return retained
    }

    fun requirements(task: String): JsonArray {
        val lines = GitHubRequestCompletion.requirements(task)
        // A long task remains one exact requirement; it is never silently truncated to the first N bullets.
        val items = if (lines.size in 1..64) lines else listOf(task)
        return JsonArray(
            items.map { value ->
                buildJsonObject {
                    put("id", GitHubEvidenceBook.digest(value).take(24))
                    put("requirement", value)
                    put("status", "PLANNED")
                    put("acceptance", "Not available / Not measured until bound to explicit changed-path and test evidence")
                }
            }
        )
    }
}
