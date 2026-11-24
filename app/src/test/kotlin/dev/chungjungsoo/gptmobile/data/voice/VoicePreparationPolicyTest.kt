package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePreparationPolicyTest {
    @Test fun localPreparationCanOverlapFinalizationReviewAndAdmission() {
        assertTrue(VoicePreparationPolicy.shouldPrepare(VoicePhase.FINALIZING, false, false, true, false))
        assertTrue(VoicePreparationPolicy.shouldPrepare(VoicePhase.REVIEWING, false, true, true, false))
        assertTrue(VoicePreparationPolicy.shouldPrepare(VoicePhase.THINKING, true, false, true, false))
    }

    @Test fun remotePreparationStillRequiresTheAcceptedReply() {
        for (phase in VoicePhase.entries) {
            assertFalse(VoicePreparationPolicy.shouldPrepare(phase, true, true, false, false))
        }
        assertTrue(VoicePreparationPolicy.shouldPrepare(VoicePhase.THINKING, true, false, false, true))
    }

    @Test fun coldScreenCaptureAndInactiveSessionsDoNotStartModelPreparation() {
        for (phase in listOf(VoicePhase.PAUSED, VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.SPEAKING, VoicePhase.CLOSED, VoicePhase.ERROR)) {
            assertFalse(VoicePreparationPolicy.shouldPrepare(phase, true, true, true, true))
        }
        assertFalse(VoicePreparationPolicy.shouldPrepare(VoicePhase.REVIEWING, false, false, true, false))
        assertFalse(VoicePreparationPolicy.shouldPrepare(VoicePhase.THINKING, false, false, true, true))
    }
}
