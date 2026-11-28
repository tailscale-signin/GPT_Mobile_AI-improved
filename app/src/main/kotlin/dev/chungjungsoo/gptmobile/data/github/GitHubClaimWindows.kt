package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The complete plan is durable; each independently retained extraction window stays bounded. */
internal object GitHubClaimWindows {
    private const val SCHEMA = "github.claim-extraction.v2"
    private const val WINDOW_SCHEMA = "github.claim-extraction-window.v1"
    private val bindingFields = setOf("binding", "context", "plan", "authorization")

    suspend fun retain(
        metadata: JsonObject,
        batches: List<JsonObject?>,
        requirementIds: List<Set<String>>,
        save: suspend (String, JsonObject) -> Unit
    ): JsonObject {
        require(batches.size == requirementIds.size)
        require(requirementIds.all { it.size in 1..GitHubClaimBatches.REQUIREMENTS_PER_BATCH })
        require(requirementIds.flatten().distinct().size == requirementIds.sumOf { it.size })
        val next = batches.indexOfFirst { it == null }.takeIf { it >= 0 } ?: batches.size
        val common = JsonObject(
            metadata + buildJsonObject {
                put("batch_requirement_ids", JsonArray(requirementIds.map { JsonArray(it.sorted().map(::JsonPrimitive)) }))
                put("total_batches", batches.size)
                put("completed_batches", batches.count { it != null })
                put("next_batch", next + 1)
                put("window_size", GitHubClaimBatches.MAX_BATCHES)
            }
        )
        // Existing bounded checkpoints remain readable without a migration or extra reads.
        if (batches.size <= GitHubClaimBatches.MAX_BATCHES) {
            return JsonObject(common + mapOf("schema" to JsonPrimitive("github.claim-extraction.v1"), "batches" to JsonArray(batches.map { it ?: JsonNull })))
        }
        val windows = batches.indices.chunked(GitHubClaimBatches.MAX_BATCHES).mapIndexed { ordinal, indexes ->
            val window = JsonObject(
                metadata.filterKeys { it in bindingFields } + buildJsonObject {
                    put("schema", WINDOW_SCHEMA)
                    put("window", ordinal + 1)
                    put("first_batch", indexes.first() + 1)
                    put("batches", JsonArray(indexes.map { batches[it] ?: JsonNull }))
                    put("batch_requirement_ids", JsonArray(indexes.map { JsonArray(requirementIds[it].sorted().map(::JsonPrimitive)) }))
                }
            )
            val digest = GitHubEvidenceBook.digest(window.toString())
            val id = "claim_extraction_window_" + digest.take(24)
            // Immutable window records precede the mutable manifest. An interrupted save
            // leaves the old cursor and all of its referenced evidence intact.
            save(id, window)
            buildJsonObject {
                put("id", id)
                put("sha256", digest)
                put("first_batch", indexes.first() + 1)
                put("batch_count", indexes.size)
            }
        }
        return JsonObject(common + mapOf("schema" to JsonPrimitive(SCHEMA), "windows" to JsonArray(windows)))
    }

    suspend fun restore(saved: JsonObject, load: suspend (String) -> JsonObject?): JsonObject? {
        if (saved["schema"] == JsonPrimitive("github.claim-extraction.v1")) return saved
        if (saved["schema"] != JsonPrimitive(SCHEMA)) return null
        val requirements = saved["batch_requirement_ids"] as? JsonArray ?: return null
        val total = (saved["total_batches"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        if (total <= GitHubClaimBatches.MAX_BATCHES || total != requirements.size) return null
        val references = saved["windows"] as? JsonArray ?: return null
        if (references.size != (total + GitHubClaimBatches.MAX_BATCHES - 1) / GitHubClaimBatches.MAX_BATCHES) return null
        val batches = mutableListOf<kotlinx.serialization.json.JsonElement>()
        for ((ordinal, value) in references.withIndex()) {
            val reference = value as? JsonObject ?: return null
            val digest = (reference["sha256"] as? JsonPrimitive)?.takeIf { it.isString && it.content.matches(Regex("[a-f0-9]{64}")) }?.content ?: return null
            val id = "claim_extraction_window_" + digest.take(24)
            if (reference["id"] != JsonPrimitive(id)) return null
            val window = load(id) ?: return null
            if (GitHubEvidenceBook.digest(window.toString()) != digest ||
                window["schema"] != JsonPrimitive(WINDOW_SCHEMA) ||
                bindingFields.any { window[it] != saved[it] } ||
                window["window"] != JsonPrimitive(ordinal + 1)
            ) {
                return null
            }
            val first = batches.size
            val count = minOf(GitHubClaimBatches.MAX_BATCHES, total - first)
            val values = window["batches"] as? JsonArray ?: return null
            if (window["first_batch"] != JsonPrimitive(first + 1) ||
                reference["first_batch"] != JsonPrimitive(first + 1) ||
                reference["batch_count"] != JsonPrimitive(count) ||
                values.size != count ||
                values.any { it != JsonNull && it !is JsonObject } ||
                window["batch_requirement_ids"] != JsonArray(requirements.subList(first, first + count))
            ) {
                return null
            }
            batches.addAll(values)
        }
        val next = batches.indexOfFirst { it == JsonNull }.takeIf { it >= 0 } ?: batches.size
        if (saved["next_batch"] != JsonPrimitive(next + 1) || saved["completed_batches"] != JsonPrimitive(batches.count { it is JsonObject })) return null
        return JsonObject(saved + ("batches" to JsonArray(batches)))
    }
}
