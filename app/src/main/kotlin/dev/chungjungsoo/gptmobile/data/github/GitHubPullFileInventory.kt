package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Only pages reconciled against the same PR revisions and upstream count contribute evidence. */
internal class GitHubPullFileInventory {
    companion object {
        const val REST_LIMIT = 3000
        private val revision = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
    }

    private data class Key(val root: String, val number: Int, val base: String, val head: String, val total: Int?, val size: Int)
    private data class Page(val names: Set<String>, val valid: Boolean)
    private val inventories = LinkedHashMap<Key, MutableMap<Int, Page>>(16, 0.75f, true)

    @Synchronized fun clear() = inventories.clear()

    @Synchronized fun observe(
        root: String,
        number: Int,
        base: String,
        head: String,
        total: Int?,
        size: Int,
        page: Int,
        files: JsonArray,
        refresh: Boolean
    ): JsonObject {
        val key = Key(root, number, base, head, total, size)
        if (refresh && page == 1) inventories.remove(key)
        val pages = inventories.getOrPut(key) { mutableMapOf() }
        if (inventories.size > 8) inventories.remove(inventories.keys.first())
        val names = files.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            fun name(field: String) = (item[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
            if (name("status") == "renamed" && name("previous_filename") == null) return@mapNotNull null
            name("filename")
        }.toSet()
        val remoteTotal = total?.coerceAtMost(REST_LIMIT)
        val last = remoteTotal?.let { maxOf(1, (it + size - 1) / size) }
        val expected = remoteTotal?.let { (it - (page - 1) * size).coerceIn(0, size) }
        val previous = pages[page]
        val samePage = previous == null || previous.valid && previous.names == names
        val valid = samePage && expected != null && page <= requireNotNull(last) && files.size == expected && names.size == files.size
        if (page <= (last ?: 1)) pages[page] = Page(names, valid)
        val distinct = pages.values.flatMap { it.names }.toSet().size
        val consistent = valid && pages.values.all { it.valid } && pages.values.sumOf { it.names.size } == distinct
        val complete = consistent && total == distinct && (1..last).all(pages::containsKey) && revision.matches(base) && revision.matches(head)
        val more = consistent && page < last
        return buildJsonObject {
            put("source", "GITHUB_PR_FILES")
            put("base_sha", base)
            put("head_sha", head)
            total?.let {
                put("expected", it)
                put("missing", (it - distinct).coerceAtLeast(0))
                put("outside_rest_limit", (it - REST_LIMIT).coerceAtLeast(0))
            }
            put("observed_unique", distinct)
            put("complete", complete)
            put("has_more", more)
            if (!complete && !more) {
                put(
                    "reason",
                    when {
                        total == null -> "PR_FILE_COUNT_UNAVAILABLE"
                        !consistent -> "INCONSISTENT_PR_FILE_PAGE"
                        total > REST_LIMIT -> "GITHUB_PR_FILE_LIMIT"
                        else -> "MISSING_PR_FILE_PAGES"
                    }
                )
                if (total != null && total > REST_LIMIT) put("recovery", "Use the authorized Gateway repository mirror at the pinned PR base and head.")
            }
        }
    }
}
