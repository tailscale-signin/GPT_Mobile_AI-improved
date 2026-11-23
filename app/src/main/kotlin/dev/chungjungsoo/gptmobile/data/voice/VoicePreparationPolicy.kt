package dev.chungjungsoo.gptmobile.data.voice

/** Preparation contains no conversation text. Remote engines retain request admission. */
internal object VoicePreparationPolicy {
    fun shouldPrepare(phase: VoicePhase, awaitingReply: Boolean, hasDraft: Boolean, local: Boolean, admitted: Boolean): Boolean =
        if (local) {
            phase == VoicePhase.FINALIZING ||
                phase == VoicePhase.REVIEWING &&
                hasDraft ||
                phase == VoicePhase.THINKING &&
                awaitingReply
        } else {
            admitted && awaitingReply && phase == VoicePhase.THINKING
        }
}
