package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reservations, interim observations and final counts are distinct; absent totals stay absent. */
internal fun invocationUsageEvidence(invocation: JsonObject): JsonObject = buildJsonObject {
    put("invocation_id", invocation["id"] ?: JsonNull)
    val retained = (invocation["usageEvidenceJson"] as? JsonPrimitive)?.content?.let { raw -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }?.takeIf { it["invocation_id"] == invocation["id"] }
    if (retained != null) put("immutable_observations", retained)
    put("cost_known", retained?.get("cost_confirmed") == JsonPrimitive(true) && invocation["costMicros"] != null && invocation["costMicros"] != JsonNull)
    put("cost_confirmed", retained?.get("cost_confirmed") ?: JsonPrimitive(false))
    for (dimension in listOf("Input", "Output")) {
        val accountingSource = (invocation[dimension.lowercase() + "TokenSource"] as? JsonPrimitive)?.content.orEmpty()
        val source = ((retained?.get(dimension.lowercase()) as? JsonObject)?.get("source") as? JsonPrimitive)?.content ?: accountingSource
        val observed = invocation["observed${dimension}Tokens"] ?: JsonNull
        val accounted = invocation[dimension.lowercase() + "Tokens"] ?: JsonNull
        put(dimension.lowercase() + "_accounted_or_reserved", accounted)
        put(dimension.lowercase() + "_accounting_source", if (accounted != observed && !dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(source)) "estimate_or_reservation_floor" else accountingSource)
        put(dimension.lowercase() + "_estimate", if (source == "estimated") accounted else JsonNull)
        put(dimension.lowercase() + "_interim_reported", if (source.endsWith("_interim")) observed else JsonNull)
        put(dimension.lowercase() + "_initial_reported", if (source.endsWith("_initial")) observed else JsonNull)
        put(dimension.lowercase() + "_final_reported", if (dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(source)) observed else JsonNull)
        put(dimension.lowercase() + "_source", source)
    }
    put("cost_accounted_or_reserved", invocation["costMicros"] ?: JsonNull)
    put("cost_final", if (retained?.get("cost_confirmed") == JsonPrimitive(true)) invocation["costMicros"] ?: JsonNull else JsonNull)
    put("superseded_frames", if (retained != null) "Bounded immutable observations retained with omission counts" else "Historical frames unavailable in this legacy record")
}
