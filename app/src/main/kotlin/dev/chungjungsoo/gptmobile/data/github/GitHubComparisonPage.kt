package dev.chungjungsoo.gptmobile.data.github

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One declared page window for pinned tree comparisons and their continuations. */
internal object GitHubComparisonPage {
    const val DEFAULT_SIZE = 100
    const val MAX_COVERAGE_PAGES = 512
    private const val GENERATION_FIELD = "pagination_generation"
    private val generationNamespace = UUID.randomUUID().toString().replace("-", "")
    private val generationOrdinal = AtomicLong()

    /** The process namespace separates restored observations from newly issued traversals. */
    data class Generation(val namespace: String, val ordinal: Long, val startsLiveRefresh: Boolean) {
        val identity: String get() = "$namespace:$ordinal"
        fun evidence(): JsonObject = buildJsonObject {
            put("namespace", namespace)
            put("ordinal", ordinal)
            put("starts_live_refresh", startsLiveRefresh)
        }
    }

    fun newGeneration(startsLiveRefresh: Boolean): Generation = Generation(generationNamespace, generationOrdinal.incrementAndGet(), startsLiveRefresh)

    /** Read only locally authored execution metadata, never pagination fields from response data. */
    fun generation(execution: JsonObject?): Generation? {
        val value = execution?.get(GENERATION_FIELD) as? JsonObject ?: return null
        if (value.keys != setOf("namespace", "ordinal", "starts_live_refresh")) return null
        val namespace = (value["namespace"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return null
        val ordinal = (value["ordinal"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: return null
        val refresh = (value["starts_live_refresh"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        return Generation(namespace, ordinal, refresh)
    }

    /** An unassessed backend response cannot supply or retain a generation stamp. */
    fun withGeneration(execution: JsonObject?, generation: Generation?): JsonObject? {
        val fields = execution.orEmpty() - GENERATION_FIELD
        val qualified = if (generation == null) fields else fields + (GENERATION_FIELD to generation.evidence())
        return qualified.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

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

    fun exceedsCoverageLimit(result: JsonObject): Boolean {
        val total = (result["files_total"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return false
        val size = observedSize(result) ?: return false
        return total.toLong() > size.toLong() * MAX_COVERAGE_PAGES
    }

    /** Retain this exact sample without offering a traversal that cannot establish coverage. */
    fun limitResult(arguments: JsonObject, result: JsonObject): JsonObject? {
        if (!exceedsCoverageLimit(result)) return null
        val total = (result.getValue("files_total") as JsonPrimitive).intOrNull!!
        val minimum = ((total.toLong() + MAX_COVERAGE_PAGES - 1) / MAX_COVERAGE_PAGES).toInt()
        val progress = (result["pagination_progress"] as? JsonObject).orEmpty()
        val page = (arguments["page"] as? JsonPrimitive)?.intOrNull ?: 1
        val rows = (result["files"] as? JsonArray)?.size ?: 0
        return JsonObject(
            result.filterKeys { it !in setOf("continuation", "continuation_call", "remote_continuations", "next_page") } + buildJsonObject {
                put("reason", "INCOMPLETE_REMOTE_COVERAGE")
                put("complete", false)
                put("has_more", false)
                put("upstream_has_more", result["upstream_has_more"] ?: result["has_more"] ?: JsonPrimitive(false))
                put("traversal_state", "INCOMPLETE")
                put(
                    "pagination_progress",
                    buildJsonObject {
                        put("schema", "github.pagination-progress.v1")
                        put("remote_page", page)
                        put("remote_pages_observed", JsonArray(listOf(JsonPrimitive(page))))
                        put("upstream_rows_returned", rows)
                        put("unique_records_observed", rows)
                        put("reported_total", total)
                        put("contiguous_from_first_page", page == 1)
                        put("upstream_changed_during_traversal", false)
                        put("acceptance_status", "NOT_MEASURED")
                        progress.filterKeys { it !in setOf("first_unobserved_page", "pending_remote_pages", "minimum_per_page") }.forEach { (key, value) -> put(key, value) }
                        put("complete", false)
                        put("coverage_limit_reached", true)
                        put("coverage_page_limit", MAX_COVERAGE_PAGES)
                        if (minimum <= DEFAULT_SIZE) put("minimum_per_page", minimum)
                    }
                )
                put(
                    "message",
                    if (minimum <= DEFAULT_SIZE) {
                        "The requested page is retained with its original page size. Complete coverage exceeds the $MAX_COVERAGE_PAGES-page limit. An explicit restart at page 1 with per_page=$minimum or greater can cover the same pinned comparison; no restart was dispatched."
                    } else {
                        "The requested page is retained with its original page size. This comparison exceeds $MAX_COVERAGE_PAGES pages even at the maximum $DEFAULT_SIZE files per page. Complete coverage is unavailable within this bound; no continuation or changed comparison was dispatched."
                    }
                )
            }
        )
    }

    /** A visible proposal only: keep the resolved repository and both immutable revisions. */
    fun restartArguments(arguments: JsonObject, result: JsonObject): JsonObject? {
        if (!exceedsCoverageLimit(result)) return null
        val total = (result.getValue("files_total") as JsonPrimitive).intOrNull!!
        val size = ((total.toLong() + MAX_COVERAGE_PAGES - 1) / MAX_COVERAGE_PAGES).toInt().takeIf { it in 1..DEFAULT_SIZE } ?: return null
        fun text(key: String) = (result[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
        val owner = text("owner") ?: return null
        val repo = text("repo") ?: return null
        val sha = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
        val base = text("base_sha")?.takeIf(sha::matches) ?: return null
        val head = text("head_sha")?.takeIf(sha::matches) ?: return null
        if (listOf("owner" to owner, "repo" to repo, "commit_sha" to head).any { (key, value) -> arguments[key]?.let { it !is JsonPrimitive || !it.isString || !it.content.equals(value, true) } == true }) return null
        val requestedBase = ((arguments["base_sha"] ?: arguments["base"]) as? JsonPrimitive)?.content
        if (requestedBase != null && sha.matches(requestedBase) && !requestedBase.equals(base, true)) return null
        return JsonObject(
            arguments - setOf("owner", "repo", "base", "base_sha", "ref", "branch", "commit_sha", "page", "per_page") + buildJsonObject {
                put("action", "changes")
                put("owner", owner)
                put("repo", repo)
                put("base_sha", base)
                put("commit_sha", head)
                put("page", 1)
                put("per_page", size)
            }
        )
    }

    /** Put exact recovery controls ahead of large samples so replay can retain their identity. */
    fun failureEnvelope(arguments: JsonObject, result: JsonObject, modelName: String): JsonObject {
        val progress = result["pagination_progress"] as? JsonObject ?: return result
        if (progress["coverage_limit_reached"] != JsonPrimitive(true)) return result
        val restart = restartArguments(arguments, result)
        return buildJsonObject {
            put("schema", "github.pagination-progress.v1")
            put("operation", "github_context")
            restart?.let {
                put(
                    "correction_call",
                    buildJsonObject {
                        put("tool", modelName)
                        put("arguments", it)
                    }
                )
            }
            result["reason"]?.let { put("reason", it) }
            put(
                "metadata",
                buildJsonObject {
                    listOf("effective_per_page", "files_total", "total_pages").forEach { key -> result[key]?.let { put(key, it) } }
                    listOf("coverage_limit_reached", "coverage_page_limit", "minimum_per_page", "upstream_changed_during_traversal").forEach { key -> progress[key]?.let { put(key, it) } }
                }
            )
            result.filterKeys { it !in setOf("schema", "operation", "correction_call", "metadata", "reason") }.forEach { (key, value) -> put(key, value) }
        }
    }
}
