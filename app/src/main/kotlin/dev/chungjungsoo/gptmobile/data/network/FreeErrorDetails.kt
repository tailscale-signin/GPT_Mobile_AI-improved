package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.network.error.ProviderFailure
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal suspend fun readFreeErrorBody(response: HttpResponse): String {
    val channel = response.bodyAsChannel()
    val buffer = ByteArray(16_384)
    var count = 0
    while (count < buffer.size) {
        val read = channel.readAvailable(buffer, count, buffer.size - count)
        if (read < 0) break
        count += read
    }
    return buffer.decodeToString(0, count)
}

internal fun freeHttpErrorMessage(status: Int, body: String, credential: String? = null): String {
    val summary = ProviderFailure.parse(status, body).message()
    val detail = providerErrorDetails(body, "", credential)
    return if (detail.isBlank()) summary else "$summary $detail"
}

internal fun gradioErrorEnvelope(data: String): String {
    val parsed = runCatching { Json.parseToJsonElement(data.take(16_384)) }.getOrNull()
    val value = if (parsed is JsonArray) parsed.firstOrNull { it != JsonNull } else parsed
    return when (value) {
        is JsonObject -> {
            val error = value["error"]
            if (error is JsonPrimitive && error != JsonNull) buildJsonObject { put("message", error.contentOrNull.orEmpty()) }.toString() else value.toString()
        }
        is JsonPrimitive -> if (value == JsonNull) "{}" else buildJsonObject { put("message", value.contentOrNull.orEmpty()) }.toString()
        else -> "{}"
    }
}

internal fun gradioErrorDetails(data: String, token: String? = null): String = providerErrorDetails(gradioErrorEnvelope(data), "No more specific reason was supplied by the provider.", token).take(600)

// Qwen's exception handler appends a red diagnostic span to a finished chatbot update.
// Retain only that bounded field; never retain the surrounding conversation or model text.
internal fun qwenDiagnostic(data: kotlinx.serialization.json.JsonElement, token: String?): String? {
    val outputs = data as? JsonArray ?: return null
    val message = outputs.mapNotNull { if (it is JsonObject) it["value"] as? JsonArray else it as? JsonArray }
        .flatMap { it.filterIsInstance<JsonObject>() }.lastOrNull { (it["role"] as? JsonPrimitive)?.contentOrNull in setOf("assistant", "user") }
        ?.takeIf { (it["role"] as? JsonPrimitive)?.contentOrNull == "assistant" } ?: return null
    if ((message["status"] as? JsonPrimitive)?.contentOrNull != "done" || (message["loading"] as? JsonPrimitive)?.contentOrNull != "false") return null
    val blocks = message["content"] as? JsonArray ?: return null
    val detail = blocks.filterIsInstance<JsonObject>().lastOrNull()?.let { block ->
        if ((block["type"] as? JsonPrimitive)?.contentOrNull == "text") (block["content"] as? JsonPrimitive)?.contentOrNull else null
    } ?: return null
    val match = QWEN_DIAGNOSTIC.matchEntire(detail.take(4096)) ?: return null
    val text = match.groupValues[1].replace(Regex("<[^>]*>"), "").take(600)
    return providerErrorDetails(buildJsonObject { put("message", text) }.toString(), "Hosted generation failed", token).take(600)
}

internal val QWEN_DIAGNOSTIC = Regex("<span style=\"color: var\\(--color-red-500\\)\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
