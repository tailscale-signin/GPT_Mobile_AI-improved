package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Availability describes retained evidence, while captureEnabled describes current policy. */
internal object JournalCaptureSummary {
    fun describe(journal: JsonObject?, eventCount: Int, capture: JsonObject? = null): JsonObject {
        fun number(key: String) = (journal?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
        val enabled = (journal?.get("captureEnabled") as? JsonPrimitive)?.booleanOrNull
            ?: (capture?.get("diagnosticsEnabled") as? JsonPrimitive)?.booleanOrNull
        val storageFailed = (journal?.get("storageFailed") as? JsonPrimitive)?.booleanOrNull == true
        val availability = when {
            storageFailed -> "STORAGE_FAILED"
            eventCount == 0 && enabled == false -> "DISABLED"
            number("droppedEvents") > 0 || number("missingBatches") > 0 || number("missingSequences") > 0 -> "PARTIAL"
            eventCount == 0 -> "EMPTY"
            else -> "AVAILABLE"
        }
        return buildJsonObject {
            put("availability", availability)
            enabled?.let { put("captureEnabled", it) }
            put("policyKnown", enabled != null)
            put("eventCount", eventCount)
            put("eventScope", "selected_run_ids")
            put("counterScope", (journal?.get("counterScope") as? JsonPrimitive)?.content ?: "legacy_unspecified")
            put("lossReasons", journal?.get("lossReasons") ?: JsonObject(emptyMap()))
            put("functionalOutcomes", "Research attempts and run outcomes are retained independently of optional diagnostics")
        }
    }
}
