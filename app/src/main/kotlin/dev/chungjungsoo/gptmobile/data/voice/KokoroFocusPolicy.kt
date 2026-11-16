package dev.chungjungsoo.gptmobile.data.voice

import android.media.AudioManager

/**
 * Only permanent audio-focus loss ends playback immediately. A transient loss pauses the
 * output track and resumes on focus gain; a duck request lowers volume without discarding PCM.
 */
internal enum class KokoroFocusAction { RESTORE, PAUSE, DUCK, STOP, IGNORE }

internal const val KOKORO_FOCUS_PAUSE_MAX_MS = 15_000L

internal fun kokoroFocusAction(change: Int): KokoroFocusAction = when (change) {
    AudioManager.AUDIOFOCUS_GAIN -> KokoroFocusAction.RESTORE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> KokoroFocusAction.PAUSE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> KokoroFocusAction.DUCK
    AudioManager.AUDIOFOCUS_LOSS -> KokoroFocusAction.STOP
    else -> KokoroFocusAction.IGNORE
}
