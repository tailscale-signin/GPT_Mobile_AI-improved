package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Transport provenance is independent of response validation and task acceptance. */
internal object GitHubEvidenceOrigin {
    fun classify(dispatched: Boolean, shared: Boolean, execution: JsonObject?): String {
        fun count(key: String) = (execution?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        return when {
            shared -> "CACHED"
            count("read_requests") > 0 || count("mutation_requests") > 0 -> "LIVE"
            count("cached_read_observations") > 0 -> "CACHED"
            execution?.get("phase") == JsonPrimitive("LOCAL_OR_REPLAYED") -> "LOCAL"
            dispatched -> "LIVE"
            else -> "NOT_TESTED"
        }
    }

    fun of(receipt: JsonObject): String = (receipt["evidence_origin"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it in setOf("LIVE", "CACHED", "LOCAL", "MOCKED", "NOT_TESTED") }
        ?: classify(receipt["dispatched"] == JsonPrimitive(true), receipt["shared_result"] == JsonPrimitive(true), receipt["execution"] as? JsonObject)
}
