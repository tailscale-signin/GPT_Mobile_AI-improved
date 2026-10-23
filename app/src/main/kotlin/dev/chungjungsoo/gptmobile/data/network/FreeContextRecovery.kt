package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Only explicit total-context rejections can teach this ceiling; never output-only errors. */
internal fun rejectedFreeContextLimit(status: Int, body: String): Int? {
    if (status !in setOf(400, 413, 422)) return null
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val error = root["error"] as? JsonObject ?: root
    val code = (error["code"] as? JsonPrimitive)?.contentOrNull
    if (code !in setOf("context_window_exceeded", "context_length_exceeded")) return null
    val explicit = ((error["context_window"] ?: error["context_length"]) as? JsonPrimitive)?.intOrNull
    val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val parsed = Regex("(?i)(?:context window\\s*\\(|maximum context length is\\s+)([0-9,]+)\\s*tokens").find(message)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    return (explicit ?: parsed)?.takeIf { it in 1..100_000_000 }
}

internal class FreeContextRetry : java.io.IOException("Re-preparing the request with the provider's total context limit")
