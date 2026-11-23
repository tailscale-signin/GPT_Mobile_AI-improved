package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProtectedTextTest {
    @Test
    fun noEvidencePreservesTheOriginalTextAndAnnotationsObject() {
        val original = AnnotatedString("alex@example.com")
        assertSame(original, privacyProtectedText(original, emptyList(), Color.Yellow))
        assertSame(original, privacyProtectedText(original, listOf("another@example.com"), Color.Yellow))
    }

    @Test
    fun protectionAddsOnlyForegroundAndPreservesTextStylesSelectionAndMetadata() {
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        val paragraph = ParagraphStyle(lineHeight = 24.sp)
        val original = AnnotatedString.Builder("Alice Smith is ready").apply {
            addStyle(bold, 0, 11)
            addStyle(paragraph, 0, length)
            addStringAnnotation("source", "receipt", 0, 11)
        }.toAnnotatedString()

        val result = privacyProtectedText(original, listOf("Alice Smith"), Color.Yellow)

        assertEquals(original.text, result.text)
        assertEquals(original.paragraphStyles, result.paragraphStyles)
        assertEquals(original.getStringAnnotations("source", 0, original.length), result.getStringAnnotations("source", 0, result.length))
        assertEquals(original.spanStyles, result.spanStyles.dropLast(1))
        assertEquals(AnnotatedString.Range(SpanStyle(color = Color.Yellow), 0, 11), result.spanStyles.last())
    }

    @Test
    fun partialUrlHighlightsPreserveUnprotectedLinkColourAndAllTapTargets() {
        val styles = TextLinkStyles(
            style = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)
        )
        val original = AnnotatedString.Builder("Contact Alice Smith here").apply {
            addLink(LinkAnnotation.Url("https://example.com/contact", styles), 8, 24)
        }.toAnnotatedString()

        val result = privacyProtectedText(original, listOf("Alice Smith"), Color.Yellow)
        val links = result.getLinkAnnotations(0, result.length)

        assertEquals(listOf(8 to 19, 19 to 24), links.map { it.start to it.end })
        assertTrue(links.all { (it.item as LinkAnnotation.Url).url == "https://example.com/contact" })
        assertEquals(Color.Yellow, links.first().item.styles?.style?.color)
        assertEquals(Color.Yellow, links.first().item.styles?.pressedStyle?.color)
        assertEquals(TextDecoration.Underline, links.first().item.styles?.style?.textDecoration)
        assertEquals(FontWeight.Bold, links.first().item.styles?.pressedStyle?.fontWeight)
        assertEquals(styles, links.last().item.styles)
    }

    @Test
    fun clickableAttributionKeepsItsListenerAfterPrivacyColourIsApplied() {
        var clicks = 0
        val listener = LinkInteractionListener { clicks++ }
        val original = AnnotatedString.Builder("Alice Smith").apply {
            addLink(LinkAnnotation.Clickable("source", TextLinkStyles(style = SpanStyle(color = Color.Blue)), listener), 0, length)
        }.toAnnotatedString()
        val result = privacyProtectedText(original, listOf("Alice Smith"), Color.Yellow)
        val link = result.getLinkAnnotations(0, result.length).single().item as LinkAnnotation.Clickable

        assertEquals("source", link.tag)
        assertSame(listener, link.linkInteractionListener)
        link.linkInteractionListener?.onClick(link)
        assertEquals(1, clicks)
    }

    @Test
    fun markdownSourceAnnotationsRestrictColourToUnchangedVisibleWords() {
        val source = "Alice and A**li**ce"
        val original = AnnotatedString.Builder("Alice and Alice").apply {
            addStringAnnotation(PRIVACY_VERBATIM_SOURCE, "0:11", 0, 11)
            addStringAnnotation(PRIVACY_VERBATIM_SOURCE, "13:15", 11, 13)
            addStringAnnotation(PRIVACY_VERBATIM_SOURCE, "17:19", 13, 15)
        }.toAnnotatedString()
        val result = privacyProtectedText(original, listOf("Alice"), Color.Yellow, source)

        assertEquals(listOf(AnnotatedString.Range(SpanStyle(color = Color.Yellow), 0, 5)), result.spanStyles)
        assertEquals(original.text, result.text)
        assertSame(original, privacyProtectedText(original, listOf("Alice"), Color.Yellow, "unrelated changed source"))
    }

    @Test
    fun yellowForegroundRemainsReadableAcrossChatAndCodeSurfaces() {
        for (surface in listOf(Color.White, Color.Black, Color(0xFF121212), Color(0xFF777777), Color(0xFFEADDFF))) {
            assertTrue(ProfileAttributionPalette.contrast(privacyProtectedForeground(surface), surface) >= 4.5)
        }
        assertEquals(Color(0xFFFFD740), privacyProtectedForeground(Color.Black))
    }
}
