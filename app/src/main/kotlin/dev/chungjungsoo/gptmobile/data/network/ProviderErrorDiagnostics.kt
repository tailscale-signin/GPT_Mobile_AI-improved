package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Bounded error-only details. Never log request bodies, arbitrary metadata, or credential echoes. */
internal fun providerErrorDetails(body: String, fallback: String, credential: String? = null): String {
    val envelope = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = envelope?.get("error") as? JsonObject ?: envelope
    val metadata = error?.get("metadata") as? JsonObject
    fun JsonObject?.value(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    val details = listOfNotNull(
        error.value("message") ?: fallback,
        error.value("code")?.let { "code=$it" },
        metadata.value("provider_name")?.let { "provider=$it" },
        (envelope.value("id") ?: metadata.value("generation_id"))?.let { "generation=$it" },
        metadata.value("raw")?.let { raw ->
            val nested = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            val nestedError = nested?.get("error") as? JsonObject ?: nested
            // Only the upstream message, not an arbitrary response body (which can echo prompts).
            nestedError.value("message")?.let { "upstream=$it" }
        }
    ).joinToString(" · ")
    var redacted = details
    credential?.split(',')?.map(String::trim)?.filter { it.isNotBlank() }?.forEach { secret ->
        redacted = redacted.replace(secret, "[redacted]")
    }
    return redactLogMessage(redacted).replace('\n', ' ').replace('\r', ' ').take(1600)
}
