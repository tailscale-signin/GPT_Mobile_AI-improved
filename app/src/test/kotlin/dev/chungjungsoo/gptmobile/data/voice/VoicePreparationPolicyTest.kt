package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePreparationPolicyTest {
    @Test fun aColdVoiceScreenCanPrepareOnlyLocalAssetsBeforeTheRoomAndProfileAreReady() {
        for (phase in VoicePhase.entries) {
            val expected = if (phase == VoicePhase.PAUSED || phase == VoicePhase.STARTING) VoicePreparation.ASSETS else VoicePreparation.NONE
            assertEquals(expected, VoicePreparationPolicy.stage(phase, true, true, true, true, true, turnReady = false))
            assertEquals(VoicePreparation.NONE, VoicePreparationPolicy.stage(phase, true, true, false, true, true, turnReady = false))
        }
    }

    @Test fun onEntryFileWorkYieldsTheNativeGateToTheMicrophone() {
        fun stage(phase: VoicePhase, openingMicrophone: Boolean = true) =
            VoicePreparationPolicy.stage(phase, awaitingReply = false, hasDraft = false, local = true, admitted = false, openingMicrophone = openingMicrophone)
        assertEquals(VoicePreparation.ASSETS, stage(VoicePhase.PAUSED))
        assertEquals(VoicePreparation.ASSETS, stage(VoicePhase.STARTING))
        assertEquals(VoicePreparation.MODEL, stage(VoicePhase.STARTING, openingMicrophone = false))
        assertEquals(VoicePreparation.MODEL, stage(VoicePhase.PAUSED, openingMicrophone = false))
        assertEquals(VoicePreparation.MODEL, stage(VoicePhase.LISTENING))
        assertEquals(VoicePreparation.MODEL, stage(VoicePhase.FINALIZING))
        for (phase in listOf(VoicePhase.PREPARING, VoicePhase.SPEAKING, VoicePhase.CLOSED, VoicePhase.ERROR)) {
            assertEquals(VoicePreparation.NONE, stage(phase))
        }
        for (phase in listOf(VoicePhase.SPEAKING, VoicePhase.CLOSED, VoicePhase.ERROR)) {
            assertEquals(VoicePreparation.NONE, VoicePreparationPolicy.stage(phase, true, true, true, true, false))
        }
    }

    @Test fun localDraftAndRemoteConsentRemainTurnScoped() {
        for (phase in VoicePhase.entries) {
            assertEquals(VoicePreparation.NONE, VoicePreparationPolicy.stage(phase, true, true, false, false, false))
        }
        assertEquals(VoicePreparation.MODEL, VoicePreparationPolicy.stage(VoicePhase.THINKING, true, false, false, true, false))
        assertEquals(VoicePreparation.MODEL, VoicePreparationPolicy.stage(VoicePhase.THINKING, true, false, true, false, false))
        assertEquals(VoicePreparation.NONE, VoicePreparationPolicy.stage(VoicePhase.THINKING, false, false, true, true, false))
        assertEquals(VoicePreparation.NONE, VoicePreparationPolicy.stage(VoicePhase.REVIEWING, false, false, true, false, false))
        assertEquals(VoicePreparation.MODEL, VoicePreparationPolicy.stage(VoicePhase.REVIEWING, false, true, true, false, false))
    }
}
