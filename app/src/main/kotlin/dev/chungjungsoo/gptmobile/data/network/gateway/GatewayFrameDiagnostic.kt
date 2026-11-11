package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Counts describe the sampled window. Lost samples never become dropped display frames. */
internal data class GatewayFrameDiagnostic(val frames: Long?, val missed: Long?, val lost: Long?, val maximumNs: Long?, val source: JsonObject) {
    val severe: Boolean get() = (maximumNs ?: 0) >= 100_000_000
    val jank: Boolean get() = frames != null && frames > 0 && missed != null && missed.toDouble() / frames >= 0.05
    val errorCode: String get() = when {
        severe -> "RENDERER_STALL"
        jank -> "RENDERER_JANK"
        (lost ?: 0) > 0 -> "RENDERER_MEASUREMENT_LOSS"
        else -> "NONE"
    }

    fun attributes(): JsonObject = buildJsonObject {
        frames?.let { put("frame_count", it) }
        missed?.let { put("missed_deadline_count", it) }
        lost?.let { put("dropped_samples", it) }
        maximumNs?.let { put("maximum_frame_ns", it) }
        put("severe_frame_threshold_ns", 100_000_000L)
        put("jank_threshold_ppm", 50_000L)
        put("severe_long_frame", severe)
        put("sustained_jank", jank)
        put("measurement_loss", (lost ?: 0) > 0)
        listOf("averageMs" to "average_frame_ns", "p50UpperMs" to "p50_upper_ns", "p95UpperMs" to "p95_upper_ns", "p99UpperMs" to "p99_upper_ns", "frameBudgetMs" to "frame_budget_ns").forEach { (input, output) -> source.number(input)?.let { nanos(it) }?.let { put(output, it) } }
        listOf("minimumDeadlineNs" to "minimum_deadline_ns", "maximumDeadlineNs" to "maximum_deadline_ns", "fallbackDeadlines" to "fallback_deadlines", "windowMs" to "window_ms", "stallsOver100Ms" to "stalls_over_100_ms", "stallsOver500Ms" to "stalls_over_500_ms", "refreshRateMillihz" to "refresh_rate_millihz", "gcCount" to "gc_count", "gcDurationMs" to "gc_duration_ms", "nativeHeapBytes" to "native_heap_bytes").forEach { (input, output) -> source.count(input)?.let { put(output, it) } }
        (source["screen"] as? JsonPrimitive)?.contentOrNull?.let { put("screen_digest", GatewayDiagnosticEvent.digest(it)) }
        (source["percentileOverflow"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()?.let { put("percentile_overflow", it) }
    }

    companion object {
        fun from(fields: JsonObject): GatewayFrameDiagnostic = GatewayFrameDiagnostic(
            fields.count("frames") ?: fields.count("frameCount"),
            fields.count("jankFrames") ?: fields.count("slowFrames"),
            fields.count("droppedSamples"),
            fields.number("maxMs")?.let(::nanos),
            fields
        )
        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        private fun JsonObject.count(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.takeIf { it >= 0 }
        private fun nanos(ms: Double): Long? = (ms * 1_000_000).takeIf { it <= (1L shl 53) }?.toLong()
    }
}
