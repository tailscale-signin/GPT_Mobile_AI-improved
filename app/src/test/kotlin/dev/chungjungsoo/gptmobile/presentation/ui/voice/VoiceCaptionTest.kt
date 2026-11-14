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
}
