package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.LEGACY_ORDER_NOTICE
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {

    @Test
    fun formatAssistantExport_simpleContent_rendersCorrectly() {
        val message = MessageV2(
            chatId = 1,
            content = "Hello, world!",
            platformType = "openai-main"
        )

        val exported = formatAssistantExport(
            platformName = "OpenAI",
            message = message,
            toolEventsByRun = emptyMap()
        )

        assertTrue(exported.contains("**Assistant (OpenAI):**"))
        assertTrue(exported.contains("Hello, world!"))
    }

    @Test
    fun formatAssistantExport_withTimeline_rendersOrderedContent() {
        val timeline = listOf(
            AssistantTimelineItem(type = AssistantTimelineItemType.THINKING, content = "Let me think about this..."),
            AssistantTimelineItem(type = AssistantTimelineItemType.TEXT, content = "Here is the final answer.")
        )
        val message = MessageV2(
            chatId = 1,
            content = "Here is the final answer.",
            thoughts = "Let me think about this...",
            timeline = timeline,
            platformType = "anthropic-main"
        )

        val exported = formatAssistantExport(
            platformName = "Claude",
            message = message,
            toolEventsByRun = emptyMap()
        )

        assertTrue(exported.contains("**Assistant (Claude):**"))
        assertTrue(exported.contains("<details><summary>Thinking</summary>"))
        assertTrue(exported.contains("Let me think about this..."))
        assertTrue(exported.contains("Here is the final answer."))
    }

    @Test
    fun formatAssistantExport_withLegacyOrderNotice_rendersNoticeBlock() {
        // When thoughts exist, content exists, but timeline is empty (legacy message)
        val message = MessageV2(
            chatId = 1,
            content = "Legacy answer",
            thoughts = "Legacy thoughts",
            timeline = emptyList(),
            platformType = "groq-main"
        )

        val exported = formatAssistantExport(
            platformName = "Groq",
            message = message,
            toolEventsByRun = emptyMap(),
            legacyOrderNotice = LEGACY_ORDER_NOTICE
        )

        assertTrue(exported.contains("> $LEGACY_ORDER_NOTICE"))
        assertTrue(exported.contains("<details><summary>Thinking (order unavailable)</summary>"))
        assertTrue(exported.contains("Legacy thoughts"))
        assertTrue(exported.contains("Legacy answer"))
    }

    @Test
    fun persistableMessages_filtersOutEmptyUnsentDrafts() {
        val user1 = MessageV2(chatId = 1, content = "Question 1", platformType = null)
        val assistant1 = MessageV2(chatId = 1, content = "Answer 1", platformType = "openai-main")
        val emptyAssistant = MessageV2(chatId = 1, content = "", platformType = "groq-main")

        val grouped = ChatViewModel.GroupedMessages(
            userMessages = listOf(user1),
            assistantMessages = listOf(listOf(assistant1, emptyAssistant))
        )

        val persistable = persistableMessages(grouped)
        assertEquals(2, persistable.size)
        assertTrue(persistable.contains(user1))
        assertTrue(persistable.contains(assistant1))
    }
}
