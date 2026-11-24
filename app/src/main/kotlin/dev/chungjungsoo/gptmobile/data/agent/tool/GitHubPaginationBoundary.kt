package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubComparisonPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Remote traversal identity and coverage are separate from retained local fragment cursors. */
internal class GitHubPaginationBoundary(private val now: () -> Long = System::nanoTime) {
    private data class End(val page: Int, val at: Long, val errorCode: String? = null)
    private data class Page(val identities: Set<String>, val at: Long, val terminal: Boolean)
    private data class Pending(val page: Int, val at: Long)
    private data class Traversal(
        val generation: Long,
        val receiptGeneration: GitHubComparisonPage.Generation,
        val pages: MutableMap<Int, Page> = linkedMapOf(),
        val pending: MutableMap<Long, Pending> = linkedMapOf(),
        var total: Int? = null,
        var changed: Boolean = false,
        var inconsistent: Boolean = false
    )
    internal data class Observation(val query: String, val generation: Long, val id: Long, val receiptGeneration: GitHubComparisonPage.Generation)
    internal data class Assessment(val data: JsonObject, val errorCode: String? = null, val generation: GitHubComparisonPage.Generation? = null)
    internal data class Dispatch(val observation: Observation? = null, val completedPage: Int? = null, val errorCode: String? = null)
    private val ends = linkedMapOf<String, End>()
    private val traversals = linkedMapOf<String, Traversal>()
    private var sequence = 0L

    private fun traversal(key: String, startsLiveRefresh: Boolean = false): Traversal = traversals.getOrPut(key) {
        Traversal(++sequence, GitHubComparisonPage.newGeneration(startsLiveRefresh))
    }

    private fun reset(key: String) {
        val previous = traversals[key]
        val aliases = traversals.filterValues { it === previous }.keys + key
        aliases.forEach {
            traversals.remove(it)
            ends.remove(it)
        }
    }

