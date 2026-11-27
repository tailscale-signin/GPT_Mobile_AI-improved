package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Same-query pages establish coverage; a newer narrow workflow query cannot replace that scope. */
internal object GitHubCiRunInventory {
    data class Snapshot(
        val receipts: Map<String, JsonObject>,
        val arguments: JsonObject,
        val tool: String,
        val complete: Boolean,
        val fresh: Boolean,
        val nextPage: Int?,
        val total: Int?,
        val observedAt: Long
    )

    fun snapshot(receipts: Map<String, JsonObject>, head: String, now: Long): Snapshot? {
        val allowed = setOf("action", "owner", "repo", "commit_sha", "head_sha", "ref", "page", "per_page", "response_detail", "verify_live")
        val candidates = receipts.filterValues { row ->
            val args = row["arguments"] as? JsonObject ?: return@filterValues false
            row["trusted"] == JsonPrimitive(true) &&
                row["success"] == JsonPrimitive(true) &&
                row["verification_eligible"] != JsonPrimitive(false) &&
                row.observed() in 1..now &&
                row.text("tool").githubOperationFamily() == "github_ci" &&
                args["action"] == JsonPrimitive("runs") &&
                args.keys.all { it in allowed } &&
                GitHubInventoryQuery.commitSha(args["commit_sha"] ?: args["head_sha"]) == JsonPrimitive(head) &&
                listOfNotNull(args["commit_sha"], args["head_sha"]).all { GitHubInventoryQuery.commitSha(it) == JsonPrimitive(head) }
        }
        val first = candidates.entries.lastOrNull { (_, row) -> page(row) == 1 } ?: return null
        val firstArgs = first.value["arguments"] as JsonObject
        val query = GitHubInventoryQuery.arguments(first.value.text("tool"), firstArgs)
        val started = first.value.observed()
        val pages = linkedMapOf<Int, Pair<String, JsonObject>>()
        var currentTraversal = false
        candidates.forEach { (id, row) ->
            if (id == first.key) currentTraversal = true
            if (currentTraversal &&
                row.text("tool") == first.value.text("tool") &&
                row["binding"] == first.value["binding"] &&
                row["repository"] == first.value["repository"] &&
                row.observed() >= started &&
                GitHubInventoryQuery.arguments(row.text("tool"), row["arguments"] as JsonObject) == query
            ) {
                page(row)?.let { pages[it] = id to row }
            }
        }
        val retained = linkedMapOf<String, JsonObject>()
        val ids = mutableSetOf<Long>()
        var total: Int? = null
        var next: Int? = 1
        var complete = false
        for (page in 1..1000) {
            val (id, row) = pages[page] ?: break
            val data = pageData(row) ?: break
            val runs = data["workflow_runs"] as? JsonArray ?: break
            val returned = (data["returned"] as? JsonPrimitive)?.intOrNull ?: break
            val count = (data["total_count"] as? JsonPrimitive)?.intOrNull
            val progress = data["pagination_progress"] as? JsonObject
            val size = ((row["arguments"] as JsonObject)["per_page"] as? JsonPrimitive)?.intOrNull ?: 100
            if (runs.size != returned ||
                size !in 1..100 ||
                returned !in 0..size ||
                data["has_more"] == JsonPrimitive(true) &&
                runs.isEmpty() ||
                listOfNotNull(data["commit_sha"], data["head_sha"]).any { GitHubInventoryQuery.commitSha(it) != JsonPrimitive(head) } ||
                data["pagination_inconsistent"] == JsonPrimitive(true) ||
                data["history_truncated"] == JsonPrimitive(true) ||
                progress?.get("upstream_changed_during_traversal") == JsonPrimitive(true) ||
                count != null &&
                (count < 0 || total != null && count != total) ||
                runs.any { it !is JsonObject || it["head_sha"] != JsonPrimitive(head) || (it["id"] as? JsonPrimitive)?.longOrNull?.let { runId -> runId > 0 && runId !in ids } != true }
            ) {
                break
            }
            val runIds = runs.filterIsInstance<JsonObject>().map { (it.getValue("id") as JsonPrimitive).longOrNull!! }
            if (runIds.distinct().size != runIds.size) break
            ids += runIds
            if (count != null) total = count
            retained[id] = row
            if (data["has_more"] == JsonPrimitive(false)) {
                complete = total == null || total == ids.size
                next = null
                break
            }
            if (data["has_more"] != JsonPrimitive(true)) {
                next = null
                break
            }
            next = (page + 1).takeIf { it <= 1000 }
        }
        return Snapshot(
            retained,
            firstArgs,
            first.value.text("tool"),
            complete,
            retained.isNotEmpty() && retained.values.all { now - it.observed() in 0..GitHubEvidencePolicy.MAX_AGE_MS },
            next,
            total,
            started
        )
    }

