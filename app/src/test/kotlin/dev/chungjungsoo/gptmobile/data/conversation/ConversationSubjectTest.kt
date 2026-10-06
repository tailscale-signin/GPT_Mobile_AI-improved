package dev.chungjungsoo.gptmobile.data.conversation

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatExportFormat
import dev.chungjungsoo.gptmobile.presentation.ui.chat.assistantExportText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSubjectTest {
    @Test fun `first-response title is concise and metadata stays out of exports`() {
        val response = "<!-- chat_subject: Android archive storage -->\n\nThe archived responses are compressed losslessly."
        assertEquals("Android archive storage", ConversationSubject.extract(response))
        assertEquals("The archived responses are compressed losslessly.", ConversationSubject.withoutMetadata(response))
        val message = MessageV2(chatId = 1, platformType = "profile", content = response)
        ChatExportFormat.entries.forEach { format ->
            assertEquals("The archived responses are compressed losslessly.", assistantExportText(message, format))
        }
        assertEquals(response, message.content)
    }

    @Test fun `subject comments inside code or later prose remain original content`() {
        for (response in listOf("```html\n<!-- chat_subject: Example -->\n```", "An example:\n<!-- chat_subject: Example -->")) {
            assertNull(ConversationSubject.extract(response))
            assertEquals(response, ConversationSubject.withoutMetadata(response))
        }
        assertEquals("", ConversationSubject.withoutMetadata("<!-- chat_sub"))
        assertEquals("Answer", ConversationSubject.withoutMetadata("<!-- chat_subject: incomplete\nAnswer"))
    }

    @Test fun `short subjects are never padded and long unicode subjects remain intact`() {
        assertEquals("TLS", ConversationSubject.clean("TLS"))
        assertEquals("Archive storage", ConversationSubject.clean("Title: Archive storage"))
        val title = requireNotNull(ConversationSubject.clean("😀".repeat(40)))
        assertEquals(24, title.codePointCount(0, title.length))
        assertTrue(!title.last().isHighSurrogate())
        assertTrue(requireNotNull(ConversationSubject.clean("A detailed guide to configuring conversation recovery")).length <= 24)
        assertNull(ConversationSubject.clean(" "))
    }
}
