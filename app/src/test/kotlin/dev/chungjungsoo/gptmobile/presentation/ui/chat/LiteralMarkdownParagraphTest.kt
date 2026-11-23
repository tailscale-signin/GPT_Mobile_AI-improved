package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralMarkdownParagraphTest {
    private fun literal(source: String): String? {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val paragraph = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        return literalMarkdownParagraph(source, paragraph)
    }

    @Test fun settledPlainTextIsIndependentOfLaterStreamingParagraphs() {
        val settled = "The source is ready, and the next step is implementation."
        assertEquals(settled, literal(settled))
        assertEquals(settled, literal("$settled\n\nThe next paragraph is still growing"))
    }

    @Test fun richLinksEscapesEntitiesAndSoftBreaksKeepTheFullAnnotationPath() {
        listOf("Read [the source](https://example.com)", "Use **bold** text", "Use `code` here", "One &amp; two", "Escaped \\* text", "First line\nsecond line", "An image ![alt](image.png)", "Math $ x + y $").forEach { source -> assertNull(source, literal(source)) }
    }

    @Test fun lazyPageOffsetsRetainAllTextAndWholeUnicodePairs() {
        val text = "a".repeat(15) + "😀" + "b".repeat(50_000)
        val ranges = paragraphPageRanges(text, 16)
        assertEquals(text, ranges.joinToString("") { text.substring(it.first, it.last + 1) })
        assertTrue(ranges.all { it.last - it.first < 16 && !text[it.first].isLowSurrogate() && !text[it.last].isHighSurrogate() })
    }
}
