package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevisionListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.GeminiSafetySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDatabaseV2MigrationsTest {
    @Test
    fun `new platform defaults Gemini safety thresholds to block none`() {
        val platform = PlatformV2(
            name = "Google",
            compatibleType = ClientType.GOOGLE,
            apiUrl = "https://generativelanguage.googleapis.com",
            model = "gemini-3-pro-preview"
        )

        assertEquals(GeminiSafetySettings.BLOCK_NONE, platform.harassmentSafetyThreshold)
        assertEquals(GeminiSafetySettings.BLOCK_NONE, platform.hateSpeechSafetyThreshold)
        assertEquals(GeminiSafetySettings.BLOCK_NONE, platform.sexuallyExplicitSafetyThreshold)
        assertEquals(GeminiSafetySettings.BLOCK_NONE, platform.dangerousContentSafetyThreshold)
    }

    @Test
    fun `new platform defaults max tool calls to integer max value`() {
        val platform = PlatformV2(
            name = "OpenAI",
            compatibleType = ClientType.OPENAI,
            apiUrl = "https://api.openai.com/v1/",
            model = "gpt-5.6"
        )

        assertEquals(Int.MAX_VALUE, platform.maxToolCalls)
    }

    @Test
    fun `new platform defaults open router routing to null`() {
        val platform = PlatformV2(
            name = "OpenRouter",
            compatibleType = ClientType.OPENROUTER,
            apiUrl = "https://openrouter.ai/api/v1/",
            model = "openai/gpt-5.6-sol"
        )

        assertNull(platform.openRouterRouting)
    }

    @Test
    fun `migration instances have correct versions`() {
        assertEquals(10, ChatDatabaseV2Migrations.MIGRATION_10_11.startVersion)
        assertEquals(11, ChatDatabaseV2Migrations.MIGRATION_10_11.endVersion)

        assertEquals(11, ChatDatabaseV2Migrations.MIGRATION_11_12.startVersion)
        assertEquals(12, ChatDatabaseV2Migrations.MIGRATION_11_12.endVersion)

        assertEquals(12, ChatDatabaseV2Migrations.MIGRATION_12_13.startVersion)
        assertEquals(13, ChatDatabaseV2Migrations.MIGRATION_12_13.endVersion)

        assertEquals(13, ChatDatabaseV2Migrations.MIGRATION_13_14.startVersion)
        assertEquals(14, ChatDatabaseV2Migrations.MIGRATION_13_14.endVersion)

        assertEquals(14, ChatDatabaseV2Migrations.MIGRATION_14_15.startVersion)
        assertEquals(15, ChatDatabaseV2Migrations.MIGRATION_14_15.endVersion)
    }

    @Test
    fun `corrupt assistant revision json decodes to empty list`() {
        val revisions = AssistantRevisionListConverter().fromString("[")

        assertTrue(revisions.isEmpty())
    }

    @Test
    fun `assistant revision serialization preserves run linkage`() {
        val converter = AssistantRevisionListConverter()
        val encoded = converter.fromList(
            listOf(
                AssistantRevision(
                    content = "Answer",
                    thoughts = "Reasoning",
                    createdAt = 1234L,
                    runId = "run-123"
                )
            )
        )

        val decoded = converter.fromString(encoded)

        assertEquals("run-123", decoded.single().runId)
    }

    @Test
    fun `legacy assistant revision json decodes without run linkage`() {
        val decoded = AssistantRevisionListConverter().fromString(
            """[{"content":"Old answer","thoughts":"","createdAt":1234}]"""
        )

        assertNull(decoded.single().runId)
    }

    @Test
    fun `legacy provider api urls normalize to current defaults`() {
        assertEquals(ModelConstants.OPENAI_API_URL, ModelConstants.normalizeLegacyAPIUrl("https://api.openai.com/"))
        assertEquals(ModelConstants.ANTHROPIC_API_URL, ModelConstants.normalizeLegacyAPIUrl("https://api.anthropic.com/"))
        assertEquals(ModelConstants.GOOGLE_API_URL, ModelConstants.normalizeLegacyAPIUrl("https://generativelanguage.googleapis.com"))
        assertEquals(ModelConstants.GROQ_API_URL, ModelConstants.normalizeLegacyAPIUrl("https://api.groq.com/openai/"))
        assertEquals(ModelConstants.OPENROUTER_API_URL, ModelConstants.normalizeLegacyAPIUrl("https://openrouter.ai/api/"))
        assertEquals(ModelConstants.OLLAMA_API_URL, ModelConstants.normalizeLegacyAPIUrl("http://localhost:11434/"))
        assertEquals("https://proxy.example/api/", ModelConstants.normalizeLegacyAPIUrl("https://proxy.example/api/"))
    }

    @Test
    fun `new platform defaults local inference columns to null`() {
        val platform = PlatformV2(
            name = "Local",
            compatibleType = ClientType.LITERT_LM,
            apiUrl = "",
            model = "gemma3-1b-it"
        )

        assertNull(platform.topK)
        assertNull(platform.maxTokens)
        assertNull(platform.accelerator)
    }

    @Test
    fun `default favorite state is false`() {
        val chatRoom = ChatRoomV2(title = "Test Room")
        assertFalse(chatRoom.isFavorite)
    }
}
