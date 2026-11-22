package dev.chungjungsoo.gptmobile.data.voice

/** An observed output failure. Only a throwable caught by the producing backend is a cause. */
data class VoicePlaybackFailure(
    val message: String,
    val cause: Throwable? = null,
    val code: String? = null,
    val sourceProvider: String? = null
)
