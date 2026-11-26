package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Joins verified excerpt bytes only to observations of the same tool call in the current run. */
internal object GitHubClaimSourceDelivery {
    const val INVOCATIONS = 16
    private val identity = listOf("commit_sha", "blob_sha", "path_sha256")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.rows(key: String) = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private data class Observation(val id: String, val transport: String, val ranges: List<JsonObject>, val limited: Boolean)

    suspend fun attach(rendered: Pair<String, JsonObject>, load: suspend () -> List<String>): Pair<String, JsonObject> {
        val claims = rendered.second["claims"] as? JsonArray ?: return rendered
        fun sourceBinding(claim: JsonObject?): JsonObject? = if (claim?.get("status") == JsonPrimitive("VERIFIED") && claim["kind"] == JsonPrimitive("excerpt")) {
            claim["source_binding"] as? JsonObject
        } else {
            null
        }
        if (claims.none { sourceBinding(it as? JsonObject) != null }) return rendered
        val records = try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val byCall = mutableMapOf<String, MutableList<Observation>>()
        val seenInvocations = mutableSetOf<String>()
        var inspected = 0
        var limited = false
        for (record in records) {
            val root = runCatching { Json.parseToJsonElement(record) as? JsonObject }.getOrNull()
            val trace = root?.get("github_source_delivery") as? JsonObject ?: continue
            if (root.text("invocation_id")?.let { !seenInvocations.add(it) } == true) continue
            if (inspected++ >= INVOCATIONS) {
                limited = true
                break
            }
            if ((trace.number("attempts_omitted") ?: 0) > 0) limited = true
            for (attempt in trace.rows("attempts")) {
                val id = attempt.text("physical_attempt_id") ?: continue
                val transport = attempt.text("transport") ?: continue
                val bounded = attempt["inspection_limited"] == JsonPrimitive(true)
                for ((call, ranges) in attempt.rows("ranges").groupBy { it.text("call_id_sha256") }) {
                    if (call != null) byCall.getOrPut(call) { mutableListOf() } += Observation(id, transport, ranges, bounded)
                }
            }
        }
        val lines = mutableListOf<String>()
        val updated = claims.map { element ->
            val claim = element as? JsonObject ?: return@map element
            val source = sourceBinding(claim) ?: return@map claim
            val call = source.text("call_id_sha256") ?: return@map claim
            val attempts = byCall[call].orEmpty().map { attempt ->
                buildJsonObject {
                    put("physical_attempt_id", attempt.id)
                    put("transport", attempt.transport)
                    put("serialized_coverage", coverage(source, attempt))
                }
            }
            val summary = attempts.groupingBy {
                when (it.text("transport")) {
                    "PROVEN_UNSENT" -> "confirmed unsent"
                    "REQUEST_BODY_SENT" -> when (it.text("serialized_coverage")) {
                        "COMPLETE" -> "full excerpt sent"
                        "PARTIAL" -> "part of excerpt confirmed sent"
                        "OMITTED" -> "tool output omitted from sent request"
                        "NO_MATCH" -> "no matching excerpt in sent tool output"
                        else -> "sent with unknown excerpt coverage"
                    }
                    else -> "transmission unverified"
                }
            }.eachCount().entries.joinToString(", ") { (label, count) -> "$label ($count)" }
            val label = "${call.take(16)} · lines ${source.text("start_line")}–${source.text("end_line")}"
            lines += "- `$label` · " + summary.ifEmpty { "source delivery unknown" }
            val evidence = buildJsonObject {
                put("scope", "MEASURED_ATTEMPTS_IN_THIS_RUN")
                put("attempts", JsonArray(attempts.take(8)))
                put("additional_matching_attempts", (attempts.size - 8).coerceAtLeast(0))
                put("history_limited", limited)
            }
            JsonObject(claim + ("source_delivery" to evidence))
        }
        if (lines.isEmpty()) return rendered
        val footer = "\n\n**Source delivery by excerpt**\n" + lines.joinToString("\n") +
            if (limited) "\nAdditional request history was outside the inspection limit." else ""
        return rendered.first + footer to JsonObject(rendered.second + ("claims" to JsonArray(updated)))
    }

    private fun coverage(source: JsonObject, attempt: Observation): String {
        val start = source.number("offset_utf16") ?: return "UNKNOWN"
        val length = source.number("length_utf16") ?: return "UNKNOWN"
        if (start < 0 || length <= 0 || length > Long.MAX_VALUE - start) return "UNKNOWN"
        val end = start + length
        val spans = mutableListOf<Pair<Long, Long>>()
        var uncertain = attempt.limited
        var matchingRow = false
        for (row in attempt.ranges) {
            val prepared = row["prepared"] as? JsonObject ?: continue
            if (identity.any { source[it] == null || source[it] != prepared[it] } ||
                source["source_row_sha256"] != prepared["excerpt_sha256"] ||
                source["source_row_start_line"] != prepared["start_line"] ||
                source["source_row_offset_utf16"] != prepared["offset_utf16"]
            ) {
                continue
            }
            matchingRow = true
            val fragments = row.rows("serialized")
            if ((row.number("serialized_fragments") ?: 0) > fragments.size || row.text("state") in setOf("UNKNOWN", "UNKNOWN_CALL_ID")) uncertain = true
            for (fragment in fragments) {
                if (identity.any { source[it] != fragment[it] }) continue
                val from = fragment.number("offset_utf16") ?: continue
                val size = fragment.number("length_utf16") ?: continue
                val first = fragment.number("start_line") ?: continue
                val last = fragment.number("end_line") ?: continue
                if (from < 0 || size <= 0 || size > Long.MAX_VALUE - from || first > (source.number("end_line") ?: continue) || last < (source.number("start_line") ?: continue)) continue
                val overlapStart = maxOf(start, from)
                val overlapEnd = minOf(end, from + size)
                if (overlapStart < overlapEnd) spans += overlapStart to overlapEnd
            }
        }
        var through = start
        for ((from, to) in spans.sortedBy { it.first }) {
            if (from > through) break
            through = maxOf(through, to)
        }
        return when {
            through == end -> "COMPLETE"
            spans.isNotEmpty() -> "PARTIAL"
            matchingRow && attempt.ranges.all { it.text("state") == "TOOL_OUTPUT_OMITTED" } -> "OMITTED"
            uncertain || !matchingRow -> "UNKNOWN"
            else -> "NO_MATCH"
        }
    }
}
