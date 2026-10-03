package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.intellij.markdown.MarkdownTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownStreamingFadeTest {
    @Test
    fun `streaming fade includes punctuation emitted as dedicated markdown tokens`() {
        val rendered = listOf(
            MarkdownTokenTypes.LPAREN to "(",
            MarkdownTokenTypes.RPAREN to ")",
            MarkdownTokenTypes.TEXT to "/",
            MarkdownTokenTypes.COLON to ":",
            MarkdownTokenTypes.TEXT to ";",
            MarkdownTokenTypes.EXCLAMATION_MARK to "!",
            MarkdownTokenTypes.TEXT to "?",
            MarkdownTokenTypes.RBRACKET to "]",
            MarkdownTokenTypes.LBRACKET to "[",
            MarkdownTokenTypes.DOUBLE_QUOTE to "\""
        ).joinToString(separator = "") { (type, rawText) ->
            streamVisibleMarkdownTokenText(type, rawText).orEmpty()
        }

        assertEquals("()/:;!?][\"", rendered)
    }
}
