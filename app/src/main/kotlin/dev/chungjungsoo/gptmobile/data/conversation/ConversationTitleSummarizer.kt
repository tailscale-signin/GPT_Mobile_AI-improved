package dev.chungjungsoo.gptmobile.data.conversation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.Message
import dev.chungjungsoo.gptmobile.data.dto.openai.request.StringContent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.GoogleAPI
import dev.chungjungsoo.gptmobile.data.network.GroqAPI
import dev.chungjungsoo.gptmobile.data.network.OpenAIAPI
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.anthropicProviderConfig
import dev.chungjungsoo.gptmobile.data.network.geminiProviderConfig
import dev.chungjungsoo.gptmobile.data.network.groqProviderConfig
import dev.chungjungsoo.gptmobile.data.network.openAICompatibleProviderConfig
import dev.chungjungsoo.gptmobile.data.network.openAIProviderConfig
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

class ConversationTitleSummarizer(
    private val openAIAPI: OpenAIAPI,
    private val groqAPI: GroqAPI,
    private val googleAPI: GoogleAPI
) {
    suspend fun summarize(
        userMessage: String,
        assistantMessage: String,
        platform: PlatformV2
    ): String? = withTimeoutOrNull(TIMEOUT_MS) {
        val prompt = buildPrompt(userMessage, assistantMessage)
        val title = when (platform.compatibleType) {
            ClientType.OPENAI -> summarizeWithOpenAI(prompt, platform, openAIProviderConfig(platform))
            ClientType.GROQ -> summarizeWithGroq(prompt, platform, groqProviderConfig(platform))
            ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM ->
                summarizeWithOpenAI(prompt, platform, openAICompatibleProviderConfig(platform))
            ClientType.GOOGLE -> summarizeWithGemini(prompt, platform, geminiProviderConfig(platform))
            else -> null
        }
        title?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
    }

    private suspend fun summarizeWithOpenAI(
        prompt: String,
        platform: PlatformV2,
        config: ProviderRequestConfig
    ): String? {
        val request = ChatCompletionRequest(
            model = platform.model,
            messages = listOf(
                Message(role = "system", content = StringContent(SYSTEM_INSTRUCTION)),
                Message(role = "user", content = StringContent(prompt))
            ),
            temperature = 0.3f,
            maxCompletionTokens = 30
        )
        val sb = StringBuilder()
        openAIAPI.streamChatCompletion(request, timeoutSeconds = 15, config = config)
            .catch { }
            .collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.content?.let { sb.append(it) }
            }
        return sb.toString().trim()
    }

    private suspend fun summarizeWithGroq(
        prompt: String,
        platform: PlatformV2,
        config: ProviderRequestConfig
    ): String? {
        val request = ChatCompletionRequest(
            model = platform.model,
            messages = listOf(
                Message(role = "system", content = StringContent(SYSTEM_INSTRUCTION)),
                Message(role = "user", content = StringContent(prompt))
            ),
            temperature = 0.3f,
            maxCompletionTokens = 30
        )
        val sb = StringBuilder()
        groqAPI.streamChatCompletion(request, timeoutSeconds = 15, config = config)
            .catch { }
            .collect { chunk ->
                chunk.choices.firstOrNull()?.delta?.content?.let { sb.append(it) }
            }
        return sb.toString().trim()
    }

    private suspend fun summarizeWithGemini(
        prompt: String,
        platform: PlatformV2,
        config: ProviderRequestConfig
    ): String? {
        val request = dev.chungjungsoo.gptmobile.data.dto.google.GeminiRequest(
            contents = listOf(
                dev.chungjungsoo.gptmobile.data.dto.google.Content(
                    role = "user",
                    parts = listOf(dev.chungjungsoo.gptmobile.data.dto.google.Part.TextPart(text = "$SYSTEM_INSTRUCTION\n\n$prompt"))
                )
            ),
            generationConfig = dev.chungjungsoo.gptmobile.data.dto.google.GenerationConfig(
                temperature = 0.3f,
                maxOutputTokens = 30
            )
        )
        val sb = StringBuilder()
        googleAPI.streamChatCompletion(platform.model, request, timeoutSeconds = 15, config = config)
            .catch { }
            .collect { response ->
                response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.let { sb.append(it) }
            }
        return sb.toString().trim()
    }

    private fun buildPrompt(userMessage: String, assistantMessage: String): String =
        "User: ${userMessage.take(300)}\nAssistant: ${assistantMessage.take(300)}"

    private fun cleanTitle(raw: String): String {
        return raw.lineSequence().firstOrNull().orEmpty()
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .removeSurrounding("'")
            .removePrefix("Title:")
            .removePrefix("title:")
            .trim()
            .take(50)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val SYSTEM_INSTRUCTION =
            "Summarize the following exchange into a short, concise conversation title of 3 to 6 words. " +
                "Respond ONLY with the title text. Do NOT wrap in quotes, do NOT include punctuation, do NOT include explanations."
    }
}
