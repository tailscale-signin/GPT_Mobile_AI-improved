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
            val nested = runCatching { Json.parseToJsonElement(raw.trim().removePrefix("data:").trim()) as? JsonObject }.getOrNull()
            val nestedError = nested?.get("error") as? JsonObject ?: nested
            // Only the upstream message, not an arbitrary response body (which can echo prompts).
            listOfNotNull(
                nestedError.value("message"),
                // Some upstreams put the actionable ceiling in param. Accept only a
                // numeric token-limit diagnostic, never arbitrary echoed parameters.
                nestedError.value("param")?.takeIf { param ->
                    Regex("(?i)^max[_ ](?:completion[_ ])?tokens.{0,80}(?:too large|exceed)").containsMatchIn(param) &&
                        Regex("(?i)(?:completion|output) tokens").containsMatchIn(param)
                }
            ).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "upstream=$it" }
        }
    ).joinToString(" · ")
    var redacted = details
    credential?.split(',')?.map(String::trim)?.filter { it.isNotBlank() }?.forEach { secret ->
        redacted = redacted.replace(secret, "[redacted]")
    }
    return redactLogMessage(redacted).replace('\n', ' ').replace('\r', ' ').take(1600)
}
