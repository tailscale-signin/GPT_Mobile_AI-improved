package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphPagesTest {
    @Test
    fun ordinaryParagraphRetainsItsOriginalLayoutInput() {
        val paragraph = AnnotatedString("A short paragraph.")
        assertSame(paragraph, paragraphPages(paragraph).single())
    }

    @Test
    fun longProseRetainsEveryCharacterAndBoundsEachLayout() {
        val paragraph = AnnotatedString(("Molière, Racine, Corneille, La Fontaine. ").repeat(700))
        val pages = paragraphPages(paragraph)
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.length <= PARAGRAPH_PAGE_CHARACTERS })
        assertEquals(paragraph.text, pages.joinToString("") { it.text })
    }

    @Test
    fun annotationsSurvivePageBoundariesAndSurrogatePairsRemainWhole() {
        val source = "a".repeat(15) + "😀" + "b".repeat(40)
        val paragraph = AnnotatedString.Builder(source).apply {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 8, 40)
            addStringAnnotation("source", "retained", 10, 35)
        }.toAnnotatedString()
        val pages = paragraphPages(paragraph, 16)
        assertEquals(source, pages.joinToString("") { it.text })
        assertTrue(pages.all { it.length <= 16 && !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
        assertEquals(32, pages.sumOf { page -> page.spanStyles.sumOf { it.end - it.start } })
        assertEquals(25, pages.sumOf { page -> page.getStringAnnotations("source", 0, page.length).sumOf { it.end - it.start } })
    }
}
