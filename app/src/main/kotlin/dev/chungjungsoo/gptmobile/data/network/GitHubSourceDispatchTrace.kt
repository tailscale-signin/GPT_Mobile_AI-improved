package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Compares the invocation's retained GitHub reads with that attempt's final tool messages. */
internal class GitHubSourceDispatchTrace private constructor(private val prepared: List<Prepared>, private val limited: Boolean) {
    private data class Prepared(val callId: String, val range: SerializedToolSourceRanges.Range?) {
        val evidence by lazy { range?.evidence(null) }
    }
    private val byCall = prepared.groupBy { it.callId }

    private val sourceAttempts = linkedMapOf<String, JsonObject>()
    private var omittedSourceAttempts = 0

    @Synchronized
    fun evidence(attemptTrace: (String) -> HttpAttemptTrace?): JsonObject? {
        if (sourceAttempts.isEmpty()) return null
        return buildJsonObject {
            put("attempts_omitted", JsonPrimitive(omittedSourceAttempts))
            put(
                "attempts",
                JsonArray(
                    sourceAttempts.map { (attempt, snapshot) ->
                        val trace = attemptTrace(attempt)
                        val transport = when {
                            trace?.evidence()?.requestBodyCompleted == true -> "REQUEST_BODY_SENT"
                            trace?.provenUnsent == true -> "PROVEN_UNSENT"
                            else -> "TRANSMISSION_UNVERIFIED"
                        }
                        JsonObject(snapshot + mapOf("physical_attempt_id" to JsonPrimitive(attempt), "transport" to JsonPrimitive(transport)))
                    }
                )
            )
        }
    }

    @Synchronized
    fun record(attempt: String, request: JsonObject?) {
        if (sourceAttempts.size >= 4 && attempt !in sourceAttempts) {
            sourceAttempts.remove(sourceAttempts.keys.last())
            omittedSourceAttempts++
        }
        sourceAttempts[attempt] = compare(request)
    }

    fun compare(request: JsonObject?): JsonObject {
        val encountered = mutableSetOf<String>()
        val observations = mutableMapOf<String, MutableList<SerializedToolSourceRanges.Range>>()
        var unidentified = false
        val limitedCalls = mutableSetOf<String>()
        val supported = SerializedToolSourceRanges.visit(request) { id, result ->
            if (id == null) {
                // An ID-less function response cannot be attributed to an individual call.
                unidentified = true
            } else if (id in byCall) {
                encountered += id
                if (SerializedToolSourceRanges.oversized(result)) limitedCalls += id
                val seen = observations.getOrPut(id) { mutableListOf() }
                val ranges = SerializedToolSourceRanges.ranges(result)
                val remaining = SerializedToolSourceRanges.LIMIT - seen.size
                if (ranges.size > remaining) limitedCalls += id
                seen += ranges.take(remaining)
            }
        }
        return buildJsonObject {
            put("scope", "RETAINED_GITHUB_READ_TOOL_OUTPUTS")
            put("claim_attribution", "UNKNOWN")
            put("inspection_limited", limited || limitedCalls.isNotEmpty())
            put(
                "ranges",
                JsonArray(
                    prepared.map { item ->
                        val expected = item.range
                        val matching = if (expected == null) emptyList() else observations[item.callId].orEmpty().filter { wire -> expected.contains(wire) }
                        val state = when {
                            expected == null || !supported -> "UNKNOWN"
                            matching.any { it.offset == expected.offset && it.content.length == expected.content.length } -> "MATCHED"
                            matching.isNotEmpty() -> "MATCHED_FRAGMENTS"
                            item.callId in limitedCalls -> "UNKNOWN"
                            item.callId in encountered -> "NO_MATCHING_SOURCE_RANGE"
                            unidentified -> "UNKNOWN_CALL_ID"
                            else -> "TOOL_OUTPUT_OMITTED"
                        }
                        buildJsonObject {
                            put("call_id_sha256", DiagnosticRecordChunks.sha256(item.callId))
                            put("state", state)
                            item.evidence?.let { put("prepared", it) }
                            if (matching.isNotEmpty()) {
                                put("serialized_fragments", matching.size)
                                put("serialized", JsonArray(matching.take(2).map { it.evidence(null) }))
                            }
                        }
                    }
                )
            )
        }
    }

    private fun SerializedToolSourceRanges.Range.contains(part: SerializedToolSourceRanges.Range): Boolean {
        if (commit != part.commit || blob != part.blob || pathHash != part.pathHash) return false
        val from = part.offset - offset
        if (from !in 0..content.length || part.content.length > content.length - from) return false
        if (!content.regionMatches(from, part.content, 0, part.content.length)) return false
        var start = line.toLong()
        for (index in 0 until from) if (content[index] == '\n') start++
        return start == part.line.toLong()
    }

    companion object {
        private const val MAX_PREPARED_RANGES = 16

        fun capture(exchanges: List<AgentToolExchange>): GitHubSourceDispatchTrace? {
            val items = mutableListOf<Prepared>()
            var limited = false
            for (exchange in exchanges) {
                val reads = exchange.calls.filter { it.name.substringBefore("__") == "github_read" }.mapTo(mutableSetOf()) { it.callId }
                if (reads.isEmpty()) continue
                for (result in exchange.results) {
                    if (result.callId !in reads || result.isError || !result.dispatched) continue
                    if (items.size >= MAX_PREPARED_RANGES) {
                        limited = true
                        break
                    }
                    fun source(content: ToolResultContent?): JsonElement? = when (content) {
                        is ToolResultContent.Json -> content.value
                        is ToolResultContent.Text -> JsonPrimitive(content.text)
                        else -> null
                    }
                    val retained = SerializedToolSourceRanges.ranges(source(result.retainedContent), retainedRead = true)
                    val ranges = retained.ifEmpty { SerializedToolSourceRanges.ranges(source(result.content)) }
                    if (ranges.isEmpty()) items += Prepared(result.callId, null)
                    val remaining = MAX_PREPARED_RANGES - items.size
                    if (ranges.size > remaining) limited = true
                    ranges.take(remaining).forEach { items += Prepared(result.callId, it) }
                }
            }
            return items.takeIf { it.isNotEmpty() }?.let { GitHubSourceDispatchTrace(it, limited) }
        }
    }
}
