package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {
    @Test
    fun `tool-only historical turn keeps its user message for follow-up context`() {
        val platform = PlatformV2(
            uid = "profile",
            name = "Provider",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://provider.example/v1",
            model = "model"
        )

        val turns = ContextBuilder().build(
            userMessages = listOf(
                MessageV2(content = "What is the latest album of NMIXX?", platformType = null),
                MessageV2(content = "Where's the result?", platformType = null)
            ),
            assistantMessages = listOf(
                listOf(MessageV2(content = "", thoughts = "I searched for it.", platformType = platform.uid)),
                listOf(MessageV2(content = "", platformType = platform.uid))
            ),
            platform = platform
        )

        assertEquals(
            listOf("What is the latest album of NMIXX?", "Where's the result?"),
            turns.map { it.userMessage.content }
        )
        assertNull(turns.first().assistantMessage)
    }

    @Test
    fun `history turns compact when exceeding character budget`() {
        val platform = PlatformV2(
            uid = "profile",
            name = "Provider",
            compatibleType = ClientType.OPENAI,
            apiUrl = "https://api.openai.com/v1",
            model = "gpt-4o"
        )

        val policy = ProviderContextPolicy(
            recentTurnWindow = 10,
            historicalImageTurnWindow = 1,
            maxHistoryCharBudget = 50
        )

        val turns = ContextBuilder().build(
            userMessages = listOf(
                MessageV2(content = "Old turn with lots of text that should be trimmed out completely", platformType = null),
                MessageV2(content = "Short turn", platformType = null),
                MessageV2(content = "Current prompt", platformType = null)
            ),
            assistantMessages = listOf(
                listOf(MessageV2(content = "Long reply from old turn", platformType = platform.uid)),
                listOf(MessageV2(content = "Short reply", platformType = platform.uid)),
                emptyList()
            ),
            platform = platform,
            policy = policy
        )

        // Old turn is trimmed by the 50 char budget, leaving only Short turn and Current prompt
        assertEquals(2, turns.size)
        assertEquals("Short turn", turns[0].userMessage.content)
        assertEquals("Current prompt", turns[1].userMessage.content)
        assertTrue(turns[1].isCurrentTurn)
    }

    @Test
    fun `litert lm keeps the full persisted history beyond ten turns`() {
        val platform = localPlatform()
        val userMessages = (0 until 12).map { index ->
            MessageV2(content = "user-$index", platformType = null)
        }
        val assistantMessages = (0 until 12).map { index ->
            listOf(MessageV2(content = "reply-$index", platformType = platform.uid))
        }

        val turns = ContextBuilder().build(userMessages, assistantMessages, platform)

        assertEquals(12, turns.size)
        assertEquals("user-0", turns.first().userMessage.content)
        assertEquals("user-11", turns.last().userMessage.content)
    }

    @Test
    fun `litert lm keeps historical image attachments for rebuild`() {
        val platform = localPlatform()
        val photo = ChatAttachment(
            localFilePath = "/tmp/photo.png",
            preparedFilePath = "/tmp/photo.png",
            displayName = "photo.png",
            mimeType = "image/png",
            sizeBytes = 12
        )

        val turns = ContextBuilder().build(
            userMessages = listOf(
                MessageV2(content = "look", platformType = null, attachments = listOf(photo)),
                MessageV2(content = "follow up", platformType = null)
            ),
            assistantMessages = listOf(
                listOf(MessageV2(content = "a cat", platformType = platform.uid)),
                emptyList()
            ),
            platform = platform
        )

        assertEquals(1, turns.first().userMessage.attachments.size)
        assertTrue(turns.last().userMessage.attachments.isEmpty())
    }

    private fun localPlatform() = PlatformV2(
        uid = "local",
        name = "Local",
        compatibleType = ClientType.LITERT_LM,
        apiUrl = "",
        model = "gemma3-1b-it"
    )
}
