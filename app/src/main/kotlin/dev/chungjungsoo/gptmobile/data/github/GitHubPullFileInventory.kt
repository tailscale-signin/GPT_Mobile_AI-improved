package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

        fun provesComplete(data: JsonObject, total: Int?): Boolean {
            val coverage = data["changed_file_coverage"] as? JsonObject ?: return false
            return total != null &&
                coverage["source"] == JsonPrimitive("GITHUB_PR_FILES") &&
                coverage["complete"] == JsonPrimitive(true) &&
                coverage["expected"] == JsonPrimitive(total) &&
                coverage["observed_unique"] == JsonPrimitive(total) &&
                coverage["missing"] == JsonPrimitive(0) &&
                listOf("head_sha", "base_sha").all { field ->
                    val sha = data[field] as? JsonPrimitive
                    sha != null && sha.isString && revision.matches(sha.content) && coverage[field] == sha
                }
        }

        fun recoveryArguments(original: JsonObject, data: JsonObject, gap: JsonElement): JsonObject? {
            val coverage = data["changed_file_coverage"] as? JsonObject ?: return null
            val head = (coverage["head_sha"] as? JsonPrimitive)?.takeIf { it.isString && revision.matches(it.content) } ?: return null
            val base = (coverage["base_sha"] as? JsonPrimitive)?.takeIf { it.isString && revision.matches(it.content) } ?: return null
            val pageSize = (data["effective_per_page"] as? JsonPrimitive)?.takeIf { !it.isString && it.content.toIntOrNull() in 1..100 } ?: return null
            if (coverage["source"] != JsonPrimitive("GITHUB_PR_FILES") || coverage["reason"] != JsonPrimitive("MISSING_PR_FILE_PAGES") || gap != coverage["first_unobserved_page"]) return null
            if (listOfNotNull(original["expected_head_sha"], data["head_sha"]).any { it != head } ||
                listOfNotNull(original["expected_base_sha"], data["base_sha"]).any { it != base }
            ) {
                return null
            }
            // A page-one live refresh resets the retained inventory. Both revisions
            // still undergo the native before/after fence for the missing file read.
            return JsonObject(
                (original - setOf("verify_live", "check_page")) + buildJsonObject {
                    put("page", gap)
                    put("per_page", pageSize)
                    put("expected_head_sha", head)
                    put("expected_base_sha", base)
                    put("include_files", true)
                    put("include_checks", false)
                    put("include_health", false)
                }
            )
        }
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
        val more = consistent && last !in pages && page < last
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
                if (consistent && total <= REST_LIMIT && revision.matches(base) && revision.matches(head)) {
                    (1..last).firstOrNull { it !in pages }?.let {
                        put("first_unobserved_page", it)
                        put("remote_pages_observed", JsonArray(pages.keys.sorted().map(::JsonPrimitive)))
                    }
                }
                if (total != null && total > REST_LIMIT) put("recovery", "Use the authorized Gateway repository mirror at the pinned PR base and head.")
            }
        }
    }
}
