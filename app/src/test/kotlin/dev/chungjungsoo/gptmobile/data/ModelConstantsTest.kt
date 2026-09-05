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
        assertEquals("On-Device (Local)", ModelConstants.defaultPlatformName(ClientType.LOCAL))
    }

    @Test
    fun defaultApiUrl_returnsValidUrlForCloudProviders() {
        assertTrue(ModelConstants.defaultApiUrl(ClientType.OPENAI).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.ANTHROPIC).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.GOOGLE).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.GROQ).startsWith("https://"))
        assertTrue(ModelConstants.defaultApiUrl(ClientType.OLLAMA).startsWith("http://"))
        assertEquals("", ModelConstants.defaultApiUrl(ClientType.LOCAL))
    }

    @Test
    fun defaultModel_returnsNonEmptyString() {
        for (clientType in ClientType.entries) {
            assertTrue(ModelConstants.defaultModel(clientType).isNotEmpty())
        }
    }

    @Test
    fun normalizeLegacyAPIUrl_normalizesTrailingSlashes() {
        assertEquals(
            "https://api.openai.com/v1",
            ModelConstants.normalizeLegacyAPIUrl(ClientType.OPENAI, "https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1",
            ModelConstants.normalizeLegacyAPIUrl(ClientType.OPENAI, "https://api.openai.com/v1")
        )
        // Blank input should fall back to default
        assertEquals(
            ModelConstants.defaultApiUrl(ClientType.OPENAI),
            ModelConstants.normalizeLegacyAPIUrl(ClientType.OPENAI, "   ")
        )
    }

    @Test
    fun promptTemplates_areNonBlank() {
        assertFalse(ModelConstants.DEFAULT_SYSTEM_PROMPT.isBlank())
        assertFalse(ModelConstants.WEB_SEARCH_USER_PROMPT_TEMPLATE.isBlank())
        assertFalse(ModelConstants.WEB_SEARCH_AGENT_SYSTEM_PROMPT.isBlank())
    }
}
