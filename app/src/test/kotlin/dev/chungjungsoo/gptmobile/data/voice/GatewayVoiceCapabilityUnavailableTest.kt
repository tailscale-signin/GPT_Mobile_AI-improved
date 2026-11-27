package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayVoiceCapabilityUnavailableTest {
    @Test fun readinessRetainsTheObservedReasonAndAConcreteRecoveryAction() {
        val disabled = GatewayVoiceCapabilityUnavailable.from("VOICE_DISABLED")
        assertEquals("VOICE_DISABLED", disabled.reason)
        assertTrue(disabled.userMessage.contains("disabled"))
        val warming = GatewayVoiceCapabilityUnavailable.from("VOICE_WARMING")
        assertEquals("VOICE_WARMING", warming.reason)
        assertTrue(warming.userMessage.contains("warming up"))
        val execution = GatewayVoiceCapabilityUnavailable.from("VOICE_CUDA_NOT_EXECUTED")
        assertEquals("VOICE_CUDA_NOT_EXECUTED", execution.reason)
        assertTrue(execution.userMessage.contains("could not run CUDA"))
        for (failure in listOf(disabled, warming, execution)) assertTrue(failure.userMessage.contains("on-device CPU"))
    }

    @Test fun incompatibleSpeechAssetsExplainTheRepairWithoutClaimingSuccessfulExecution() {
        for (reason in listOf("VOICE_PACK_INVALID", "VOICE_PACK_HASH_MISMATCH", "VOICE_FRONTEND_MISMATCH")) {
            val failure = GatewayVoiceCapabilityUnavailable.from(reason)
            assertEquals(reason, failure.reason)
            assertTrue(failure.userMessage.contains("Repair"))
        }
    }

    @Test fun remoteFreeTextCannotEnterTheFailureNotice() {
        for (reason in listOf(null, "VOICE_WARMING private path", "https://private.invalid/token", "unrecognized")) {
            val failure = GatewayVoiceCapabilityUnavailable.from(reason)
            assertEquals("VOICE_UNAVAILABLE", failure.reason)
            assertFalse(failure.userMessage.contains("private"))
        }
    }

    @Test fun wrappedQualificationFailureRetainsItsOriginalReason() {
        val failure = GatewayVoiceCapabilityUnavailable.from("VOICE_WORKER_UNAVAILABLE")
        assertSame(failure, GatewayVoiceCapabilityUnavailable.find(IllegalStateException("wrapper", failure)))
    }
}
