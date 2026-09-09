package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.Message
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterPlugin
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoning
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAdvancedOptionsTest {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `standard request does not serialize openrouter fields when null`() {
        val request = ChatCompletionRequest(
            model = "openai/gpt-4o",
            messages = listOf(Message(role = "user", content = "Hello"))
        )

        val encoded = json.encodeToString(request)

        assertFalse(encoded.contains("\"models\""))
        assertFalse(encoded.contains("\"provider\""))
        assertFalse(encoded.contains("\"transforms\""))
        assertFalse(encoded.contains("\"reasoning\""))
        assertFalse(encoded.contains("\"plugins\""))
    }

    @Test
    fun `openrouter advanced fields are correctly serialized when specified`() {
        val request = ChatCompletionRequest(
            model = "openai/gpt-4o",
            messages = listOf(Message(role = "user", content = "Hello")),
            models = listOf("anthropic/claude-3.5-sonnet", "google/gemini-pro-1.5"),
            provider = OpenRouterProviderRouting(
                order = listOf("Anthropic", "OpenAI"),
                allowFallbacks = false,
                requireParameters = true,
                dataCollection = "deny",
                ignore = listOf("Together"),
                quantizations = listOf("fp16", "int8"),
                sort = "price"
            ),
            transforms = listOf("middle-out"),
            reasoning = OpenRouterReasoning(
                effort = "high",
                maxTokens = 2048,
                exclude = false
            ),
            plugins = listOf(
                OpenRouterPlugin(
                    id = "web",
                    maxResults = 5,
                    searchPrompt = "search the web"
                )
            )
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"models\":[\"anthropic/claude-3.5-sonnet\",\"google/gemini-pro-1.5\"]"))
        assertTrue(encoded.contains("\"transforms\":[\"middle-out\"]"))
        assertTrue(encoded.contains("\"provider\":{"))
        assertTrue(encoded.contains("\"order\":[\"Anthropic\",\"OpenAI\"]"))
        assertTrue(encoded.contains("\"allow_fallbacks\":false"))
        assertTrue(encoded.contains("\"require_parameters\":true"))
        assertTrue(encoded.contains("\"data_collection\":\"deny\""))
        assertTrue(encoded.contains("\"ignore\":[\"Together\"]"))
        assertTrue(encoded.contains("\"quantizations\":[\"fp16\",\"int8\"]"))
        assertTrue(encoded.contains("\"sort\":\"price\""))
        assertTrue(encoded.contains("\"reasoning\":{"))
        assertTrue(encoded.contains("\"effort\":\"high\""))
        assertTrue(encoded.contains("\"max_tokens\":2048"))
        assertTrue(encoded.contains("\"exclude\":false"))
        assertTrue(encoded.contains("\"plugins\":[{"))
        assertTrue(encoded.contains("\"id\":\"web\""))
        assertTrue(encoded.contains("\"max_results\":5"))
        assertTrue(encoded.contains("\"search_prompt\":\"search the web\""))
    }
}
