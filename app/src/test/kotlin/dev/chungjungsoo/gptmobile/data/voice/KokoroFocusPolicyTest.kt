package dev.chungjungsoo.gptmobile.data.voice

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroFocusPolicyTest {
    @Test fun repeatedFocusLossDoesNotExtendThePauseDeadline() {
        val paused = KokoroFocusState().transition(KokoroFocusAction.PAUSE, 1000)
            .transition(KokoroFocusAction.PAUSE, 14_000)
        assertEquals(1000L, paused.pauseStartedAt)
        assertFalse(paused.pauseExpired(15_999))
        assertTrue(paused.pauseExpired(16_000))
        val restored = paused.transition(KokoroFocusAction.RESTORE, 16_001)
        assertFalse(restored.pauseExpired(100_000))
        assertEquals(20_000L, restored.transition(KokoroFocusAction.PAUSE, 20_000).pauseStartedAt)
    }

    @Test fun gainResumesOnlyAnActuallyPrimedPausedTrack() {
        val paused = KokoroFocusState().transition(KokoroFocusAction.PAUSE, 100)
        assertFalse(paused.resumeOnGain(primed = false))
        assertTrue(paused.resumeOnGain(primed = true))
        val ducked = KokoroFocusState().transition(KokoroFocusAction.DUCK, 200)
        assertFalse(ducked.resumeOnGain(primed = true))
        assertEquals(0.1f, ducked.outputLevel(0.5f), 0.001f)
        assertEquals(0f, paused.transition(KokoroFocusAction.DUCK, 300).outputLevel(0.5f), 0.001f)
        assertEquals(0.5f, ducked.transition(KokoroFocusAction.RESTORE, 400).outputLevel(0.5f), 0.001f)
    }

    @Test
    fun transientAndDuckingFocusEventsDoNotMapToPermanentStop() {
        assertEquals(KokoroFocusAction.PAUSE, kokoroFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(KokoroFocusAction.DUCK, kokoroFocusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(KokoroFocusAction.RESTORE, kokoroFocusAction(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(KokoroFocusAction.STOP, kokoroFocusAction(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(KokoroFocusAction.IGNORE, kokoroFocusAction(0))
        assertEquals(15_000L, KOKORO_FOCUS_PAUSE_MAX_MS)
    }
}
