package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.TimedKokoro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechFailureEvidenceTest {
    private fun failure(stage: String, code: String, snapshot: LongArray?): TimedKokoro.Failure =
        TimedKokoro.Failure::class.java.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            stage, code, 4, 21, "qnn-htp", 87, 128, 510, "stage=selected;type=2,vendor=20803,id=0", snapshot
        ) as TimedKokoro.Failure

    private fun profile() = longArrayOf(
        2, 0, 419, 0, 1950, 45, 32, 21, 2, 20803, 0, 0,
        1, 9, 0, 45, 1, 2, 17000000, 0.02.toRawBits(), 0.3.toRawBits(), 0, 1200, 0, 1
    )

    @Test fun cpuOnlyRetainsTheFailedCallProfileWithoutQualifyingAudio() {
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("validating", "CPU_ONLY", profile()), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals(VoiceSpeechBackend.QNN_HTP, receipt.selectedBackend)
        assertEquals("cpu", receipt.effectiveProvider)
        assertEquals("qnn-htp", receipt.failedProvider)
        assertEquals(VoiceSpeechStage.VALIDATING, receipt.stage)
        assertEquals(419, receipt.cpuKernels)
        assertEquals(0, receipt.acceleratorKernels)
        assertEquals(9L, receipt.profileId)
        assertEquals(87, receipt.failedInputBytes)
        assertEquals(0.02, checkNotNull(receipt.audioRms), 0.0)
        assertFalse(receipt.verifiedAudio)
        assertTrue(receipt.diagnostics().containsKey("speech_cpu_kernels"))
    }

    @Test fun frontendPreparationAndGenericFailuresCannotBorrowPreviousEvidence() {
        assertNull(speechFailureExecutionEvidence(failure("frontend", "FRONTEND_INPUT_TOO_LONG", null), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("preparing", "GRAPH_PREPARATION", null), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("frontend", "FRONTEND_INPUT_INVALID", profile()), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(IllegalStateException("CPU_ONLY"), VoiceSpeechBackend.QNN_HTP, "device"))
    }

    @Test fun unavailableFailedProfileDoesNotClaimAnExecutedProcessor() {
        val unknown = profile().also {
            it[2] = 0
            it[12] = 0
        }
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("synthesis", "EXECUTION_FAILED", unknown), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals("unverified", receipt.effectiveProvider)
        assertFalse(receipt.diagnostics().containsKey("speech_cpu_kernels"))
        assertFalse(receipt.verifiedAudio)
    }
}
