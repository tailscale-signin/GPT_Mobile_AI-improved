package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A short-lived terminal remote page is distinct from a retained local fragment cursor. */
internal class GitHubPaginationBoundary(private val now: () -> Long = System::nanoTime) {
    private data class End(val page: Int, val at: Long, val repeated: Boolean = false)
    private val ends = linkedMapOf<String, End>()
    private data class PageObservation(val page: Int, val digest: String, val at: Long)
    private val pages = linkedMapOf<String, PageObservation>()
    private fun key(operation: String, args: JsonObject): String = operation + JsonObject(args.filterKeys { it !in setOf("page", "response_detail") }.toSortedMap())
    private fun page(args: JsonObject) = (args["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1

    @Synchronized fun completedPage(operation: String, args: JsonObject): Int? {
        if ("view_cursor" in args) return null
        val key = key(operation, args)
        if (page(args) == 1) {
            ends.remove(key)
            pages.remove(key)
            return null
        }
        val end = ends[key] ?: return null
        if (now() - end.at !in 0..30_000_000_000L) {
            ends.remove(key)
            return null
        }
        return end.page.takeIf { page(args) > it }
    }

    /** Ignore transport/local-fragment metadata; advancing a remote page must change its rows. */
    @Synchronized fun repeatsRemotePage(operation: String, args: JsonObject, result: JsonObject): Boolean {
        if ("view_cursor" in args) return false
        val upstream = result["upstream_record_ids"] as? JsonArray
        val rows = if (!upstream.isNullOrEmpty()) JsonObject(mapOf("upstream_record_ids" to upstream)) else JsonObject(result.filter { (name, value) -> name in setOf("items", "workflow_runs", "workflows", "jobs", "artifacts", "check_runs", "pull_requests", "commits") && value is JsonArray && value.isNotEmpty() })
        // Filtered-empty pages are not identities. They can contain distinct upstream PR records.
        if (rows.isEmpty()) return false
        val key = key(operation, args)
        val digest = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(rows.toString())
        val previous = pages[key]
        pages[key] = PageObservation(page(args), digest, now())
        while (pages.size > 128) pages.remove(pages.keys.first())
        val repeated = previous != null && page(args) > previous.page && previous.digest == digest && now() - previous.at in 0..300_000_000_000L
        if (repeated) ends[key] = End(page(args), now(), repeated = true)
        return repeated
    }

    @Synchronized fun stoppedOnRepetition(operation: String, args: JsonObject): Boolean = ends[key(operation, args)]?.repeated == true

    @Synchronized fun observe(operation: String, args: JsonObject, result: JsonObject) {
        if ("view_cursor" in args || result["has_more"] != JsonPrimitive(false)) return
        ends[key(operation, args)] = End(page(args), now())
        while (ends.size > 128) ends.remove(ends.keys.first())
    }
}
