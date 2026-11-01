package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A short-lived terminal remote page is distinct from a retained local fragment cursor. */
internal class GitHubPaginationBoundary(private val now: () -> Long = System::nanoTime) {
    private data class End(val page: Int, val at: Long)
    private val ends = linkedMapOf<String, End>()
    private fun key(operation: String, args: JsonObject): String = operation + JsonObject(args.filterKeys { it !in setOf("page", "response_detail") }.toSortedMap())
    private fun page(args: JsonObject) = (args["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1

    @Synchronized fun completedPage(operation: String, args: JsonObject): Int? {
        if ("view_cursor" in args) return null
        val key = key(operation, args)
        if (page(args) == 1) {
            ends.remove(key)
            return null
        }
        val end = ends[key] ?: return null
        if (now() - end.at !in 0..30_000_000_000L) {
            ends.remove(key)
            return null
        }
        return end.page.takeIf { page(args) > it }
    }

    @Synchronized fun observe(operation: String, args: JsonObject, result: JsonObject) {
        if ("view_cursor" in args || result["has_more"] != JsonPrimitive(false)) return
        ends[key(operation, args)] = End(page(args), now())
        while (ends.size > 128) ends.remove(ends.keys.first())
    }
}
