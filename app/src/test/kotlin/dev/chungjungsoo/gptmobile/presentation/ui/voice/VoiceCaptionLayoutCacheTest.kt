package dev.chungjungsoo.gptmobile.presentation.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptionLayoutCacheTest {
    @Test fun appendingShapesOnlyTheMutableTailAndCorrectionsRebuild() {
        val cache = VoiceCaptionLayoutCache()
        val measured = mutableListOf<String>()
        fun measure(text: String): List<VoiceCaptionLine> {
            measured += text
            return text.indices.step(4).map { VoiceCaptionLine(it, minOf(it + 4, text.length)) }
        }
        val original = "abcdefghijklmnop"
        assertEquals(4, cache.update(original, ::measure).size)
        val appended = cache.update(original + "qrst", ::measure)
        assertEquals("ijklmnopqrst", measured.last())
        assertEquals(listOf(0, 4, 8, 12, 16), appended.map { it.start })
        assertEquals(3, appended.lineForOffset(15))
        cache.update("Replacement text", ::measure)
        assertEquals("Replacement text", measured.last())
    }

    @Test fun partialWordsAndSupplementaryCharactersRemainExact() {
        val cache = VoiceCaptionWordCache()
        val prefix = "This is a progressively grow"
        cache.update(VoiceCaption(prefix, "AI reply", true))
        val caption = VoiceCaption(prefix + "ing word 👋 and another", "AI reply", true)
        assertEquals(caption.wordRanges(), cache.update(caption))
        val revised = caption.copy(text = "Corrected 👋 reply")
        assertEquals(revised.wordRanges(), cache.update(revised))
        assertTrue(cache.update(revised).all { it.last < revised.text.length })
    }

    @Test fun bidirectionalParagraphsRetainTheirFullShapingContext() {
        val cache = VoiceCaptionLayoutCache()
        val measured = mutableListOf<String>()
        fun measure(text: String): List<VoiceCaptionLine> {
            measured += text
            return text.indices.step(4).map { VoiceCaptionLine(it, minOf(it + 4, text.length)) }
        }
        val text = "مرحبا this is a long bilingual reply"
        cache.update(text, ::measure)
        cache.update("$text with more words", ::measure)
        assertEquals("$text with more words", measured.last())
    }
}
