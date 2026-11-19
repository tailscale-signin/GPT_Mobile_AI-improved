package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val totalContextMessage = Regex(
    """(?i)(?:context window\s*\(\s*|maximum context length (?:is|of)\s+)(\d{1,9}|\d{1,3}(?:,\d{3}){1,2})\s*tokens\b"""
)

/** Only explicit total-context rejections can teach this ceiling; never output-only errors. */
internal fun rejectedFreeContextLimit(status: Int, body: String): Int? {
    if (status !in setOf(400, 413, 422) || body.length > 65536) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val error = root["error"] as? JsonObject ?: root
    val code = (error["code"] as? JsonPrimitive)?.contentOrNull
    if (code !in setOf("context_window_exceeded", "context_length_exceeded")) return null
    val explicit = listOf("context_window", "context_length").mapNotNull { (error[it] as? JsonPrimitive)?.intOrNull }
    val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val limits = totalContextMessage.findAll(message).mapNotNull { it.groupValues[1].replace(",", "").toIntOrNull() }
    return (explicit.asSequence() + limits).filter { it in 1..100_000_000 }.minOrNull()
}

internal class FreeContextRetry : java.io.IOException("Re-preparing the request with the provider's total context limit")
