package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Preparing a short cue is bounded; failure never parks microphone admission or schedules a later sound. */
internal class VoiceMicrophoneCueReadiness {
    private val loaded = CompletableDeferred<Boolean>()

    fun loaded(success: Boolean) {
        loaded.complete(success)
    }

    suspend fun prepare(): Boolean = withTimeoutOrNull(250) { loaded.await() } ?: false
}
