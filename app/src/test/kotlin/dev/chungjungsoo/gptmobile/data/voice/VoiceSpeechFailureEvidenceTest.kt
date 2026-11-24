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
        assertNull(receipt.audioRms)
        assertNull(receipt.audioMeasurementAvailable)
        assertFalse(receipt.diagnostics().containsKey("speech_audio_rms"))
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

    @Test fun oversizedOutputKeepsDurationEvidenceWithoutInventingSilentPcm() {
        val snapshot = profile().copyOf(48).also {
            it[0] = 5
            it[7] = 93
            it[17] = 3
            it[29] = 1280L * 1024 * 1024
            it[30] = 1024
            it[31] = 2L * 1024 * 1024 * 1024
            it[32] = 4
            it[33] = 4 // AUDIO_TOO_LONG
            it[34] = 0 // Shape validation happened before the PCM scan.
            it[35] = 2
            it[36] = 93L * 45 * 600
            it[37] = 1
            it[38] = 1
            it[39] = 93
            it[40] = 7
            it[41] = 1
            it[42] = 1
            it[43] = 93L * 45
            it[44] = 45
            it[45] = 45
        }
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("validating", "OUTPUT_INVALID", snapshot), VoiceSpeechBackend.QNN_GPU, "device"))
        assertEquals(false, receipt.audioMeasurementAvailable)
        assertNull(receipt.audioRms)
        assertNull(receipt.audioPeak)
        assertNull(receipt.clippedSamples)
        assertNull(receipt.nonSilentSamples)
        assertNull(receipt.audioFinite)
        assertEquals("AUDIO_TOO_LONG", receipt.validationReason)
        assertEquals(2_511_000L, receipt.outputFrames)
        assertEquals(4185L, receipt.durationSum)
        assertEquals(45L, receipt.durationMin)
        assertEquals(45L, receipt.durationMax)
        assertEquals("BOUNDED_THREE_SHAPES", receipt.cacheAdmission)
        assertEquals(false, receipt.diagnostics()["speech_audio_measurement_available"])
        assertFalse(receipt.diagnostics().containsKey("speech_audio_rms"))
    }

    @Test fun aMeasuredSilentFailureIsDistinctFromUnmeasuredAndLegacyFailures() {
        val snapshot = profile().copyOf(48).also {
            it[0] = 5
            it[19] = 0.0.toRawBits()
            it[20] = 0.0.toRawBits()
            it[22] = 0
            it[33] = 10 // SILENT_PCM
            it[34] = 1
            it[35] = 2
            it[36] = 21 * 600L
            it[37] = 1
            it[38] = 1
            it[39] = 21
            it[40] = 7
            it[41] = 1
            it[42] = 1
            it[43] = 21
            it[44] = 1
            it[45] = 1
            it[47] = 1
        }
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("validating", "OUTPUT_INVALID", snapshot), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals(true, receipt.audioMeasurementAvailable)
        assertEquals(true, receipt.audioFinite)
        assertEquals(0.0, checkNotNull(receipt.audioRms), 0.0)
        assertEquals(0L, receipt.nonSilentSamples)
        assertEquals("SILENT_PCM", receipt.validationReason)
        assertFalse(receipt.verifiedAudio)
        val historical = checkNotNull(speechFailureExecutionEvidence(failure("validating", "CPU_ONLY", profile().also { it[11] = 1 }), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals(true, historical.audioMeasurementAvailable)
        assertEquals(.02, checkNotNull(historical.audioRms), 0.0)
        assertFalse(historical.verifiedAudio)
    }

    @Test fun clippedPcmFailureKeepsMeasuredSignalAndAppendOnlyNativeReasonWithoutQualifying() {
        val snapshot = profile().copyOf(48).also {
            it[0] = 5
            it[20] = 1.02.toRawBits()
            it[21] = 8
            it[33] = 12 // CLIPPED_PCM, appended without changing the v5 payload layout.
            it[34] = 1
            it[35] = 2
            it[36] = 21 * 600L
            it[37] = 1
            it[38] = 1
            it[39] = 21
            it[40] = 7
            it[41] = 1
            it[42] = 1
            it[43] = 21
            it[44] = 1
            it[45] = 1
            it[47] = 1
        }
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("validating", "OUTPUT_INVALID", snapshot), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals("CLIPPED_PCM", receipt.validationReason)
        assertEquals(8L, receipt.clippedSamples)
        assertEquals(1.02, checkNotNull(receipt.audioPeak), 0.0)
        assertEquals(true, receipt.audioMeasurementAvailable)
        assertFalse(receipt.verifiedAudio)
        assertEquals("CLIPPED_PCM", receipt.diagnostics()["speech_validation_reason"])
    }

    @Test fun frontendPreparationAndGenericFailuresCannotBorrowPreviousEvidence() {
        assertNull(speechFailureExecutionEvidence(failure("frontend", "FRONTEND_INPUT_TOO_LONG", null), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("preparing", "GRAPH_PREPARATION", null), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("frontend", "FRONTEND_INPUT_INVALID", profile()), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(failure("preparing", "GRAPH_PREPARATION", profile()), VoiceSpeechBackend.QNN_HTP, "device"))
        assertNull(speechFailureExecutionEvidence(IllegalStateException("CPU_ONLY"), VoiceSpeechBackend.QNN_HTP, "device"))
    }

    @Test fun currentPreparationFailureRetainsElapsedCompilationWithoutInventingExecutionOrActiveResidency() {
        val current = LongArray(33).also {
            it[0] = 4
            it[7] = 21
            it[15] = 7632
            it[16] = 1
            it[17] = 2
            it[18] = 323715810
            it[29] = 1280L * 1024 * 1024
            it[30] = -1
            it[31] = 0
            it[32] = 1
        }
        val receipt = checkNotNull(speechFailureExecutionEvidence(failure("preparing", "GRAPH_PREPARATION", current), VoiceSpeechBackend.QNN_HTP, "device"))
        assertEquals(VoiceSpeechStage.PREPARING, receipt.stage)
        assertEquals("unverified", receipt.effectiveProvider)
        assertEquals(7632L, receipt.currentPrepareMs)
        assertEquals(1280L * 1024 * 1024, receipt.cacheBudgetBytes)
        assertEquals("MEMORY_UNKNOWN", receipt.cacheAdmission)
        assertNull(receipt.activeShapeEstimatedBytes)
        assertNull(receipt.availableMemoryBytes)
        assertFalse(receipt.verifiedAudio)
        assertFalse(receipt.diagnostics().containsKey("speech_cpu_kernels"))
        assertEquals("MEMORY_UNKNOWN", receipt.diagnostics()["speech_cache_admission"])
        current[12] = 1
        assertNull(speechFailureExecutionEvidence(failure("preparing", "GRAPH_PREPARATION", current), VoiceSpeechBackend.QNN_HTP, "device"))
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