    private fun key(operation: String, args: JsonObject, result: JsonObject? = null): String {
        if (operation == "github_context" && args["action"] == JsonPrimitive("changed_since")) {
            // The first page can use a mutable base ref, while the exact
            // continuation uses its resolved immutable SHA. Both are one
            // traversal only when the response proves the same pinned pair.
            fun sha(value: kotlinx.serialization.json.JsonElement?): String? =
                (value as? JsonPrimitive)?.content?.takeIf {
                    it.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
                }?.lowercase()
            val base = sha(result?.get("base_sha") ?: args["base"] ?: args["base_sha"])
            val head = sha(result?.get("head_sha") ?: args["commit_sha"])
            if (base != null && head != null) {
                val pinned = buildJsonObject {
                    put("action", "changed_since")
                    for (field in listOf("owner", "repo")) {
                        (args[field] as? JsonPrimitive)?.takeIf { it.isString }?.let {
                            put(field, it.content.lowercase())
                        }
                    }
                    put("base_sha", base)
                    put("head_sha", head)
                    put("include_patches", args["include_patches"] == JsonPrimitive(true))
                    put("per_page", result?.get("effective_per_page") ?: args["per_page"] ?: JsonPrimitive(100))
                }
                return "$operation:$pinned"
            }
        }
        return operation + dev.chungjungsoo.gptmobile.data.github.GitHubInventoryQuery.arguments(operation, args, result?.get("commit_sha"))
    }
    private fun page(args: JsonObject) = (args["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
    private fun pinnedComparison(operation: String, args: JsonObject) = operation == "github_context" &&
        args["action"] == JsonPrimitive("changed_since") &&
        listOf(args["base"] ?: args["base_sha"], args["commit_sha"]).all {
            (it as? JsonPrimitive)?.content?.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) == true
        }

    private fun liveFirstPage(args: JsonObject) = page(args) == 1 &&
        (args["verify_live"] == JsonPrimitive(true) || args["refresh"] == JsonPrimitive(true))

    // The failure digest bounds a search for one failed job. Its continuation
    // advances that search without publishing a page of workflow jobs.
    private fun numberedRemotePage(operation: String, args: JsonObject): Boolean {
        val action = (args["action"] as? JsonPrimitive)?.content
        // A PR context, commit, or branch dependency is one resource, not a
        // numbered collection. These action names are resolved by nativeArguments.
        if (operation == "github_pr" && action == "pr_context") return false
        if (operation == "github_context" && action in setOf("get_commit_detail", "branch_dependencies")) return false
        return "view_cursor" !in args &&
            "index_cursor" !in args &&
            args["action"] !in setOf(
                JsonPrimitive("workflow_catalog"),
                JsonPrimitive("search_repository"),
                JsonPrimitive("ci_failure_digest"),
                // Log windows are local text excerpts, not remote record collection pages.
                JsonPrimitive("get_job_logs")
            )
    }

    fun expectsNumberedRemotePage(operation: String, args: JsonObject): Boolean =
        numberedRemotePage(operation, args)

    private fun fillingFirstPage(key: String, args: JsonObject): Boolean {
        val stored = traversals[key] ?: return false
        return args["verify_live"] != JsonPrimitive(true) &&
            args["refresh"] != JsonPrimitive(true) &&
            stored.pages.values.all { now() - it.at in 0..30_000_000_000L } &&
            !stored.changed &&
            stored.pages.isNotEmpty() &&
            1 !in stored.pages
    }

    @Synchronized fun completedPage(operation: String, args: JsonObject): Int? {
        if (!numberedRemotePage(operation, args)) return null
        val key = key(operation, args)
        val end = ends[key]
        // A first-page request starts a fresh observation epoch. A live
        // continuation on page 2+ belongs to that same epoch; clearing it
        // would make a complete remote traversal appear to contain one page.
        if (page(args) == 1 && !fillingFirstPage(key, args) && (liveFirstPage(args) || !pinnedComparison(operation, args))) {
            reset(key)
            return null
        }
        if (end == null) return null
        if (now() - end.at !in 0..30_000_000_000L) {
            reset(key)
            return null
        }
        return end.page.takeIf { page(args) > it }
    }

    /** The completion guard and generation ownership must be one atomic dispatch decision. */
    @Synchronized fun preparePage(operation: String, args: JsonObject): Dispatch {
        val completed = completedPage(operation, args)
        return if (completed != null) Dispatch(completedPage = completed, errorCode = completionError(operation, args)) else Dispatch(observation = beginPage(operation, args))
    }

    /** Observe dispatch ownership so a delayed response cannot join a newer live traversal. */
    @Synchronized fun beginPage(operation: String, args: JsonObject): Observation? {
        if (!numberedRemotePage(operation, args)) return null
        val key = key(operation, args)
        val state = traversal(key, liveFirstPage(args))
        val id = ++sequence
        state.pending[id] = Pending(page(args), now())
        while (state.pending.size > GitHubComparisonPage.MAX_COVERAGE_PAGES) state.pending.remove(state.pending.keys.first())
        while (traversals.size > 128) reset(traversals.keys.first())
        return Observation(key, state.generation, id, state.receiptGeneration)
    }

    @Synchronized fun cancelPage(observation: Observation?) {
        if (observation == null) return
        traversals[observation.query]?.takeIf { it.generation == observation.generation }?.pending?.remove(observation.id)
    }

    private fun superseded(args: JsonObject, observation: Observation): Assessment = Assessment(
        buildJsonObject {
            put("reason", "SUPERSEDED_PAGINATION_GENERATION")
            put("complete", false)
            put("has_more", false)
            put("traversal_state", "SUPERSEDED")
            put("message", "A newer live traversal replaced this response. Continue from its retained pages; this older response cannot establish coverage.")
            put(
                "pagination_progress",
                buildJsonObject {
                    put("schema", "github.pagination-progress.v1")
                    put("remote_page", page(args))
                    put("complete", false)
                    put("acceptance_status", "NOT_MEASURED")
                }
            )
        },
        "GITHUB_PAGINATION_INCOMPLETE",
        observation.receiptGeneration
    )

    private fun rows(operation: String, args: JsonObject, result: JsonObject): Map<String, JsonArray> {
        if (operation == "github_pr" && args["action"] == JsonPrimitive("copilot_tasks")) {
            // An empty task list is a valid discovery result. Missing or malformed
            // task data must not borrow coverage from an unrelated collection.
            val tasks = result["tasks"] as? JsonArray ?: return emptyMap()
            val malformed = tasks.any { row ->
                val id = ((row as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                id.isNullOrBlank() || id.length > 256 || id.any(Char::isISOControl)
            }
            if (malformed) return emptyMap()
            return mapOf("tasks" to tasks)
        }
        val upstream = result["upstream_record_ids"] as? JsonArray
        if (!upstream.isNullOrEmpty()) return mapOf("upstream_record_ids" to upstream)
        val direct = result.filter { (name, value) -> name in COLLECTIONS && value is JsonArray }.mapValues { it.value as JsonArray }
        if (direct.isNotEmpty()) return direct
        // Commit status summaries store the actual check rows inside check_details.
        val nested = (result["check_details"] as? JsonObject)?.get("check_runs") as? JsonArray
        return if (nested != null) mapOf("check_runs" to nested) else emptyMap()
    }

    private fun identity(collection: String, row: JsonElement, branchList: Boolean): String {
        val objectRow = row as? JsonObject
        // The same blob SHA can occur at multiple changed filenames. A file
        // collection is identified by path, not by its shared content digest.
        val value = objectRow?.let {
            when {
                branchList && collection == "items" -> (it["name"] as? JsonPrimitive)?.takeIf { name -> name.isString && name.content.isNotBlank() } ?: row
                collection == "files" -> it["filename"] ?: it["path"] ?: it["sha"] ?: it["id"]
                else -> it["id"] ?: it["node_id"] ?: it["sha"] ?: it["number"] ?: it["path"]
            }
        } ?: row
        return collection + ":" + dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(value.toString())
    }

    private fun reportedTotal(operation: String, args: JsonObject, result: JsonObject): Int? {
        val comparisonFiles = if (
            operation == "github_context" &&
            args["action"] == JsonPrimitive("changed_since")
        ) {
            result["files_total"]
        } else {
            null
        }
        val upstream = comparisonFiles ?: result["total_count"] ?: (result["check_details"] as? JsonObject)?.get("total_count")
        return (upstream as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
    }

    /** A PR context is a singleton, but its explicitly requested file page must be real. */
    private fun assessPrContextFilePage(args: JsonObject, result: JsonObject): Assessment {
        if (result["files_included"] != JsonPrimitive(true)) return Assessment(result)
        val pageNumber = page(args)
        val total = (result["files_total"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
        val files = result["files"] as? JsonArray
        val expectedRows = total?.let { (it.toLong() - (pageNumber.toLong() - 1L) * 30L).coerceIn(0L, 30L).toInt() }
        val claimedRows = (result["files_returned"] as? JsonPrimitive)?.content?.toIntOrNull()
        val expectedMore = total?.let { pageNumber.toLong() * 30L < it }
        if (pageNumber > 0 &&
            expectedRows != null &&
            files?.size == expectedRows &&
            claimedRows == expectedRows &&
            result["has_more"] == JsonPrimitive(expectedMore == true)
        ) {
            return Assessment(result)
        }

        val progress = buildJsonObject {
            put("schema", "github.pagination-progress.v1")
            put("remote_page", pageNumber)
            put("remote_pages_observed", JsonArray(listOf(JsonPrimitive(pageNumber))))
            put("upstream_rows_returned", files?.size ?: 0)
            put("unique_records_observed", files?.size ?: 0)
            total?.let { put("reported_total", it) }
            put("contiguous_from_first_page", pageNumber == 1)
            put("complete", false)
            put("acceptance_status", "NOT_MEASURED")
        }
        val missing = files == null || files.isEmpty() && (expectedRows ?: 0) > 0
        return Assessment(
            JsonObject(
                result.filterKeys { it != "files_continuation" } + buildJsonObject {
                    put("reason", if (missing) "MISSING_REMOTE_RECORD_COLLECTION" else "INCOMPLETE_REMOTE_COVERAGE")
                    put("has_more", false)
                    put("upstream_has_more", result["has_more"] ?: JsonPrimitive(false))
                    put("complete", false)
                    put("traversal_state", "INCOMPLETE")
                    put("pagination_progress", progress)
                    put("message", "Requested PR files did not match files_total for this page. Refresh page 1 with verify_live=true and include_files=true at the observed PR head; do not accept incomplete file coverage.")
                }
            ),
            "GITHUB_PAGINATION_INCOMPLETE"
        )
    }

    /** Compare stable record identifiers, including earlier nonadjacent pages and reordered rows. */
    @Synchronized fun assessPage(operation: String, args: JsonObject, result: JsonObject, observation: Observation? = null): Assessment {
        if (operation == "github_pr" && args["action"] == JsonPrimitive("pr_context")) {
            return assessPrContextFilePage(args, result)
        }
        if (!numberedRemotePage(operation, args) || "has_more" !in result) {
            cancelPage(observation)
            return Assessment(result)
        }
        val dispatched = observation?.let { token ->
            traversals[token.query]?.takeIf { state ->
                state.generation == token.generation && state.pending[token.id]?.let { now() - it.at in 0..300_000_000_000L } == true
            }
        }
        if (observation != null && dispatched == null) return superseded(args, observation)
        if (observation != null) dispatched?.pending?.remove(observation.id)
        val collections = rows(operation, args, result)
        if (collections.isEmpty()) {
            // A successful envelope with no record collection cannot establish
            // coverage when the provider advertises records or another page.
            // An explicitly empty terminal result remains valid.
            val reportedTotal = reportedTotal(operation, args, result)
            // A zero-result first page is a complete empty collection. A
            // later zero-result page cannot erase previously observed records.
            if (!(operation == "github_pr" && args["action"] == JsonPrimitive("copilot_tasks")) &&
                reportedTotal == 0 &&
                result["has_more"] == JsonPrimitive(false) &&
                page(args) == 1 &&
                traversals[key(operation, args)]?.pages?.isEmpty() != false
            ) {
                return Assessment(result)
            }
            val number = page(args)
            val progress = buildJsonObject {
                put("schema", "github.pagination-progress.v1")
                put("remote_page", number)
                put("remote_pages_observed", JsonArray(listOf(JsonPrimitive(number))))
                put("upstream_rows_returned", 0)
                put("unique_records_observed", 0)
                put("new_records_on_page", 0)
                put("duplicate_records_on_page", 0)
                reportedTotal?.let { put("reported_total", it) }
                put("contiguous_from_first_page", number == 1)
                put("upstream_changed_during_traversal", false)
                put("complete", false)
                put("acceptance_status", "NOT_MEASURED")
            }
            val withoutContinuations = result.filterKeys {
                it !in setOf("continuation", "continuation_call", "remote_continuations", "next_page")
            }
            val failureBody = buildJsonObject {
                put("reason", "MISSING_REMOTE_RECORD_COLLECTION")
                put("complete", false)
                put("has_more", false)
                put("traversal_state", "INCOMPLETE")
                put("upstream_has_more", result["has_more"] ?: JsonPrimitive(false))
                put("pagination_progress", progress)
                put("message", "Remote coverage is unverified: no record collection was returned. Refresh page 1 with verify_live=true before accepting evidence.")
            }
            return Assessment(JsonObject(withoutContinuations + failureBody), "GITHUB_PAGINATION_INCOMPLETE")
        }
        val key = key(operation, args, result)
        if (observation == null &&
            page(args) == 1 &&
            !fillingFirstPage(key, args) &&
            operation == "github_context" &&
            args["action"] in setOf(JsonPrimitive("changed_since"), JsonPrimitive("list_commits")) &&
            (liveFirstPage(args) || !pinnedComparison(operation, args))
        ) {
            // Page one can resolve a symbolic ref. Restart the traversal under
            // its observed immutable key, which was unavailable before dispatch.
            reset(key)
        }
        if (observation != null && observation.query != key) {
            // A symbolic first page resolves its immutable key only in the response.
            // An explicit refresh replaces that key; ordinary reads may reuse its coverage.
            val existing = traversals[key]
            if (existing != null && existing.generation > observation.generation) return superseded(args, observation)
            val resolved = if (liveFirstPage(args)) requireNotNull(dispatched) else existing ?: requireNotNull(dispatched)
            if (liveFirstPage(args)) reset(key)
            traversals[key] = resolved
            traversals[observation.query] = resolved
        }
        val state = traversal(key, liveFirstPage(args))
        val comparisonLimited = operation == "github_context" && args["action"] == JsonPrimitive("changed_since") && GitHubComparisonPage.exceedsCoverageLimit(result)
        if (state.pages.values.any { now() - it.at !in 0..300_000_000_000L }) {
            state.pages.clear()
            state.total = null
            state.changed = false
            state.inconsistent = false
        }
        state.pending.entries.removeAll { now() - it.value.at !in 0..300_000_000_000L }
        val number = page(args)
        val branchList = operation == "github_context" && args["action"] == JsonPrimitive("list_branches")
        val identities = collections.flatMap { (collection, values) -> values.map { identity(collection, it, branchList) } }.toSet()
        val earlier = state.pages.filterKeys { it < number }.values.flatMapTo(mutableSetOf()) { it.identities }
        val previous = state.pages[number]
        if (previous != null && previous.identities != identities) state.changed = true
        if ((result["pagination_progress"] as? JsonObject)?.get("upstream_changed_during_traversal") == JsonPrimitive(true)) state.changed = true
        val total = reportedTotal(operation, args, result)
        if (state.total != null && total != null && state.total != total) state.changed = true
        if (state.total == null) state.total = total
        state.inconsistent = state.inconsistent || result["pagination_inconsistent"] == JsonPrimitive(true) || result["tree_complete"] == JsonPrimitive(false)
        val upstreamMore = if (comparisonLimited) result["upstream_has_more"] ?: result["has_more"] else result["has_more"]
        state.pages[number] = Page(identities, now(), upstreamMore == JsonPrimitive(false))
        while (traversals.size > 128) reset(traversals.keys.first())
        // Keep memory bounded without silently claiming complete coverage of a truncated traversal.
        if (state.pages.size > GitHubComparisonPage.MAX_COVERAGE_PAGES) {
            state.pages.remove(state.pages.keys.min())
            // An explicit comparison cap already disqualifies coverage. Evicting
            // bounded observations is not evidence that its immutable source moved.
            if (!comparisonLimited) state.changed = true
        }
        val all = state.pages.values.flatMapTo(mutableSetOf()) { it.identities }
        val overlap = identities.count { it in earlier }
        // Offset pages can overlap when branches, pull requests, or check runs move between reads.
        // Even a matching check total cannot establish which rows shared the same observation.
        val commitChecks = operation == "github_context" &&
            args["action"] in setOf(JsonPrimitive("get_commit_status_summary"), JsonPrimitive("checks")) &&
            "check_runs" in collections
        if (branchList || commitChecks || operation == "github_pr" && args["action"] == JsonPrimitive("list_pull_requests")) {
            if (all.size < state.pages.values.sumOf { it.identities.size }) state.changed = true
        }
        val repeated = identities.isNotEmpty() && overlap == identities.size
        val emptyRemotePage = identities.isEmpty() && result["has_more"] == JsonPrimitive(true) && ((result["upstream_returned"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) == 0
        val stalled = repeated || emptyRemotePage
        val terminal = state.pages.filterValues { it.terminal }.keys.minOrNull()
        val boundary = terminal ?: state.pages.keys.maxOrNull() ?: number
        val missing = if (boundary in 1..1000) (1..boundary).filter { it !in state.pages } else emptyList()
        val contiguous = boundary in 1..1000 && missing.isEmpty()
        if (terminal != null && state.pages.any { (page, value) -> page > terminal && value.identities.isNotEmpty() }) state.changed = true
        val consistent = !state.changed && !state.inconsistent && (state.total == null || all.size == state.total)
        val collecting = !comparisonLimited &&
            terminal != null &&
            missing.isNotEmpty() &&
            !state.changed &&
            !state.inconsistent &&
            !stalled &&
            missing.all { page -> state.pending.values.any { it.page == page } }
        val complete = !comparisonLimited && terminal != null && contiguous && consistent && !stalled
        val error = when {
            stalled -> "GITHUB_PAGINATION_STALLED"
            comparisonLimited -> "GITHUB_PAGINATION_INCOMPLETE"
            terminal != null && !complete && !collecting -> "GITHUB_PAGINATION_INCOMPLETE"
            else -> null
        }
        val firstMissing = if (!comparisonLimited && terminal != null && !contiguous && !stalled && !state.changed && !state.inconsistent) {
            missing.firstOrNull()
        } else {
            null
        }
        if (!comparisonLimited && (terminal != null || stalled)) ends[key] = End(terminal ?: number, now(), error ?: if (complete) null else "GITHUB_PAGINATION_INCOMPLETE")
        while (ends.size > 128) ends.remove(ends.keys.first())
        val progress = buildJsonObject {
            put("schema", "github.pagination-progress.v1")
            put("remote_page", number)
            terminal?.let { put("terminal_page", it) }
            put("remote_pages_observed", JsonArray(state.pages.keys.sorted().map(::JsonPrimitive)))
            put("upstream_rows_returned", collections.values.sumOf { it.size })
            put("unique_records_observed", all.size)
            put("new_records_on_page", identities.size - overlap)
            put("duplicate_records_on_page", collections.values.sumOf { it.size } - identities.size + overlap)
            state.total?.let { put("reported_total", it) }
            put("contiguous_from_first_page", contiguous)
            firstMissing?.let { put("first_unobserved_page", it) }
            put("upstream_changed_during_traversal", state.changed)
            if (collecting) put("pending_remote_pages", JsonArray(missing.map(::JsonPrimitive)))
            put("complete", complete)
            put("acceptance_status", "NOT_MEASURED")
        }
        val deduplicated = if (comparisonLimited) {
            result
        } else {
            result.mapValues { (name, value) ->
                if (name !in collections || value !is JsonArray || "upstream_record_ids" in collections) {
                    value
                } else {
                    val seen = earlier.toMutableSet()
                    JsonArray(value.filter { seen.add(identity(name, it, branchList)) })
                }
            }
        }
        val visibleCount = collections.keys.singleOrNull()?.takeIf { it != "upstream_record_ids" }?.let { (deduplicated[it] as? JsonArray)?.size }
        val settled = if (complete) {
            deduplicated.filterKeys { it !in setOf("continuation", "continuation_call", "remote_continuations", "next_page") } + buildJsonObject {
                put("has_more", false)
                put("upstream_has_more", result.getValue("has_more"))
                put("complete", true)
                put("traversal_state", "COMPLETE")
            }
        } else if (collecting) {
            deduplicated + buildJsonObject {
                put("complete", false)
                put("traversal_state", "COLLECTING")
            }
        } else {
            deduplicated
        }
        val output = JsonObject(settled + ("pagination_progress" to progress) + if (visibleCount != null && "returned" in result) mapOf("returned" to JsonPrimitive(visibleCount)) else emptyMap()).let {
            if (comparisonLimited) requireNotNull(GitHubComparisonPage.limitResult(args, it)) else it
        }
        return if (error == null) {
            Assessment(output, generation = state.receiptGeneration)
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
                        put("upstream_has_more", if (comparisonLimited) output.getValue("upstream_has_more") else result["has_more"] ?: JsonPrimitive(false))
                        put("message", if (comparisonLimited) output.getValue("message") else JsonPrimitive("Remote pagination did not establish complete coverage. Retain these observations and deliberately refresh page 1 with verify_live=true; do not count repeated rows as new records or accepted tests."))
                    }
                ),
                error,
                state.receiptGeneration
            )
        }
    }

    /** Compatibility helper for callers which only need repetition detection. */
    @Synchronized fun repeatsRemotePage(operation: String, args: JsonObject, result: JsonObject): Boolean =
        assessPage(operation, args, JsonObject(result + ("has_more" to (result["has_more"] ?: JsonPrimitive(true))))).errorCode == "GITHUB_PAGINATION_STALLED"

    @Synchronized fun stoppedOnRepetition(operation: String, args: JsonObject): Boolean = ends[key(operation, args)]?.errorCode == "GITHUB_PAGINATION_STALLED"

    @Synchronized fun completionError(operation: String, args: JsonObject): String? = ends[key(operation, args)]?.errorCode

    @Synchronized fun observe(operation: String, args: JsonObject, result: JsonObject) {
        if (!numberedRemotePage(operation, args) || result["has_more"] != JsonPrimitive(false)) return
        ends[key(operation, args)] = End(page(args), now())
        while (ends.size > 128) ends.remove(ends.keys.first())
    }

    companion object {
        private val COLLECTIONS = setOf("items", "workflow_runs", "workflows", "jobs", "artifacts", "check_runs", "pull_requests", "commits", "comments", "reviews", "threads", "files", "releases", "tags")
    }
}
