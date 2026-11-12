package dev.chungjungsoo.gptmobile.data.dto.openai.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Server ASGI send acknowledgments describe this connection, independently of saved backend generation. */
@Serializable
data class GatewayDeliveryTiming(
    @SerialName("measurement_scope") val measurementScope: String? = null,
    @SerialName("delivery_mode") val deliveryMode: String? = null,
    @SerialName("first_event_submit_ms") val firstEventSubmitMs: Double? = null,
    @SerialName("first_event_send_completed_ms") val firstEventSendCompletedMs: Double? = null,
    @SerialName("first_content_submit_ms") val firstContentSubmitMs: Double? = null,
    @SerialName("first_content_send_completed_ms") val firstContentSendCompletedMs: Double? = null,
    @SerialName("observed_elapsed_ms") val observedElapsedMs: Double? = null,
    @SerialName("forwarded_events") val forwardedEvents: Int? = null
) {
    fun evidence(): JsonObject? {
        if (measurementScope != "gateway_asgi_stream") return null
        val mode = deliveryMode?.takeIf { it in setOf("completion_buffered", "incremental_final_answer") } ?: return null
        val fields = mapOf(
            "first_event_submit_ms" to firstEventSubmitMs,
            "first_event_send_completed_ms" to firstEventSendCompletedMs,
            "first_content_submit_ms" to firstContentSubmitMs,
            "first_content_send_completed_ms" to firstContentSendCompletedMs,
            "observed_elapsed_ms" to observedElapsedMs
        )
        if (fields.values.any { it != null && (!it.isFinite() || it !in 0.0..86_400_000.0) }) return null
        return buildJsonObject {
            put("measurement_scope", "gateway_asgi_stream")
            put("delivery_mode", mode)
            fields.forEach { (key, value) -> value?.let { put(key, it) } }
            forwardedEvents?.takeIf { it >= 0 }?.let { put("forwarded_events", it) }
            // Awaited ASGI send is observable; socket/proxy flush is not exposed by this interface.
            put("socket_flush_confirmed", JsonNull)
        }
    }
}
