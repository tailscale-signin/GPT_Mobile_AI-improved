package dev.chungjungsoo.gptmobile.data.voice

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroFocusPolicyTest {
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
