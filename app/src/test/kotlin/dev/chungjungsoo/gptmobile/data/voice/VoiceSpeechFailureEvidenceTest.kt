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
        assertNull(receipt.frontendMs)
        assertNull(receipt.validationMs)
        assertNull(receipt.cacheEvictions)
        assertNull(receipt.profileFinalizeMs)
    }

    @Test fun newNativeFailureRetainsCurrentPhaseAndCacheCostsWithoutQualifyingAudio() {
        val current = profile().copyOf(29).also {
            it[0] = 3
            it[25] = 4
            it[26] = 2
            it[27] = 1
            it[28] = 7
        }
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("validating", "OUTPUT_INVALID", current), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals(4L, receipt.frontendMs)
        assertEquals(2L, receipt.validationMs)
        assertEquals(1, receipt.cacheEvictions)
        assertEquals(7L, receipt.profileFinalizeMs)
        assertEquals(4L, receipt.diagnostics()["speech_frontend_ms"])
        assertEquals(2L, receipt.diagnostics()["speech_validation_ms"])
        assertEquals(1, receipt.diagnostics()["speech_graph_cache_evictions"])
        assertEquals(7L, receipt.diagnostics()["speech_profile_finalize_ms"])
        assertFalse(receipt.verifiedAudio)
    }

    @Test fun frontendPreparationAndGenericFailuresCannotBorrowPreviousEvidence() {
        assertNull(speechFailureExecutionEvidence(failure("frontend", "FRONTEND_INPUT_TOO_LONG", null), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("preparing", "GRAPH_PREPARATION", null), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("frontend", "FRONTEND_INPUT_INVALID", profile()), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(IllegalStateException("CPU_ONLY"), VoiceSpeechBackend.QNN_HTP, "device"))
    }

    @Test fun typedOpenFailuresDoNotMislabelTheBenchmarkWorkloadOrDisableHardware() {
        for ((stage, code) in listOf("frontend" to "FRONTEND_LOAD", "preparing" to "MODEL_CONFIGURATION", "preparing" to "MODEL_OPEN")) {
            val native = failure(stage, code, null)
            val classified = VoiceSpeechFailureCode.valueOf(native.code)
            assertEquals(stage, native.stage)
            assertEquals(code, native.code)
            assertFalse(classified.isInputFailure)
            assertNull(native.currentEvidence)
            if (classified.isSetupFailure) {
                val capability = VoiceBackendCapability(
                    "device",
                    VoiceSpeechBackend.QNN_HTP,
                    100,
                    failure = classified,
                    failureStage = VoiceSpeechStage.valueOf(stage.uppercase())
                )
                assertFalse(capability.cacheable)
            }
        }
        assertTrue(VoiceSpeechFailureCode.FRONTEND_INPUT_EMPTY.isInputFailure)
        assertTrue(VoiceSpeechFailureCode.FRONTEND_TOKEN_LIMIT.isInputFailure)
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
