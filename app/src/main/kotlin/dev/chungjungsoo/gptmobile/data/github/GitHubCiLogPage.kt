package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded excerpts with an exact continuation, including a line longer than one page. */
internal object GitHubCiLogPage {
    fun render(log: GitHubCiLogReader.Log, args: JsonObject, byteBudget: Int = 24 * 1024): JsonObject {
        fun number(key: String, default: Int) = (args[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default
        val start = number("start_line", 1)
        val end = number("end_line", minOf(Int.MAX_VALUE.toLong(), start.toLong() + 199).toInt())
        val offset = number("start_offset", 0)
        require(start > 0 && end >= start && end.toLong() - start < 2000 && offset >= 0) { "Use positive ordered log lines (at most 2000), and a nonnegative start_offset." }
        require(byteBudget >= 4)
        val lines = log.text.split('\n')
        require(offset == 0 || start <= lines.size && offset <= lines[start - 1].length) { "Log offset is outside the requested line." }
        var line = start
        var nextOffset = offset
        var remaining = byteBudget
        val rows = mutableListOf<JsonObject>()
        while (line <= minOf(end, lines.size)) {
            val source = lines[line - 1]
            require(nextOffset == 0 || nextOffset == source.length || !source[nextOffset].isLowSurrogate()) { "Log offset splits a Unicode character." }
            val text = GitHubRepositorySession.utf8Prefix(source.substring(nextOffset), remaining)
            if (text.isEmpty() && nextOffset < source.length) break
            rows += buildJsonObject {
                put("line", line)
                put("start_offset", nextOffset)
                put("text", text)
            }
            remaining -= text.encodeToByteArray().size + 1
            nextOffset += text.length
            if (nextOffset < source.length) break
            line++
            nextOffset = 0
            if (remaining < 4) break
        }
        val hasMore = line <= lines.size && (line <= end || "end_line" !in args)
        return buildJsonObject {
            put("status", if (rows.isEmpty()) "empty_range" else "available")
            args["job_id"]?.let { put("job_id", it) }
            args["run_id"]?.let { put("run_id", it) }
            put("log_bytes", log.bytes)
            put("log_complete", log.complete)
            put("truncated", !log.complete)
            put("total_lines", lines.size)
            put("excerpts", JsonArray(rows))
            put("has_more", hasMore)
            if (hasMore) {
                put(
                    "continuation",
                    buildJsonObject {
                        listOf("owner", "repo", "job_id", "run_id", "expected_head_sha", "end_line").forEach { key -> args[key]?.let { put(key, it) } }
                        put("action", "get_job_logs")
                        put("start_line", line)
                        put("start_offset", nextOffset)
                    }
                )
            }
            if (!log.complete) put("limit_note", "The source log exceeds the 4 MiB ingestion limit. Continuations cover captured bytes only; open the job on GitHub for the complete log.")
        }
    }
}
