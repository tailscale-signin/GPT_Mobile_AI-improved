package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState

/** A new room may request its first capture once; readiness changes cannot undo a user action. */
internal class VoiceListeningEntry(var consumed: Boolean = false) {
    fun take(ready: Boolean, newConversation: Boolean, state: VoiceState): Boolean {
        if (consumed || !ready) return false
        consumed = true
        return newConversation &&
            state.phase == VoicePhase.PAUSED &&
            !state.muted &&
            !state.hasDraft &&
            !state.awaitingReply &&
            state.submittedDraftRequestId == null &&
            state.transcript.isBlank() &&
            state.reply.isBlank() &&
            state.notice == null &&
            state.retryAtMillis == 0L
    }

    fun cancel() {
        consumed = true
    }
}
