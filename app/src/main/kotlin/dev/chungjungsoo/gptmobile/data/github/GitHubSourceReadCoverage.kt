package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Coverage records captured source ranges; it never certifies that saved output pages were consumed. */
internal object GitHubSourceReadCoverage {
    private const val MAX_INTERVALS = 128

    fun merge(previous: JsonObject?, identity: JsonObject, evidenceId: String, items: List<JsonObject>): JsonObject {
        val total = (identity["total_lines"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: 0
        val prior = previous?.takeIf { old -> identity.all { (key, value) -> old[key] == value } }
        fun interval(item: JsonObject): IntRange? {
            val start = (item["start_line"] as? JsonPrimitive)?.intOrNull ?: return null
            val end = (item["end_line"] as? JsonPrimitive)?.intOrNull ?: return null
            return (start..end).takeIf { start >= 1 && end in start..total }
        }
        val before = (prior?.get("captured_ranges") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull(::interval)
        val current = items.filter { it["range_complete"] == JsonPrimitive(true) }.mapNotNull(::interval)
        val union = mutableListOf<IntRange>()
        for (range in (before + current).sortedBy { it.first }) {
            val last = union.lastOrNull()
            if (last != null && range.first.toLong() <= last.last.toLong() + 1) {
                union[union.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                union += range
            }
        }
        val retained = union.take(MAX_INTERVALS)
        val missing = mutableListOf<IntRange>()
        var next = 1
        for (range in retained) {
            if (next < range.first) missing += next until range.first
            next = range.last + 1
        }
        if (next <= total) missing += next..total
        val emptyCaptured = total == 0 && (prior?.get("complete_file") == JsonPrimitive(true) || items.any { it["complete_file"] == JsonPrimitive(true) })
        val complete = if (total == 0) emptyCaptured else missing.isEmpty()
        val alreadyRead = if (total == 0) {
            prior?.get("complete_file") == JsonPrimitive(true)
        } else {
            items.isNotEmpty() && items.all { item -> interval(item)?.let { requested -> before.any { it.first <= requested.first && it.last >= requested.last } } == true }
        }
        fun json(ranges: List<IntRange>) = JsonArray(
            ranges.map { range ->
                buildJsonObject {
                    put("start_line", range.first)
                    put("end_line", range.last)
                }
            }
        )
        val evidence = (prior?.get("evidence_ids") as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().filter { it.isString }.takeLast(127)
        return JsonObject(
            identity + buildJsonObject {
                put("schema", "github.source-coverage.v1")
                put("captured_ranges", json(retained))
                put("unread_ranges", json(missing))
                put("captured_lines", retained.sumOf { it.last.toLong() - it.first + 1 })
                put("complete_file", complete)
                put("already_read", alreadyRead)
                put("read_required", !complete)
                put("coverage_bounded", union.size > MAX_INTERVALS || prior?.get("coverage_bounded") == JsonPrimitive(true))
                put("evidence_ids", JsonArray((evidence + JsonPrimitive(evidenceId)).distinct()))
                put("coverage_scope", "CAPTURED_COMPLETE_RANGES_AT_IMMUTABLE_BLOB")
                put("output_pages_verified", false)
                put("next_action", if (complete) "Reuse the captured source evidence; finish any pending saved-view pages before analysis. No additional source range is needed for this file." else "Read only the unread ranges, or finish the exact byte continuation for an incomplete range. Captured source does not certify consumed saved-view pages.")
            }
        )
    }
}
