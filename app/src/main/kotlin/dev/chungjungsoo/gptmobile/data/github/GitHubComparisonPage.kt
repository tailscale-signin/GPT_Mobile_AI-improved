package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One declared page window for pinned tree comparisons and their continuations. */
internal object GitHubComparisonPage {
    const val DEFAULT_SIZE = 100

    data class Window(val page: Int, val size: Int, val from: Int, val until: Int, val totalPages: Int) {
        val hasMore: Boolean get() = page < totalPages
    }

    fun size(arguments: JsonObject): Int {
        if ("per_page" !in arguments) return DEFAULT_SIZE
        val size = (arguments["per_page"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(size != null && size in 1..100) { "per_page must be an integer from 1 to 100." }
        return size
    }

    fun window(arguments: JsonObject, total: Int): Window {
        require(total >= 0)
        val page = if ("page" !in arguments) 1 else (arguments["page"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(page != null && page in 1..1000) { "page must be an integer from 1 to 1000." }
        val size = size(arguments)
        val from = ((page.toLong() - 1) * size).coerceAtMost(total.toLong()).toInt()
        return Window(page, size, from, (from.toLong() + size).coerceAtMost(total.toLong()).toInt(), ((total.toLong() + size - 1) / size).coerceAtLeast(1).toInt())
    }

    /** Older native comparison receipts always used 100 rows, even when requesting another size. */
    fun observedSize(result: JsonObject): Int? = if ("effective_per_page" !in result) {
        DEFAULT_SIZE
    } else {
        (result["effective_per_page"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..100 }
    }
}
