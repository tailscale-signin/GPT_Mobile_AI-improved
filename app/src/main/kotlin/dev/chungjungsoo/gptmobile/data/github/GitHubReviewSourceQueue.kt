package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Bounded, restart-safe source waves, never a claim that every changed file was reviewed. */
internal object GitHubReviewSourceQueue {
    const val MAX_WINDOWS = 8
    private const val MAX_LOCAL_PAGES = 4
    private const val WINDOW_LINES = 160
    private val source = Regex("(?i)\\.(?:kt|kts|java|py|js|jsx|ts|tsx|go|rs|c|cc|cpp|h|swift|m|mm|rb|php|sh|sql)$")
    private val test = Regex("(?i)(?:^|/)(?:tests?|androidTest|testFixtures)(?:/|$)|(?:Test|Tests|Spec)\\.[a-z]+$|(?:^|/)test_[^/]+\\.[a-z]+$")
    private val hunk = Regex("(?m)^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    data class Work(val recipes: List<JsonObject>, val checkpoint: JsonObject)
    private data class Window(val revision: String, val path: String, val start: Int)

    fun plan(task: String, comparison: Map<String, JsonObject>, receipts: Map<String, JsonObject>, base: String, head: String, repository: String, binding: String?): Work {
        val files = comparison.values.flatMap { (data(it)["files"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
            .filter { validPath(it.text("filename")) }.distinctBy { it.text("filename") }
        val ranked = files.sortedWith(
            compareByDescending<JsonObject> { row ->
                val path = row.text("filename")
                (if (task.contains(path) || task.contains(path.substringAfterLast('/'))) 16 else 0) +
                    (if (source.containsMatchIn(path)) 4 else 0) + (if (test.containsMatchIn(path)) 0 else 2)
            }.thenBy { it.text("filename") }
        )
        // Keep one relevant test in the seed without discarding task-named source.
        val ordered = (ranked.take(2) + listOfNotNull(ranked.firstOrNull { test.containsMatchIn(it.text("filename")) }) + ranked).distinctBy { it.text("filename") }
        val windows = ordered.flatMap { row ->
            val match = hunk.find(row.text("patch"))
            fun start(group: Int) = ((match?.groupValues?.get(group)?.toIntOrNull() ?: 1) - 20).coerceAtLeast(1)
            buildList {
                if (row.text("status") != "removed") add(Window(head, row.text("filename"), start(2)))
                if (row.text("status") != "added") {
                    val previous = row.text("previous_filename").takeIf(::validPath) ?: row.text("filename")
                    add(Window(base, previous, start(1)))
                }
            }
        }.distinct()
        val roots = GitHubTestChecklist.paginationReceipts(receipts) { row ->
            val args = row["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            row.text("tool").githubOperationFamily() == "github_read" &&
                row.text("repository") == repository &&
                (binding == null || row.text("binding") == binding) &&
                row["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                args.text("commit_sha") in setOf(base, head) &&
                (args["ranges"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { range -> windows.any { it.revision == args.text("commit_sha") && requestedWindow(range, it) } }
        }
        fun attempted(window: Window) = roots.values.any { row ->
            val args = row["arguments"] as? JsonObject ?: return@any false
            args.text("commit_sha") == window.revision &&
                (args["ranges"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { requestedWindow(it, window) }
        }
        val unattempted = windows.filterNot(::attempted)
        // Advance past the initial seed while retaining the same task, revision pair
        // and caller-owned budget. A failed window stays a separate unresolved item.
        val pending = unattempted.take(MAX_WINDOWS)
        val selected = (windows.take(MAX_WINDOWS).filter(::attempted) + pending).takeLast(MAX_WINDOWS)
        val localPages = roots.values.count { (it["arguments"] as? JsonObject)?.containsKey("view_cursor") == true }
        val continuations = if (localPages < MAX_LOCAL_PAGES) {
            GitHubTestChecklist.pendingPages(roots).filter { recipe -> (recipe["arguments"] as? JsonObject)?.keys == setOf("view_cursor") }
        } else {
            emptyList()
        }
        val recipes = continuations + pending.groupBy { it.revision }.map { (revision, ranges) ->
            buildJsonObject {
                put("tool", "github_read")
                put(
                    "arguments",
                    buildJsonObject {
                        put("commit_sha", revision)
                        put(
                            "ranges",
                            JsonArray(
                                ranges.take(4).map { window ->
                                    buildJsonObject {
                                        put("path", window.path)
                                        put("start_line", window.start)
                                        put("max_lines", WINDOW_LINES)
                                    }
                                }
                            )
                        )
                    }
                )
            }
        }
        return Work(
            recipes,
            buildJsonObject {
                put("policy", "BOUNDED_CHANGED_SOURCE_WAVES")
                put("maximum_windows", MAX_WINDOWS)
                put("selected_windows", selected.size)
                put("total_windows", windows.size)
                put("remaining_windows", unattempted.size)
                put("attempted_windows", windows.count(::attempted))
                put("pending_windows", pending.size)
                put("selected_paths", JsonArray(selected.map { it.path }.distinct().filter { it.length <= 384 }.map(::JsonPrimitive)))
                put("selected_path_preview_complete", selected.all { it.path.length <= 384 })
                put("issued_local_pages_pending", GitHubTestChecklist.pendingPages(roots).count { (it["arguments"] as? JsonObject)?.containsKey("view_cursor") == true })
                put("automatic_local_page_limit_reached", localPages >= MAX_LOCAL_PAGES)
                put("scope", "Next bounded changed-source wave within the accepted task budget. Attempted windows, source observations and correctness review are separate; failed or partial reads remain unverified.")
            }
        )
    }

    /** A prior header or partial-offset read does not attempt the selected hunk window. */
    private fun requestedWindow(range: JsonObject, window: Window): Boolean {
        if (range.text("path") != window.path) return false
        fun number(key: String, default: Int): Int? = if (key in range) (range[key] as? JsonPrimitive)?.intOrNull else default
        val start = number("start_line", 1)?.takeIf { it in 1..window.start } ?: return false
        if (number("start_offset", 0) != 0) return false
        val end = if ("end_line" in range) {
            if ("max_lines" in range) return false
            when (val supplied = number("end_line", -1) ?: return false) {
                -1 -> start.toLong() + 1999
                else -> supplied.toLong().takeIf { it >= start && it - start < 2000 } ?: return false
            }
        } else {
            val count = number("max_lines", 300)?.takeIf { it in 1..2000 } ?: return false
            start.toLong() + count - 1
        }
        return end >= window.start.toLong() + WINDOW_LINES - 1
    }

    private fun validPath(path: String) = path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && path.split('/').none { it in setOf(".", "..") } && path.none(Char::isISOControl)
    private fun data(row: JsonObject) = GitHubSemanticSummary.data(row["result"] as? JsonObject ?: JsonObject(emptyMap()))
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
