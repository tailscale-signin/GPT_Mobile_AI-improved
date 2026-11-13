package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Review projection only: the original, independently authorized receipts stay in the checkpoint. */
internal object GitHubReviewPayloads {
    fun canonicalize(receipts: List<String>): List<String> {
        data class Entry(val original: String, val receipt: JsonObject?, val result: JsonElement?)
        val groups = linkedMapOf<String, MutableList<Entry>>()
        receipts.forEachIndexed { index, original ->
            val receipt = runCatching { Json.parseToJsonElement(original) as? JsonObject }.getOrNull()
            val result = receipt?.get("result")?.let(::canonical)
            // Compare the actual canonical bytes, never a caller-supplied hash.
            // Two different responses bearing the same claimed source hash must
            // still be reviewed separately.
            val key = result?.let { "result:$it" } ?: "opaque:$index"
            groups.getOrPut(key) { mutableListOf() } += Entry(original, receipt, result)
        }
        return groups.values.map { entries ->
            if (entries.size == 1) return@map entries.single().original
            buildJsonObject {
                put("schema", "github.review.shared_result.v1")
                put("result", requireNotNull(entries.first().result))
                put("result_reference_scope", "Identical content only; each receipt retains its own origin, dispatch, freshness and outcome. Reuse is not an additional live observation or successful operation.")
                put("receipts", JsonArray(entries.map { JsonObject(requireNotNull(it.receipt) - "result") }))
            }.toString()
        }
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