    fun nextCall(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, head: String, now: Long): ProviderEvent.ToolCall? {
        if (receipts.values.any { row ->
                val args = row["arguments"] as? JsonObject ?: return@any false
                val data = pageData(row) ?: return@any false
                row["success"] == JsonPrimitive(true) && GitHubStatusEvidence.fresh(row, now, null) && GitHubCiFailureObservation.emptyRevision(args, data) == head
            }
        ) {
            return null
        }
        val snapshot = snapshot(receipts, head, now)
        if (snapshot?.complete == true && snapshot.fresh || snapshot != null && snapshot.receipts.isEmpty()) return null
        val eligible = tools.filter { it.modelVisible && it.name.githubOperationFamily() == "github_ci" }
        val tool = snapshot?.tool?.let { name -> eligible.firstOrNull { it.name == name } }
            ?: eligible.singleOrNull() ?: return null
        val base = snapshot?.arguments ?: JsonObject(mapOf("action" to JsonPrimitive("runs"), "commit_sha" to JsonPrimitive(head), "per_page" to JsonPrimitive(100)))
        val page = if (snapshot == null || !snapshot.fresh) 1 else snapshot.nextPage ?: return null
        val arguments = JsonObject(base + mapOf("page" to JsonPrimitive(page), "verify_live" to JsonPrimitive(true)))
        if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) return null
        val id = "ci-inventory-" + GitHubEvidenceBook.digest("${tool.name}|$arguments|${snapshot?.observedAt ?: 0}").take(24)
        if (receipts.values.any { it["call_id"] == JsonPrimitive(id) }) return null
        return ProviderEvent.ToolCall(id, tool.name, arguments)
    }

    private fun page(row: JsonObject): Int? {
        val args = row["arguments"] as? JsonObject ?: return null
        val data = pageData(row) ?: return null
        val requested = (args["page"] as? JsonPrimitive)?.intOrNull ?: 1
        val returned = (data["page"] as? JsonPrimitive)?.intOrNull ?: requested
        return requested.takeIf { it in 1..1000 && it == returned }
    }

    /** Counts and row identities were captured from the source before any review projection. */
    fun pageData(row: JsonObject): JsonObject? {
        val root = GitHubInventoryObservation.planningRoot(row) ?: return null
        val inventory = GitHubInventoryObservation.of(row) ?: return root
        if (inventory["field"] != JsonPrimitive("workflow_runs")) return root
        if (inventory["record_keys_complete"] != JsonPrimitive(true)) return null
        val facts = inventory.filterKeys { it in setOf("page", "per_page", "total_count", "has_more", "complete", "pagination_inconsistent") } +
            listOfNotNull(inventory["fetched"]?.let { "returned" to it }).toMap()
        if (facts.any { (key, value) -> key in root && root[key] != value }) return null
        val progress = root["pagination_progress"] as? JsonObject
        val retainedProgress = inventory["upstream_changed_during_traversal"]?.let { changed ->
            mapOf("pagination_progress" to JsonObject(progress.orEmpty() + ("upstream_changed_during_traversal" to changed)))
        }.orEmpty()
        return JsonObject(root + facts + retainedProgress)
    }

    fun currentObservations(receipts: Map<String, JsonObject>, snapshot: Snapshot?): Map<String, JsonObject> {
        val first = snapshot?.receipts?.keys?.firstOrNull() ?: return receipts
        var currentTraversal = false
        return receipts.filter { (id, row) ->
            if (id == first) currentTraversal = true
            currentTraversal && row.observed() >= snapshot.observedAt
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.observed() = (get("observed_at_ms") as? JsonPrimitive)?.longOrNull ?: 0
}
