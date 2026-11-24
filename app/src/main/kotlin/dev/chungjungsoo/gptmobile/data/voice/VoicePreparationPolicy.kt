package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoicePreparation { NONE, ASSETS, MODEL }

/** The microphone takes the native gate first. Installed assets can be read on the synthesis worker during capture. */
internal object VoicePreparationPolicy {
    fun stage(phase: VoicePhase, awaitingReply: Boolean, hasDraft: Boolean, local: Boolean, admitted: Boolean, openingMicrophone: Boolean): VoicePreparation {
        if (!local) return if (admitted && awaitingReply && phase == VoicePhase.THINKING) VoicePreparation.MODEL else VoicePreparation.NONE
        return when (phase) {
            VoicePhase.PAUSED -> if (openingMicrophone) VoicePreparation.ASSETS else VoicePreparation.MODEL
            VoicePhase.STARTING -> VoicePreparation.ASSETS
            VoicePhase.LISTENING, VoicePhase.FINALIZING -> VoicePreparation.MODEL
            VoicePhase.REVIEWING -> if (hasDraft) VoicePreparation.MODEL else VoicePreparation.NONE
            VoicePhase.THINKING -> if (awaitingReply) VoicePreparation.MODEL else VoicePreparation.NONE
            else -> VoicePreparation.NONE
        }
    }
}
