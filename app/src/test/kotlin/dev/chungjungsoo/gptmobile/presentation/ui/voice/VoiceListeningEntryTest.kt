package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceListeningEntryTest {
    @Test fun freshRoomWaitsForReadinessThenConsumesItsOpeningAcrossRecreation() {
        val entry = VoiceListeningEntry()
        repeat(3) { assertFalse(entry.take(false, true, VoiceState())) }
        assertFalse(entry.consumed)
        assertTrue(entry.take(true, true, VoiceState()))
        assertFalse(entry.take(true, true, VoiceState()))
        val restored = VoiceListeningEntry(entry.consumed)
        assertFalse(restored.take(true, true, VoiceState()))
    }

    @Test fun savedConversationAndExplicitCancellationCannotBecomeALaterAutomaticOpening() {
        val existing = VoiceListeningEntry()
        assertFalse(existing.take(true, false, VoiceState()))
        assertFalse(existing.take(true, true, VoiceState()))
        val cancelled = VoiceListeningEntry()
        assertFalse(cancelled.take(false, true, VoiceState()))
        cancelled.cancel()
        assertFalse(cancelled.take(true, true, VoiceState()))
    }

    @Test fun restoredDraftsRepliesMuteAndFailuresKeepTheirExistingOwner() {
        val occupied = listOf(
            VoiceState(phase = VoicePhase.STARTING),
            VoiceState(phase = VoicePhase.ERROR),
            VoiceState(muted = true),
            VoiceState(hasDraft = true),
            VoiceState(awaitingReply = true),
            VoiceState(submittedDraftRequestId = "request"),
            VoiceState(transcript = "Keep my words"),
            VoiceState(reply = "Saved answer"),
            VoiceState(notice = "Stopped"),
            VoiceState(retryAtMillis = 100)
        )
        for (state in occupied) {
            val entry = VoiceListeningEntry()
            assertFalse(entry.take(true, true, state))
            assertFalse(entry.take(true, true, VoiceState()))
        }
    }
}
