package dev.chungjungsoo.gptmobile.data.nvidia

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class NvidiaProbeResult(
    val status: Int,
    val visibleAnswer: Boolean,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)

/** Explicit user-triggered 64-token test only. Not part of automatic model discovery. */
class NvidiaInferenceProbe @Inject constructor(private val network: NetworkClient) {
    suspend fun check(apiUrl: String, apiKey: String, model: String): NvidiaProbeResult = withTimeout(60_000L) {
        require(apiKey.isNotBlank() && model.isNotBlank()) { "NVIDIA inference requires a model and API key." }
        val config = ProviderRequestConfig(NvidiaEndpoints.normalize(apiUrl), apiKey, providerType = "NVIDIA")
        val request = NvidiaRequestPolicy.prepare(
            ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage(Role.USER, listOf(TextContent("Reply with OK.")))),
                maxTokens = 64,
                stream = false
            ),
            reasoningRequested = false,
            reasoningAllowed = false
        )
        // The hosted NIM endpoints accept the simplest OpenAI text-message wire shape.
        // Keep the shared DTO/policy for all other fields, but serialize text as a string.
        val root = NetworkClient.openAIJson.parseToJsonElement(NetworkClient.openAIJson.encodeToString(request)).jsonObject
        val message = buildJsonObject {
            put("role", "user")
            put("content", "Reply with OK.")
        }
        val wire = JsonObject(root + ("messages" to buildJsonArray { add(message) })).toString()
        val response = network().post(config.buildEndpoint("chat/completions")) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(wire)
        }
        val body = response.bodyAsText()
        val parsed = if (response.status.value == 200) {
            runCatching { NetworkClient.openAIJson.decodeFromString<ChatCompletionChunk>(body) }.getOrNull()
        } else {
            null
        }
        NvidiaProbeResult(
            status = response.status.value,
            visibleAnswer = parsed?.choices.orEmpty().any { !it.effectiveDelta.content.isNullOrBlank() },
            inputTokens = parsed?.usage?.promptTokens,
            outputTokens = parsed?.usage?.completionTokens
        )
    }
}
