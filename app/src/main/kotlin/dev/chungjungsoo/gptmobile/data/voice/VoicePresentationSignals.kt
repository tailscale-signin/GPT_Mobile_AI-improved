package dev.chungjungsoo.gptmobile.data.voice

/** Fast presentation signals never carry a transcript or trigger draft persistence. */
data class VoiceInputLevel(val level: Float = 0f, val available: Boolean = false)

data class VoiceCaptionCursor(val range: IntRange? = null, val projectedText: String? = null, val timing: VoiceCaptionTiming? = null, val presentationId: Long? = null) {
    /** A clock snapshot from an independently collected flow cannot own a replacement reply. */
    fun belongsTo(presentationId: Long): Boolean = this.presentationId == null || this.presentationId == presentationId
}

internal fun VoiceState.presentationState(): VoiceState = copy(level = 0f, hasAudioLevel = false, spokenRange = null, playbackCaption = null)
