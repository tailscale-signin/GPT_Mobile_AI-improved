package dev.chungjungsoo.gptmobile.data.voice

import android.media.AudioManager

/**
 * Only permanent audio-focus loss ends playback immediately. A transient loss pauses the
 * output track and resumes on focus gain; a duck request lowers volume without discarding PCM.
 */
internal enum class KokoroFocusAction { RESTORE, PAUSE, DUCK, STOP, IGNORE }

internal const val KOKORO_FOCUS_PAUSE_MAX_MS = 15_000L

/** Give Android a short route-settling window for an immediate denial in normal mode. */
internal fun kokoroFocusRetryDelay(attempt: Int, mode: Int?): Long? = when {
    attempt == 1 && mode == AudioManager.MODE_NORMAL -> 900L
    attempt == 1 -> 250L
    else -> null
}

internal data class KokoroFocusState(val paused: Boolean = false, val ducked: Boolean = false, val pauseStartedAt: Long? = null) {
    fun transition(action: KokoroFocusAction, now: Long): KokoroFocusState = when (action) {
        KokoroFocusAction.RESTORE -> KokoroFocusState()
        KokoroFocusAction.PAUSE -> copy(paused = true, pauseStartedAt = pauseStartedAt ?: now)
        KokoroFocusAction.DUCK -> copy(ducked = true)
        else -> this
    }

    fun resumeOnGain(primed: Boolean): Boolean = paused && primed
    fun pauseExpired(now: Long): Boolean = paused && pauseStartedAt?.let { now - it >= KOKORO_FOCUS_PAUSE_MAX_MS } == true
    fun outputLevel(level: Float): Float = if (paused) {
        0f
    } else if (ducked) {
        level * 0.2f
    } else {
        level
    }
}

internal fun kokoroFocusAction(change: Int): KokoroFocusAction = when (change) {
    AudioManager.AUDIOFOCUS_GAIN -> KokoroFocusAction.RESTORE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> KokoroFocusAction.PAUSE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> KokoroFocusAction.DUCK
    AudioManager.AUDIOFOCUS_LOSS -> KokoroFocusAction.STOP
    else -> KokoroFocusAction.IGNORE
}
