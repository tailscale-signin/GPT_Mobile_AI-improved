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

    @Test fun timedRangesNeverSplitASurrogatePair() {
        val caption = VoiceCaption("Say 👋 hello", "AI reply", true)
        assertEquals(4..5, caption.highlightRange(4..5))
        assertNull(caption.highlightRange(4..4))
        assertNull(caption.highlightRange(5..5))
    }

    @Test fun automaticMovementToEarlierWordsDoesNotStopFollowing() {
        val follow = VoiceCaptionFollowing(800)
        follow.beginAutomaticScroll()
        assertTrue(follow.observe(600, 800))
        assertTrue(follow.observe(300, 800))
        follow.endAutomaticScroll(200)
        assertTrue(follow.observe(200, 800))
        follow.beginAutomaticScroll()
        assertTrue(follow.observe(400, 800))
        follow.endAutomaticScroll(500)
        assertTrue(follow.observe(500, 800))
    }

    @Test fun accessibilityReadingPausesUntilReturningToTheBottom() {
        val follow = VoiceCaptionFollowing(800)
        assertFalse(follow.observe(500, 800))
        assertFalse(follow.observe(500, 900))
        assertFalse(follow.observe(650, 900))
        assertTrue(follow.observe(900, 900))
        assertTrue(follow.observe(900, 1000))
    }

    @Test fun aManualGestureCancelsAutomaticFollowingEvenDuringAnAnimation() {
        val follow = VoiceCaptionFollowing(800)
        follow.beginAutomaticScroll()
        assertTrue(follow.observe(700, 800))
        follow.pause()
        follow.endAutomaticScroll(650)
        assertFalse(follow.observe(650, 800))
        assertFalse(follow.userScroll(400, 800))
        assertTrue(follow.userScroll(800, 800))
    }

    @Test fun replacingTextDoesNotReclaimTheReadersPosition() {
        val follow = VoiceCaptionFollowing(800)
        assertFalse(follow.userScroll(300, 800))
        assertFalse(follow.observe(300, 1200))
        assertFalse(follow.observe(300, 500))
        assertTrue(follow.userScroll(500, 500))
    }
}
