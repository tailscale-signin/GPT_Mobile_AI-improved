package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Monotonic Android observations for one physical HTTP attempt; these are not UI paint timestamps. */
data class ProviderResponseTiming(
    val responseHeadersMs: Long? = null,
    val firstParsedEventMs: Long? = null,
    val firstParsedPayloadMs: Long? = null,
    val httpStatus: Int? = null,
    val serverRequestId: String? = null,
    val generationId: String? = null
) {
    fun evidence(): JsonObject = buildJsonObject {
        put("measurement_scope", "android_http_attempt")
        put("response_headers_ms", responseHeadersMs?.let(::JsonPrimitive) ?: JsonNull)
        put("first_parsed_event_ms", firstParsedEventMs?.let(::JsonPrimitive) ?: JsonNull)
        put("first_parsed_payload_ms", firstParsedPayloadMs?.let(::JsonPrimitive) ?: JsonNull)
        put("http_status", httpStatus?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "remote_acceptance",
            when (httpStatus) {
                in 200..299 -> "YES"
                in 400..499 -> "NO"
                else -> "UNKNOWN"
            }
        )
        serverRequestId?.let { put("server_request_id", it) }
        generationId?.let { put("generation_id", it) }
        put("first_ui_paint_ms", JsonNull)
    }
}

internal class ProviderResponseObserver(private val now: () -> Long = ProviderElapsedClock::now) {
    private val started = now()
    private var headers: Long? = null
    private var firstEvent: Long? = null
    private var firstPayload: Long? = null
    private var status: Int? = null
    private var requestId: String? = null
    private var generationId: String? = null

    fun headersReceived(httpStatus: Int? = null, header: (String) -> String? = { null }) {
        if (headers == null) headers = elapsed()
        status = httpStatus?.takeIf { it in 100..599 } ?: status
        requestId = providerRequestIdentity(header).takeUnless { it == "unavailable" } ?: requestId
        generationId = safeProviderRequestIdentity(header("X-Generation-ID")) ?: generationId
    }

    fun parsed(hasAssistantPayload: Boolean): ProviderResponseTiming {
        if (firstEvent == null) firstEvent = elapsed()
        if (hasAssistantPayload && firstPayload == null) firstPayload = elapsed()
        return snapshot()
    }

    fun snapshot(): ProviderResponseTiming = ProviderResponseTiming(headers, firstEvent, firstPayload, status, requestId, generationId)

    private fun elapsed(): Long = (now() - started).coerceAtLeast(0L)
}
