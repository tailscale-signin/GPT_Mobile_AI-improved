package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterOptionsTest {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `default OpenRouterOptions values match specified defaults`() {
        val options = OpenRouterOptions.createDefault()

        assertEquals(true, options.stream)
        assertEquals(4096, options.maxTokens)
        assertEquals(0.2f, options.temperature ?: 0f, 0.001f)
        assertEquals(0.15f, options.topP ?: 0f, 0.001f)
        assertEquals(30, options.topK)
        assertEquals(0.0f, options.frequencyPenalty ?: 0f, 0.001f)
        assertEquals(0.0f, options.presencePenalty ?: 0f, 0.001f)
        assertEquals(1.03f, options.repetitionPenalty ?: 0f, 0.001f)
        assertEquals(42, options.seed)
        assertEquals("price-asc", options.provider?.sort)
        assertEquals(true, options.provider?.allowFallbacks)
        assertEquals(listOf("Mancer"), options.provider?.skip)
    }

    @Test
    fun `request serializes OpenRouter options and provider fields when provided`() {
        val routing = OpenRouterProviderRouting(
            sort = "price-asc",
            allowFallbacks = true,
            skip = listOf("Mancer")
        )
        val request = ChatCompletionRequest(
            model = "anthropic/claude-3.5-sonnet",
            messages = listOf(ChatMessage(role = Role.USER, content = listOf(TextContent("Hello")))),
            stream = true,
            maxTokens = 4096,
            temperature = 0.2f,
            topP = 0.15f,
            topK = 30,
            frequencyPenalty = 0.0f,
            presencePenalty = 0.0f,
            repetitionPenalty = 1.03f,
            seed = 42,
            provider = routing
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"stream\":true"))
        assertTrue(encoded.contains("\"max_tokens\":4096"))
        assertTrue(encoded.contains("\"temperature\":0.2"))
        assertTrue(encoded.contains("\"top_p\":0.15"))
        assertTrue(encoded.contains("\"top_k\":30"))
        assertTrue(encoded.contains("\"frequency_penalty\":0.0"))
        assertTrue(encoded.contains("\"presence_penalty\":0.0"))
        assertTrue(encoded.contains("\"repetition_penalty\":1.03"))
        assertTrue(encoded.contains("\"seed\":42"))
        assertTrue(encoded.contains("\"provider\":{"))
        assertTrue(encoded.contains("\"sort\":\"price-asc\""))
        assertTrue(encoded.contains("\"allow_fallbacks\":true"))
        assertTrue(encoded.contains("\"skip\":[\"Mancer\"]"))
    }
}
