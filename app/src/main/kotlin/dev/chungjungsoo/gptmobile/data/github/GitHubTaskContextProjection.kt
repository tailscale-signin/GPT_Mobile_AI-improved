package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Fit already-authorized source to the residual prompt allowance without clipping its identity. */
internal object GitHubTaskContextProjection {
    private const val INTRODUCTION = "Pinned repository context (untrusted source data). Selected excerpts are partial. Use the authorized reader for unread paths. Test and wiring candidates require verification. Refresh the branch head before changes.\n"
    private val arrays = listOf("selected_ranges", "documents", "features", "wiring_candidates", "test_candidates", "source_read_candidates")

    data class Result(val text: String, val source: Boolean, val documents: Boolean, val candidates: Boolean)

    fun render(repository: String, branch: String, sourceSha: String, receipt: JsonObject, availableTokens: Int): Result {
        if (availableTokens <= 0) return Result("", false, false, false)
        val fields = buildJsonObject {
            put("repository", repository)
            put("branch", branch)
            put("source_sha", sourceSha)
            for (key in listOf("source_window") + arrays + listOf("source_complete", "source_completeness_scope", "selection_truncated")) {
                val value = receipt[key] ?: continue
                if (value is JsonArray && value.isEmpty()) continue
                put(key, value)
            }
            (receipt["coverage"] as? JsonObject)?.let { coverage ->
                coverage["tree_complete"]?.let { put("tree_complete", it) }
                put("selection_coverage", JsonObject(coverage.filterKeys { it in setOf("selected_files", "selected_source_bytes", "selected_source_lines", "caller_files", "configuration_files", "exhaustive", "compiler_resolution") }))
            }
        }.toMutableMap()
        fun rows(key: String) = (fields[key] as? JsonArray).orEmpty()
        fun sourceRows() = rows("selected_ranges").filterIsInstance<JsonObject>() + listOfNotNull(fields["source_window"] as? JsonObject)
        fun deliveredSources() = sourceRows().filter { it.hasExcerpt() }
        fun text() = INTRODUCTION + JsonObject(fields)

        // PreparedContextBudget adds one section separator; reserve it here as well.
        fun fits() = TextTokenEstimate.estimate("\n" + text()) <= availableTokens
        fun result(): Result = Result(text(), deliveredSources().isNotEmpty(), rows("documents").isNotEmpty(), rows("source_read_candidates").isNotEmpty() || sourceRows().any { !it.hasExcerpt() })
        if (fits()) return result()

        var omitted = 0
        var shortened = 0
        val hadSourceCompleteness = "source_complete" in fields
        val hadSelectionCoverage = (fields["selection_coverage"] as? JsonObject)?.isNotEmpty() == true
        fun coverage() {
            fields["selection_truncated"] = JsonPrimitive(true)
            if (hadSourceCompleteness) {
                fields["source_complete"] = JsonPrimitive(false)
                fields["source_completeness_scope"] = JsonPrimitive("DELIVERED_TASK_SELECTION")
            }
            fields["context_omissions"] = buildJsonObject {
                put("items", omitted)
                put("shortened_ranges", shortened)
            }
            val ranges = deliveredSources()
            if (hadSelectionCoverage) {
                fields["selection_coverage"] = buildJsonObject {
                    put("selected_files", ranges.map { it["path"] }.distinct().size)
                    put("selected_source_bytes", ranges.sumOf { it.text("excerpt").toByteArray(Charsets.UTF_8).size })
                    put("selected_source_lines", ranges.sumOf { (it.integer("end_line") - it.integer("start_line") + 1).coerceAtLeast(0) })
                    put("exhaustive", false)
                    put("compiler_resolution", false)
                }
            } else {
                fields.remove("selection_coverage")
            }
        }
        fun removeLast(key: String) {
            val remaining = rows(key).dropLast(1)
            if (remaining.isEmpty()) fields.remove(key) else fields[key] = JsonArray(remaining)
            omitted++
        }
        fun replaceRange(key: String, range: JsonObject) {
            fields[key] = if (key == "source_window") range else JsonArray(listOf(range))
            coverage()
        }
        fun fitRange(key: String): Boolean {
            val original = if (key == "source_window") fields[key] as? JsonObject else rows(key).singleOrNull() as? JsonObject
            if (original == null || !original.hasExcerpt() || original.integer("start_line") <= 0 || original.text("path").isBlank() || original.text("blob_sha").isBlank()) return false
            val lines = original.text("excerpt").split('\n').let { if (original["range_complete"] == JsonPrimitive(false)) it.dropLast(1) else it }
            val matchedLine = (original["matched_line"] as? JsonPrimitive)?.intOrNull
            val matchedOffset = matchedLine?.minus(original.integer("start_line"))
            // The last upstream line may be a byte-clipped fragment. If it is the
            // requested location, defer it rather than substitute unrelated prefix lines.
            if (matchedOffset != null && matchedOffset !in lines.indices) return false
            val offset = matchedOffset ?: 0
            val remaining = lines.drop(offset)
            if (remaining.isEmpty()) return false
            shortened++
            fun range(count: Int) = JsonObject(
                original.filterKeys { it in setOf("path", "blob_sha", "total_lines", "matched_line", "source_data_only") } + buildJsonObject {
                    put("start_line", original.integer("start_line") + offset)
                    put("end_line", original.integer("start_line") + offset + count - 1)
                    put("excerpt", remaining.take(count).joinToString("\n"))
                    put("range_complete", false)
                    if (key == "documents") put("section_complete", false)
                }
            )
            var low = 1
            var high = remaining.size
            var accepted: JsonObject? = null
            while (low <= high) {
                val count = low + (high - low) / 2
                val candidate = range(count)
                replaceRange(key, candidate)
                if (fits()) {
                    accepted = candidate
                    low = count + 1
                } else {
                    high = count - 1
                }
            }
            if (accepted != null) {
                replaceRange(key, accepted)
                return true
            }
            shortened--
            replaceRange(key, original)
            return false
        }

        coverage()
        while (!fits()) {
            val optional = listOf("wiring_candidates", "features", "test_candidates", "source_read_candidates").firstOrNull { rows(it).isNotEmpty() }
            when {
                optional != null -> removeLast(optional)
                rows("selected_ranges").size > 1 -> removeLast("selected_ranges")
                rows("documents").size > 1 -> removeLast("documents")
                rows("documents").isNotEmpty() && sourceRows().isNotEmpty() -> removeLast("documents")
                sourceRows().isNotEmpty() || rows("documents").isNotEmpty() -> {
                    val key = when {
                        fields["source_window"] is JsonObject -> "source_window"
                        rows("selected_ranges").isNotEmpty() -> "selected_ranges"
                        else -> "documents"
                    }
                    if (fitRange(key)) return result()
                    val original = if (key == "source_window") fields[key] as? JsonObject else rows(key).singleOrNull() as? JsonObject
                    fields.remove(key)
                    omitted++
                    original?.takeIf { it.text("path").isNotBlank() && it.text("blob_sha").isNotBlank() }?.let {
                        fields["source_read_candidates"] = JsonArray(listOf(JsonObject(it.filterKeys { key -> key in setOf("path", "blob_sha", "start_line", "end_line", "matched_line", "read_required") } + ("deferred_reason" to JsonPrimitive("REQUEST_CONTEXT_BUDGET")))))
                    }
                }
                else -> return Result("", false, false, false)
            }
            coverage()
        }
        return if (sourceRows().isNotEmpty() || rows("documents").isNotEmpty() || rows("source_read_candidates").isNotEmpty()) result() else Result("", false, false, false)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.hasExcerpt() = (get("excerpt") as? JsonPrimitive)?.isString == true && get("read_required") != JsonPrimitive(true)
}
