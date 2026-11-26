package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Bounded evidence grouping, with hypotheses kept distinct from confirmed root causes. */
internal object GitHubCiDiagnosis {
    private val sourceBoundary = "[\\p{L}\\p{N}_./+~-]"
    private fun fullPath(path: String) = Regex("(?<!$sourceBoundary)${Regex.escape(path)}(?!$sourceBoundary)")
    private fun changedPathMatches(text: String, paths: Set<String>, repository: String): Pair<List<String>, String> {
        val repo = repository.substringAfterLast('/')
        var normalized = text.replace('\\', '/')
        if (repo.matches(Regex("[A-Za-z0-9_.-]+"))) {
            val escaped = Regex.escape(repo)
            normalized = normalized.replace(Regex("(?:file:///|/)(?:home/runner/work|Users/runner/work)/$escaped/$escaped/|[A-Za-z]:/a/$escaped/$escaped/"), "./")
        }
        val exact = paths.filter { path ->
            fullPath("./$path").containsMatchIn(normalized) || '/' in path && fullPath(path).containsMatchIn(normalized)
        }
        if (exact.isNotEmpty()) return exact.sorted() to "EXACT_REPOSITORY_PATH_IN_LOG"
        val names = paths.groupBy { it.substringAfterLast('/') }.filterKeys { fullPath(it).containsMatchIn(normalized) }
        val reason = when {
            names.values.any { it.size > 1 } -> "AMBIGUOUS_BASENAME"
            names.isNotEmpty() -> "BASENAME_ONLY"
            else -> "UNLOCATED"
        }
        return emptyList<String>() to reason
    }

    fun group(excerpts: List<JsonObject>, changedPaths: Set<String>, identity: JsonObject = JsonObject(emptyMap())): JsonArray {
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val symptoms = excerpts.filter { Regex("(?i)error|failed|failure|exception|fatal|timed? out|no space|connection reset").containsMatchIn(it.text("text")) }
        return JsonArray(
            symptoms.groupBy { row ->
                val text = row.text("text")
                when {
                    Regex("(?i)no space left|out of memory|runner.*(?:offline|lost)|connection reset|could not resolve host|rate limit|service unavailable").containsMatchIn(text) -> "INFRASTRUCTURE"
                    Regex("(?i)assertion|test.*fail|compile|unresolved reference|syntax error|type mismatch|lint").containsMatchIn(text) -> "CODE_OR_TEST"
                    Regex("(?i)unauthori[sz]ed|permission denied|authentication failed|forbidden").containsMatchIn(text) -> "ACCESS_OR_CONFIGURATION"
                    else -> "UNKNOWN"
                } to text.replace(Regex("\\b[0-9a-f]{8,40}\\b|\\b\\d+\\b"), "#").take(240)
            }.entries.take(30).map { (key, rows) ->
                val evidenceText = rows.joinToString("\n") { it.text("text") }
                val (related, location) = changedPathMatches(evidenceText, changedPaths, identity.text("repository"))
                buildJsonObject {
                    put("category", key.first)
                    put("source", "JOB_LOG")
                    put("location_status", location)
                    identity.forEach { (field, value) -> put(field, value) }
                    put("confidence", "LOG_PATTERN_HYPOTHESIS_NOT_CONFIRMED_ROOT_CAUSE")
                    put("occurrences_in_retained_excerpt", rows.size)
                    put("log_lines", JsonArray(rows.mapNotNull { it["line"] }))
                    put("excerpt", rows.first().text("text").take(1200))
                    put("matching_changed_paths", JsonArray(related.map(::JsonPrimitive)))
                    put(
                        "next_observation",
                        when (key.first) {
                            "INFRASTRUCTURE" -> "Compare the failing setup step with another attempt on the same revision. A repeatable compiler or assertion failure after setup points to the change."
                            "CODE_OR_TEST" -> "Run the first failing target on the head and base with the same dependencies. A failure on both revisions weakens attribution to the change."
                            "ACCESS_OR_CONFIGURATION" -> "Check the effective scope and target for the recorded attempt. A successful equivalent request with the same identity weakens the access hypothesis."
                            else -> "Inspect the first failed step and the preceding log lines to distinguish setup, compilation and test execution."
                        }
                    )
                    put(
                        "repair",
                        when (key.first) {
                            "INFRASTRUCTURE" -> "Check runner capacity and the named dependency/service; retry only after the condition clears."
                            "CODE_OR_TEST" -> "Inspect the cited failure and matching changed files; repair the cause and rerun the affected tests at the new SHA."
                            "ACCESS_OR_CONFIGURATION" -> "Revalidate the operation's account, installation and repository permissions before retrying."
                            else -> "Expand the cited log range and inspect the failed step before selecting a repair."
                        }
                    )
                }
            }
        )
    }

    fun repositoryPath(value: String): Boolean = value.isNotBlank() &&
        value.length <= 1024 &&
        value.none { it.isISOControl() || it == '\\' } &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun annotations(rows: JsonArray, changed: Set<String>, identity: JsonObject, checkId: Long, checkSha: String?, redact: (String) -> String): List<JsonObject> =
        rows.filterIsInstance<JsonObject>().filter { it["annotation_level"] == JsonPrimitive("failure") }.take(30).map { row ->
            val path = (row["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(::repositoryPath)
            val start = (row["start_line"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            val end = (row["end_line"] as? JsonPrimitive)?.intOrNull?.takeIf { start != null && it >= start }
            buildJsonObject {
                identity.forEach { (field, value) -> put(field, value) }
                put("source", "CHECK_RUN_ANNOTATION")
                put("check_run_id", checkId)
                checkSha?.let { put("check_head_sha", it) }
                put("confidence", "REPORTED_FAILURE_LOCATION")
                put("location_status", if (path != null) "ANNOTATED_REPOSITORY_PATH" else "UNLOCATED")
                if (path != null) {
                    put("source_path", path)
                    start?.let { put("start_line", it) }
                    end?.let { put("end_line", it) }
                }
                put("matching_changed_paths", JsonArray(listOfNotNull(path?.takeIf(changed::contains)).map(::JsonPrimitive)))
                put("excerpt", redact((row["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()).take(500))
            }
        }
}
