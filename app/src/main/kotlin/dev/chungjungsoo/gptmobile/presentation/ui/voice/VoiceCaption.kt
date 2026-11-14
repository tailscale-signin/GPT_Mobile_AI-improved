package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState

/** The complete current phrase. Speech engines do not provide word alignment for karaoke. */
internal data class VoiceCaption(val text: String, val speaker: String, val live: Boolean) {
    companion object {
        fun from(state: VoiceState): VoiceCaption? {
            val capturing = state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.REVIEWING) || state.hasDraft
            val response = !capturing && state.reply.isNotBlank()
            val text = if (response) state.reply else state.transcript
            if (text.isBlank()) return null
            val live = if (response) {
                state.phase in setOf(VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
            } else {
                state.phase in setOf(VoicePhase.LISTENING, VoicePhase.FINALIZING)
            }
            return VoiceCaption(text, if (response) "AI reply" else "You", live)
        }
    }
}
