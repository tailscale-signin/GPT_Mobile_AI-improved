package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class PrivateDeliveryMode { INCREMENTAL, TOOL_CONTROL_BUFFERED, COMPLETION_BUFFERED_ENDPOINT, COMPLETION_BUFFERED_PROVIDER }

/** Measured stream-local times and aggregate counts. No text, token, mapping or identity. */
internal data class PrivateStreamObservation(
    val firstReasoningMillis: Long?,
    val firstAnswerMillis: Long?,
    val firstRestoredAnswerMillis: Long?,
    val restorationNanos: Long,
    val restoredChunks: Int,
    val restoredRanges: Int,
    val maximumHeldMarkerCharacters: Int,
    val repairedMarkers: Int,
    val markerProtocolStatus: PrivateMarkerProtocolStatus,
    val anomalies: Set<PrivateOutputAnomaly>,
    val completed: Boolean,
    val deliveryMode: PrivateDeliveryMode = PrivateDeliveryMode.INCREMENTAL
) {
    fun evidence(): JsonObject = buildJsonObject {
        put("schema", "privacy.stream-observation.v1")
        put("scope", "SELECTED_PROVIDER_STREAM_MONOTONIC_TIME")
        put("delivery_mode", deliveryMode.name)
        firstReasoningMillis?.let { put("first_reasoning_ms", it) }
        firstAnswerMillis?.let { put("first_upstream_answer_ms", it) }
        firstRestoredAnswerMillis?.let { put("first_restored_answer_ms", it) }
        put("restoration_ns", restorationNanos)
        put("restored_chunks", restoredChunks)
        put("restored_ranges", restoredRanges)
        put("maximum_held_marker_characters", maximumHeldMarkerCharacters)
        put("repaired_marker_count", repairedMarkers)
        put("marker_protocol_status", markerProtocolStatus.name)
        put("usefulness_status", "UNASSESSED")
        put("anomaly_codes", JsonArray(anomalies.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
        put("completed", completed)
        put("contains_content", false)
    }
}

internal class PrivateStreamTiming(private val clock: () -> Long = System::nanoTime) {
    private val start = clock()
    private var firstReasoning: Long? = null
    private var firstAnswer: Long? = null
    private var firstRestored: Long? = null

    fun reasoning() {
        if (firstReasoning == null) firstReasoning = elapsed()
    }
    fun answer() {
        if (firstAnswer == null) firstAnswer = elapsed()
    }
    fun restoredAnswer() {
        if (firstRestored == null) firstRestored = elapsed()
    }

    fun observation(
        answer: PrivateStreamingRestorer,
        reasoning: PrivateStreamingRestorer,
        protocol: PrivateMarkerProtocol,
        completed: Boolean,
        deliveryMode: PrivateDeliveryMode = PrivateDeliveryMode.INCREMENTAL
    ) = PrivateStreamObservation(
        firstReasoning, firstAnswer, firstRestored,
        answer.restorationNanos + reasoning.restorationNanos,
        answer.restoredChunks + reasoning.restoredChunks,
        answer.restoredRanges + reasoning.restoredRanges,
        maxOf(answer.maximumHeldCharacters, reasoning.maximumHeldCharacters),
        protocol.repairedMarkers, protocol.status(), protocol.anomalyCodes(), completed, deliveryMode
    )

    private fun elapsed(): Long = ((clock() - start).coerceAtLeast(0) / 1_000_000)
}
