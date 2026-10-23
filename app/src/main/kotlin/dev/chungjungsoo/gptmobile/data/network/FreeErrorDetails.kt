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

internal fun freeHttpErrorMessage(status: Int, body: String): String {
    val summary = ProviderFailure.parse(status, body).message()
    val detail = providerErrorDetails(body, "")
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

internal fun gradioErrorDetails(data: String): String = providerErrorDetails(gradioErrorEnvelope(data), "No more specific reason was supplied by the provider.").take(600)
