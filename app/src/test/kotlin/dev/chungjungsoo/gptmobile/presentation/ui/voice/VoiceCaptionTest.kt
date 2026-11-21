package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptionTest {
    @Test fun longRepliesRetainTheirEntireEndingAndUnicode() {
        val reply = "A complete phrase with punctuation. ".repeat(500) + "The important ending: 你好 👋"
        for (phase in listOf(VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.PAUSED)) {
            val caption = requireNotNull(VoiceCaption.from(VoiceState(phase = phase, transcript = "My question", reply = reply)))
            assertEquals(reply, caption.text)
            assertEquals("AI reply", caption.speaker)
            assertEquals(phase != VoicePhase.PAUSED, caption.live)
        }
    }

    @Test fun aNewCaptureShowsTheCurrentWordsInsteadOfThePreviousAnswer() {
        val state = VoiceState(phase = VoicePhase.LISTENING, transcript = "My next question", reply = "The previous answer")
        val caption = requireNotNull(VoiceCaption.from(state))
        assertEquals("My next question", caption.text)
        assertEquals("You", caption.speaker)
        assertTrue(caption.live)
    }

    @Test fun anInterruptedDraftStaysAvailableForReview() {
        val state = VoiceState(phase = VoicePhase.ERROR, transcript = "Keep these words", reply = "An old reply", hasDraft = true)
        val caption = requireNotNull(VoiceCaption.from(state))
        assertEquals("Keep these words", caption.text)
        assertFalse(caption.live)
    }

    @Test fun emptyRecognitionDoesNotResurfaceTheLastReply() {
        assertNull(VoiceCaption.from(VoiceState(phase = VoicePhase.LISTENING, reply = "An old answer")))
    }

    @Test fun highlightingRequiresAnObservedRangeInsideTheReply() {
        val caption = VoiceCaption("An actual reply", "AI reply", true)
        assertEquals(3..8, caption.highlightRange(3..8))
        assertNull(caption.highlightRange(null))
        assertNull(caption.highlightRange(-1..3))
        assertNull(caption.highlightRange(3..100))
        assertNull(caption.highlightRange(4..3))
        assertNull(caption.copy(speaker = "You").highlightRange(3..8))
    }

    @Test fun phraseCallbacksStillIlluminateOnlyOneWord() {
        val caption = VoiceCaption("First, second and third.", "AI reply", true)
        assertEquals(0..4, caption.highlightRange(0..12))
        assertEquals(7..12, caption.highlightRange(7..22))
        assertEquals(18..22, caption.highlightRange(18..22))
        assertNull(caption.highlightRange(5..5))
    }

    @Test fun lateRangesInLongRepliesKeepSupplementaryLetters() {
        val prefix = "testing ".repeat(1000)
        val word = String(Character.toChars(0x10400))
        val caption = VoiceCaption(prefix + word + "!", "AI reply", true)
        val words = caption.wordRanges()
        assertEquals(prefix.length until prefix.length + word.length, caption.highlightRange(prefix.length until prefix.length + word.length, words))
        assertNull(caption.highlightRange(caption.text.lastIndex..caption.text.lastIndex, words))
    }

    @Test fun timedRangesNeverSplitASurrogatePair() {
        val caption = VoiceCaption("Say 👋 hello", "AI reply", true)
        assertEquals(4..5, caption.highlightRange(4..5))
        assertNull(caption.highlightRange(4..4))
        assertNull(caption.highlightRange(5..5))
    }

    @Test fun captionsHaveFiveSymmetricTypeAndOpacityLevels() {
        assertEquals(listOf(1f, 1.5f, 2f, 1.5f, 1f), (-2..2).map(::captionLineScale))
        assertEquals(listOf(.5f, .75f, 1f, .75f, .5f), (-2..2).map(::captionLineAlpha))
    }

    @Test fun manualReadingHoldsThroughNewTextAndPlaybackUntilReturningToTheEnd() {
        val following = VoiceCaptionReader()
        assertEquals(7f, following.position(7, 40))
        val reading = following.scroll(4.5f, 40)
        assertFalse(reading.following)
        assertEquals(4.5f, reading.position(13, 50))
        assertEquals(2f, reading.position(1, 2))
        val resumed = reading.scroll(50f, 50)
        assertTrue(resumed.following)
        assertEquals(13f, resumed.position(13, 50))
    }
}
