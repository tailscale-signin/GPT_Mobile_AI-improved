package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Remote traversal identity and coverage are separate from retained local fragment cursors. */
internal class GitHubPaginationBoundary(private val now: () -> Long = System::nanoTime) {
    private data class End(val page: Int, val at: Long, val errorCode: String? = null)
    private data class Page(val identities: Set<String>, val at: Long)
    private data class Traversal(val pages: MutableMap<Int, Page> = linkedMapOf(), var total: Int? = null, var changed: Boolean = false)
    internal data class Assessment(val data: JsonObject, val errorCode: String? = null)
    private val ends = linkedMapOf<String, End>()
    private val traversals = linkedMapOf<String, Traversal>()

    private fun key(operation: String, args: JsonObject): String {
        val workflowInventory = args["action"] == JsonPrimitive("list_workflows")
        val ignored = setOf("page", "response_detail", "verify_live") + if (workflowInventory) setOf("ref", "commit_sha") else emptySet()
        val normalized = args.filterKeys { it !in ignored } + if (workflowInventory) mapOf("per_page" to (args["per_page"] ?: JsonPrimitive(30))) else emptyMap()
        return operation + JsonObject(normalized.toSortedMap())
    }
    private fun page(args: JsonObject) = (args["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
    private fun numberedRemotePage(args: JsonObject) = "view_cursor" !in args && "index_cursor" !in args && args["action"] !in setOf(JsonPrimitive("workflow_catalog"), JsonPrimitive("search_repository"))

    @Synchronized fun completedPage(operation: String, args: JsonObject): Int? {
        if (!numberedRemotePage(args)) return null
        val key = key(operation, args)
        val end = ends[key]
        if (args["verify_live"] == JsonPrimitive(true) || page(args) == 1 && end != null) {
            if (page(args) == 1) {
                ends.remove(key)
                traversals.remove(key)
            }
            return null
        }
        if (end == null) return null
        if (now() - end.at !in 0..30_000_000_000L) {
            ends.remove(key)
            traversals.remove(key)
            return null
        }
        return end.page.takeIf { page(args) > it }
    }

    private fun rows(result: JsonObject): Map<String, JsonArray> {
        val upstream = result["upstream_record_ids"] as? JsonArray
        return if (!upstream.isNullOrEmpty()) mapOf("upstream_record_ids" to upstream) else result.filter { (name, value) -> name in COLLECTIONS && value is JsonArray }.mapValues { it.value as JsonArray }
    }

    private fun identity(collection: String, row: JsonElement): String {
        val objectRow = row as? JsonObject
        val value = objectRow?.let { it["id"] ?: it["node_id"] ?: it["sha"] ?: it["number"] ?: it["path"] } ?: row
        return collection + ":" + dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(value.toString())
    }

    /** Compare stable record identifiers, including earlier nonadjacent pages and reordered rows. */
    @Synchronized fun assessPage(operation: String, args: JsonObject, result: JsonObject): Assessment {
        if (!numberedRemotePage(args) || "has_more" !in result) return Assessment(result)
        val collections = rows(result)
        if (collections.isEmpty()) return Assessment(result)
        val key = key(operation, args)
        val state = traversals.getOrPut(key) { Traversal() }
        if (state.pages.values.any { now() - it.at !in 0..300_000_000_000L }) {
            state.pages.clear()
            state.total = null
            state.changed = false
        }
        val number = page(args)
        val identities = collections.flatMap { (collection, values) -> values.map { identity(collection, it) } }.toSet()
        val earlier = state.pages.filterKeys { it < number }.values.flatMapTo(mutableSetOf()) { it.identities }
        val previous = state.pages[number]
        if (previous != null && previous.identities != identities) state.changed = true
        val total = (result["total_count"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (state.total != null && total != null && state.total != total) state.changed = true
        if (state.total == null) state.total = total
        state.pages[number] = Page(identities, now())
        while (traversals.size > 128) traversals.remove(traversals.keys.first())
        // Keep memory bounded without silently claiming complete coverage of a truncated traversal.
        if (state.pages.size > 512) {
            state.pages.remove(state.pages.keys.min())
            state.changed = true
        }
        val all = state.pages.values.flatMapTo(mutableSetOf()) { it.identities }
        val overlap = identities.count { it in earlier }
        val repeated = identities.isNotEmpty() && overlap == identities.size
        val emptyRemotePage = identities.isEmpty() && result["has_more"] == JsonPrimitive(true) && ((result["upstream_returned"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) == 0
        val stalled = repeated || emptyRemotePage
        val contiguous = number > 0 && state.pages.size == number && state.pages.keys.minOrNull() == 1 && state.pages.keys.maxOrNull() == number
        val terminal = result["has_more"] == JsonPrimitive(false)
        val consistent = !state.changed && result["pagination_inconsistent"] != JsonPrimitive(true) && (total == null || all.size == total)
        val error = when {
            stalled -> "GITHUB_PAGINATION_STALLED"
            terminal && (!consistent && contiguous || state.changed) -> "GITHUB_PAGINATION_INCOMPLETE"
            else -> null
        }
        if (terminal || stalled) ends[key] = End(number, now(), error)
        while (ends.size > 128) ends.remove(ends.keys.first())
        val progress = buildJsonObject {
            put("schema", "github.pagination-progress.v1")
            put("remote_page", number)
            put("remote_pages_observed", JsonArray(state.pages.keys.sorted().map(::JsonPrimitive)))
            put("upstream_rows_returned", collections.values.sumOf { it.size })
            put("unique_records_observed", all.size)
            put("new_records_on_page", identities.size - overlap)
            put("duplicate_records_on_page", collections.values.sumOf { it.size } - identities.size + overlap)
            state.total?.let { put("reported_total", it) }
            put("contiguous_from_first_page", contiguous)
            put("upstream_changed_during_traversal", state.changed)
            put("complete", terminal && contiguous && consistent && !stalled)
            put("acceptance_status", "NOT_MEASURED")
        }
        val deduplicated = result.mapValues { (name, value) ->
            if (name !in COLLECTIONS || value !is JsonArray || "upstream_record_ids" in collections) {
                value
            } else {
                val seen = earlier.toMutableSet()
                JsonArray(value.filter { seen.add(identity(name, it)) })
            }
        }
        val visibleCount = collections.keys.singleOrNull()?.takeIf { it != "upstream_record_ids" }?.let { (deduplicated[it] as? JsonArray)?.size }
        val output = JsonObject(deduplicated + ("pagination_progress" to progress) + if (visibleCount != null && "returned" in result) mapOf("returned" to JsonPrimitive(visibleCount)) else emptyMap())
        return if (error == null) {
            Assessment(output)
        } else {
            Assessment(
                JsonObject(
                    output.filterKeys { it !in setOf("continuation", "continuation_call", "remote_continuations", "next_page") } + buildJsonObject {
                        put(
                            "reason",
                            if (repeated) {
                                "REPEATED_REMOTE_PAGE"
                            } else if (emptyRemotePage) {
                                "EMPTY_REMOTE_PAGE_WITH_CONTINUATION"
                            } else {
                                "INCOMPLETE_REMOTE_COVERAGE"
                            }
                        )
                        put("complete", false)
                        put("has_more", false)
                        put("traversal_state", if (stalled) "STALLED" else "INCOMPLETE")
                        put("upstream_has_more", result["has_more"] ?: JsonPrimitive(false))
                        put("message", "Remote pagination did not establish complete coverage. Retain these observations and deliberately refresh page 1 with verify_live=true; do not count repeated rows as new records or accepted tests.")
                    }
                ),
                error
            )
        }
    }

    /** Compatibility helper for callers which only need repetition detection. */
    @Synchronized fun repeatsRemotePage(operation: String, args: JsonObject, result: JsonObject): Boolean =
        assessPage(operation, args, JsonObject(result + ("has_more" to (result["has_more"] ?: JsonPrimitive(true))))).errorCode == "GITHUB_PAGINATION_STALLED"

    @Synchronized fun stoppedOnRepetition(operation: String, args: JsonObject): Boolean = ends[key(operation, args)]?.errorCode == "GITHUB_PAGINATION_STALLED"

    @Synchronized fun completionError(operation: String, args: JsonObject): String? = ends[key(operation, args)]?.errorCode

    @Synchronized fun observe(operation: String, args: JsonObject, result: JsonObject) {
        if (!numberedRemotePage(args) || result["has_more"] != JsonPrimitive(false)) return
        ends[key(operation, args)] = End(page(args), now())
        while (ends.size > 128) ends.remove(ends.keys.first())
    }

    companion object {
        private val COLLECTIONS = setOf("items", "workflow_runs", "workflows", "jobs", "artifacts", "check_runs", "pull_requests", "commits")
    }
}
