package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Wall-clock adjustments never become negative latency or fabricated native durations. */
internal object DiagnosticClockReconciliation {
    fun summarize(rows: JsonArray): JsonObject {
        val previous = mutableMapOf<Long, Pair<Long, Long>>()
        val skew = mutableListOf<String>()
        var anchored = 0
        var discontinuities = 0
        for (row in rows.filterIsInstance<JsonObject>()) {
            val wall = (row["time"] as? JsonPrimitive)?.longOrNull ?: continue
            val mono = (row["monotonicNanos"] as? JsonPrimitive)?.longOrNull ?: continue
            val process = (row["processId"] as? JsonPrimitive)?.longOrNull ?: continue
            if (process < 0 || mono < 0) continue
            anchored++
            previous[process]?.let { (priorWall, priorMono) ->
                if (mono < priorMono) {
                    discontinuities++
                } else if (kotlin.math.abs((wall - priorWall) - (mono - priorMono) / 1_000_000) > 2000) {
                    skew += (row["eventId"] as? JsonPrimitive)?.content.orEmpty()
                }
            }
            previous[process] = wall to mono
        }
        return buildJsonObject {
            put("schemaVersion", 1)
            put("monotonicClock", "SYSTEM_NANO_TIME")
            put("bootClock", "ELAPSED_REALTIME")
            put("anchoredRecords", anchored)
            put("legacyOrUnanchoredRecords", rows.size - anchored)
            put("clockDiscontinuities", discontinuities)
            put("wallClockAdjustmentEvents", JsonArray(skew.map(::JsonPrimitive)))
            put("nativeDurationPolicy", "Retain operation-local native elapsed durations; do not subtract them from wall or boot timestamps")
        }
    }
}
