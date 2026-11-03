package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reservations, interim observations and final counts are distinct; absent totals stay absent. */
internal fun invocationUsageEvidence(invocation: JsonObject): JsonObject = buildJsonObject {
    put("invocation_id", invocation["id"] ?: JsonNull)
    for (dimension in listOf("Input", "Output")) {
        val source = (invocation[dimension.lowercase() + "TokenSource"] as? JsonPrimitive)?.content.orEmpty()
        val observed = invocation["observed${dimension}Tokens"] ?: JsonNull
        val accounted = invocation[dimension.lowercase() + "Tokens"] ?: JsonNull
        put(dimension.lowercase() + "_accounted_or_reserved", accounted)
        put(dimension.lowercase() + "_estimate", if (source == "estimated") accounted else JsonNull)
        put(dimension.lowercase() + "_interim_reported", if (source.endsWith("_interim")) observed else JsonNull)
        put(dimension.lowercase() + "_initial_reported", if (source.endsWith("_initial")) observed else JsonNull)
        put(dimension.lowercase() + "_final_reported", if (dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(source)) observed else JsonNull)
        put(dimension.lowercase() + "_source", source)
    }
    put("superseded_frames", "Not reconstructed from the latest invocation record; consult retained provider events when available")
}
