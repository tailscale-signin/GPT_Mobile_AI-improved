package dev.chungjungsoo.gptmobile.data

import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConstantsTest {

    @Test
    fun defaultPlatformName_returnsExpectedValues() {
        assertEquals("OpenAI", ModelConstants.defaultPlatformName(ClientType.OPENAI))
        assertEquals("Anthropic", ModelConstants.defaultPlatformName(ClientType.ANTHROPIC))
        assertEquals("Google", ModelConstants.defaultPlatformName(ClientType.GOOGLE))
        assertEquals("Groq", ModelConstants.defaultPlatformName(ClientType.GROQ))
        assertEquals("Ollama", ModelConstants.defaultPlatformName(ClientType.OLLAMA))
        assertEquals("OpenRouter", ModelConstants.defaultPlatformName(ClientType.OPENROUTER))
        assertEquals("", ModelConstants.defaultPlatformName(ClientType.CUSTOM))
        assertEquals("Local", ModelConstants.defaultPlatformName(ClientType.LITERT_LM))
    }

    @Test
    fun defaultApiUrl_returnsValidUrlForCloudProviders() {
        assertTrue(ModelConstants.defaultApiUrl(ClientType.OPENAI).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.ANTHROPIC).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.GOOGLE).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.GROQ).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.OPENROUTER).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.OLLAMA).startsWith("http://"))
        assertEquals("", ModelConstants.defaultApiUrl(ClientType.CUSTOM))
        assertEquals("", ModelConstants.defaultApiUrl(ClientType.LITERT_LM))
    }

    @Test
    fun defaultModel_returnsNonEmptyString() {
        for (clientType in ClientType.entries) {
            val model = ModelConstants.defaultModel(clientType)
            if (clientType == ClientType.CUSTOM || clientType == ClientType.LITERT_LM) {
                assertEquals("", model)
            } else {
                assertTrue(model.isNotEmpty())
            }
        }
    }

    @Test
    fun normalizeLegacyAPIUrl_normalizesTrailingSlashes() {
        assertEquals(
            "https://api.openai.com/v1/",
            ModelConstants.normalizeLegacyAPIUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1/",
            ModelConstants.normalizeLegacyAPIUrl("https://api.openai.com")
        )
        assertEquals(
            "https://api.anthropic.com/v1/",
            ModelConstants.normalizeLegacyAPIUrl("https://api.anthropic.com")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/",
            ModelConstants.normalizeLegacyAPIUrl("https://generativelanguage.googleapis.com")
        )
        assertEquals(
            "https://api.groq.com/openai/v1/",
            ModelConstants.normalizeLegacyAPIUrl("https://api.groq.com/openai")
        )
        assertEquals(
            "https://openrouter.ai/api/v1/",
            ModelConstants.normalizeLegacyAPIUrl("https://openrouter.ai/api")
        )
        assertEquals(
            "http://localhost:11434/v1/",
            ModelConstants.normalizeLegacyAPIUrl("http://localhost:11434")
        )
        // Custom or unmapped URL should be preserved
        assertEquals(
            "https://custom-proxy.internal/v1",
            ModelConstants.normalizeLegacyAPIUrl("https://custom-proxy.internal/v1")
        )
    }

    @Test
    fun promptTemplates_areNonBlank() {
        assertFalse(ModelConstants.DEFAULT_PROMPT.isBlank())
        assertFalse(ModelConstants.OPENAI_PROMPT.isBlank())
        assertFalse(ModelConstants.CHAT_TITLE_GENERATE_PROMPT.isBlank())
    }
}
