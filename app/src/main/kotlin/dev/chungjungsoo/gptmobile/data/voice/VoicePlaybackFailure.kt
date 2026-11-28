package dev.chungjungsoo.gptmobile.data.voice

enum class VoicePlaybackFailureStage { PREPARATION, SYNTHESIS, PLAYBACK, INITIALIZATION, CATALOG, AUDIO_FOCUS }

/**
 * An observed output failure. Only a throwable caught by the producing backend is a cause.
 * Requested hardware is kept separately from an execution provider that actually initialized.
 */
data class VoicePlaybackFailure(
    val message: String,
    val cause: Throwable? = null,
    val code: String? = null,
    val sourceProvider: String? = null,
    val requestedBackend: String? = null,
    val stage: VoicePlaybackFailureStage? = null,
    val platformErrorCode: Int? = null,
    val fallbackFailure: VoicePlaybackFailure? = null
) {
    /** Keep both observed causes without treating the fallback as the original producer. */
    fun afterFallback(fallback: VoicePlaybackFailure): VoicePlaybackFailure {
        val terminal = fallback.copy(fallbackFailure = null)
        // Native acceleration can first fall back to CPU, then to Android. Keep
        // those three bounded stages without creating an unbounded cause graph.
        val next = fallbackFailure?.copy(fallbackFailure = terminal) ?: terminal
        return copy(message = fallback.message, fallbackFailure = next)
    }

    val terminalFallback: VoicePlaybackFailure? get() = fallbackFailure?.fallbackFailure ?: fallbackFailure
}
