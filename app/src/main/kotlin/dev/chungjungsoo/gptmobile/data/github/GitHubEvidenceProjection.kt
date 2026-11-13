package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Review material is bounded independently of the immutable, encrypted source receipt. */
internal object GitHubEvidenceProjection {
    private val catalogFields = setOf(
        "authorized_inventory",
        "inventory_version",
        "authorized_tool_count",
        "inventory_offset",
        "returned",
        "has_more",
        "continuation_call"
    )
    private val catalogEntryFields = setOf("name", "schema_fingerprint", "semantic_schema_fingerprint", "invocation_contract_version")
    private val identities = setOf(
        "id", "number", "sha", "commit_sha", "head_sha", "base_sha", "tree_sha", "source_sha", "expected_head_sha",
        "stage_id", "task_id", "engineering_task_id", "card_id", "approval_state", "user_input_required", "execution_id", "correlation_id", "run_id", "workflow_run_id", "workflow_id", "release_id", "pull_number",
        "status", "state", "conclusion", "success", "error", "error_code", "message", "committed", "published", "merged", "draft",
        "commit", "pull_request", "release", "dispatch_receipt", "workflow_run", "execution", "receipt", "head", "base", "result", "data", "verification",
        "accepted_mutations", "unknown_mutations", "dispatched", "head_ref", "head_branch", "source_head_sha",
        "reconciled", "remote_state_verified", "remote_reconciled", "proof", "reconciliation", "verification_evidence", "parent_sha", "object",
        "branch", "ref", "tag_name", "target_commitish", "html_url", "url", "has_more", "next_page", "continuation"
    )

    /** Values are either exact or omitted. A shortened string must never become verified source text. */
    fun result(source: JsonElement, maxBytes: Int = 32 * 1024): JsonElement {
        var remaining = maxBytes
        fun visit(value: JsonElement, depth: Int): JsonElement? {
            if (depth > 12 || remaining < 16) return null
            return when (value) {
                is JsonObject -> {
                    val children = linkedMapOf<String, JsonElement>()
                    val ordered = value.entries.sortedBy { if (it.key in identities) 0 else 1 }
                    for ((key, child) in ordered) {
                        val cost = key.encodeToByteArray().size + 8
                        if (cost >= remaining) continue
                        remaining -= cost
                        visit(child, depth + 1)?.let { children[key] = it }
                    }
                    JsonObject(children)
                }
                is JsonArray -> {
                    // Null placeholders preserve the original JSON pointers of admitted records.
                    JsonArray(value.take(256).map { visit(it, depth + 1) ?: JsonNull })
                }
                else -> {
                    val cost = value.toString().encodeToByteArray().size
                    if (cost > minOf(4096, remaining)) null else value.also { remaining -= cost }
                }
            }
        }
        return visit(source, 0) ?: JsonNull
    }

    fun receipt(source: JsonObject): JsonObject {
        val original = source["result"] ?: return source
        if (source["review_projection"] == JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL")) return source
        val compact = when {
            source["tool"] == JsonPrimitive(GitHubAuthorizedCatalog.TOOL) -> catalog(original) ?: result(original)
            (source["tool"] as? JsonPrimitive)?.content?.substringBefore("__") == "github_context" &&
                (source["arguments"] as? JsonObject)?.get("action") == JsonPrimitive("checks") &&
                original is JsonObject -> result(checkRows(original))
            else -> result(original)
        }
        var excerptBytes = 0
        val excerpts = GitHubEvidenceExcerpts.collect(source).filter { (key, value) ->
            val cost = key.encodeToByteArray().size + value.toString().encodeToByteArray().size
            (excerptBytes + cost <= 24 * 1024).also { if (it) excerptBytes += cost }
        }
        return JsonObject(
            source + buildJsonObject {
                put("result", compact)
                put("retained_excerpts", JsonObject(excerpts))
                put("review_projection", "EXACT_VALUES_WITH_DURABLE_ORIGINAL")
                put("review_projection_complete", compact == original)
                source["receipt_id"]?.let { put("original_receipt_id", it) }
                put("original_result_sha256", source["result_sha256"] ?: JsonPrimitive(GitHubRepositoryStateStore.digest(original.toString())))
                put("original_retrieval", "The immutable receipt and saved result pages retain omitted text. A projection does not establish omitted facts or complete remote coverage.")
            }
        )
    }

    /** Keep exact check pointers while removing repeated producer metadata before the byte budget. */
    private fun checkRows(source: JsonObject): JsonObject {
        val data = source["data"] as? JsonObject
        val page = data ?: source
        val changes = linkedMapOf<String, JsonElement>()
        val checks = page["check_details"] as? JsonObject
        (checks?.get("check_runs") as? JsonArray)?.let { rows ->
            changes["check_details"] = JsonObject(
                checks + (
                    "check_runs" to JsonArray(
                        rows.map { raw ->
                            val row = raw as? JsonObject ?: return@map raw
                            buildJsonObject {
                                row.filterKeys { it in setOf("id", "name", "head_sha", "status", "conclusion", "required", "required_verified") }.forEach { (key, value) -> put(key, value) }
                                (row["app"] as? JsonObject)?.get("id")?.let { put("app", buildJsonObject { put("id", it) }) }
                            }
                        }
                    )
                    )
            )
        }
        val statuses = page["status_details"] as? JsonObject
        (statuses?.get("statuses") as? JsonArray)?.let { rows ->
            changes["status_details"] = JsonObject(
                statuses + (
                    "statuses" to JsonArray(
                        rows.map { raw ->
                            if (raw is JsonObject) JsonObject(raw.filterKeys { it in setOf("id", "context", "state", "target_url", "description", "created_at", "updated_at") }) else raw
                        }
                    )
                    )
            )
        }
        val compact = JsonObject(page + changes)
        return if (data == null) compact else JsonObject(source + ("data" to compact))
    }

    /** Discovery metadata may be large; its exact, bounded page proof must survive independently. */
    private fun catalog(source: JsonElement): JsonElement? {
        val envelope = source as? JsonObject ?: return null
        val data = envelope["data"] as? JsonObject
        val page = data ?: envelope
        val inventory = page["authorized_inventory"] as? JsonArray ?: return null
        // Match the producer's maximum page size. Never silently truncate or repair
        // malformed pages, duplicate names, fingerprints or continuation recipes.
        if (inventory.size > 256) return null
        val proof = JsonObject(
            page.filterKeys { it in catalogFields } + (
                "authorized_inventory" to JsonArray(
                    inventory.map { entry ->
                        if (entry is JsonObject) JsonObject(entry.filterKeys { it in catalogEntryFields }) else entry
                    }
                )
                )
        )
        if (proof.toString().encodeToByteArray().size > 128 * 1024) return null
        return if (data == null) proof else JsonObject((result(envelope) as? JsonObject).orEmpty() + ("data" to proof))
    }
}
