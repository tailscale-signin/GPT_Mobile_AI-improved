package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceBackendQualificationTest {
    private fun evidence(backend: VoiceSpeechBackend = VoiceSpeechBackend.QNN_HTP) = VoiceSpeechExecutionEvidence(
        backend, VoiceSpeechBenchmarkBackendResult.provider(backend), "model", "runtime", "device", VoiceSpeechStage.READY,
        acceleratorKernels = if (backend == VoiceSpeechBackend.CPU) 0 else 39,
        verifiedAudio = true, observedAtMillis = 1,
        profileAvailable = backend != VoiceSpeechBackend.CPU, nonSilentSamples = 100, tokenCount = 43
    )

    @Test fun completeProductionWorkloadUsesActiveVoiceRateAndEveryPreparationCost() {
        val groups = VoiceSpeechWorkload.calibrationFixtures
        val phrases = mutableListOf<String>()
        var clock = 500_000_000L
        val measured = measureVoiceQualification(groups, VoiceSpeechBackend.QNN_HTP, 21, 1.25f, 0, { clock }, {}, 0, { 0 }, ::speechReferenceFixture) { phrase, speaker, rate ->
            assertEquals(21, speaker)
            assertEquals(1.25f, rate)
            phrases += phrase
            clock += 25_000_000L
            evidence()
        }
        val samples = measured.sampleMs
        assertEquals(6, samples.size)
        assertEquals(groups.flatten(), phrases)
        assertEquals(groups.take(3), groups.drop(3))
        assertTrue(phrases.all { it.toByteArray().size <= 128 })
        assertEquals(500L + phrases.size * 25L, samples.sum())
        assertEquals(500L + groups.first().size * 25L, samples.first())
        assertTrue(measured.rankingEligible)
        assertEquals(1 + 2 * phrases.size, measured.thermalStatuses.size)
    }

    @Test fun oversizedLaterGroupRejectsBeforeAnyNativeWork() {
        val groups = List(6) { index -> listOf(if (index == 5) "a".repeat(129) else "the voice is ready.") }
        var calls = 0
        try {
            measureVoiceQualification(groups, VoiceSpeechBackend.CPU, 3, 1f, 0, { 1_000_000L }, {}) { _, _, _ ->
                calls++
                evidence(VoiceSpeechBackend.CPU)
            }
            fail("Expected invalid workload")
        } catch (error: VoiceSpeechWorkloadFailure) {
            assertEquals("FRONTEND_INPUT_TOO_LONG", error.code)
            assertEquals(129, error.inputBytes)
            assertEquals(128, error.inputByteLimit)
        }
        assertEquals(0, calls)
    }

    @Test fun incompleteInferencePreservesFailureAndNeverReturnsACompleteRanking() {
        val failure = IllegalStateException("GRAPH_PREPARATION")
        var calls = 0
        var clock = 0L
        try {
            measureVoiceQualification(VoiceSpeechWorkload.calibrationFixtures, VoiceSpeechBackend.QNN_HTP, 3, 1f, 0, { clock }, {}) { _, _, _ ->
                calls++
                if (calls == 3) throw failure
                clock += 1_000_000L
                evidence()
            }
            fail("Expected incomplete qualification")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(3, calls)
    }

    @Test fun missingProfileSilentAudioAndWrongProviderNeverQualify() {
        for ((receipt, code) in listOf(
            evidence().copy(profileAvailable = null) to "EXECUTION_EVIDENCE_MISSING",
            evidence().copy(nonSilentSamples = null) to "OUTPUT_INVALID",
            evidence().copy(nonSilentSamples = 0) to "OUTPUT_INVALID",
            evidence().copy(effectiveProvider = "cpu") to "CPU_ONLY",
            evidence().copy(acceleratorKernels = 0) to "CPU_ONLY"
        )) {
            var calls = 0
            try {
                measureVoiceQualification(VoiceSpeechWorkload.calibrationFixtures, VoiceSpeechBackend.QNN_HTP, 3, 1f, 0, { 1_000_000L }, {}) { _, _, _ ->
                    calls++
                    receipt
                }
                fail("Expected rejected evidence")
            } catch (error: IllegalStateException) {
                assertEquals(code, error.message)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationAfterNativeReturnRemainsCancellationWithoutMoreWork() {
        val cancelled = CancellationException("stop")
        var stopped = false
        var calls = 0
        try {
            measureVoiceQualification(VoiceSpeechWorkload.calibrationFixtures, VoiceSpeechBackend.QNN_HTP, 3, 1f, 0, { 1_000_000L }, { if (stopped) throw cancelled }) { _, _, _ ->
                calls++
                stopped = true
                evidence()
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertEquals(1, calls)
    }

    @Test fun completeSupportedExecutionWithUnknownOrTransientWarmThermalsCannotRank() {
        for (bad in listOf(null, 2, 6)) {
            var calls = 0
            var reads = 0
            var clock = 0L
            val measured = measureVoiceQualification(
                VoiceSpeechWorkload.calibrationFixtures, VoiceSpeechBackend.QNN_HTP, 3, 1f,
                0, { clock }, {}, 0, { if (++reads == 4) bad else 0 }
            ) { _, _, _ ->
                calls++
                clock += 1_000_000L
                evidence()
            }
            assertEquals(VoiceSpeechWorkload.calibrationPhrases.size, calls)
            assertEquals(VoiceMeasuredBackendPolicy.FIXTURE_COUNT, measured.sampleMs.size)
            assertTrue(measured.thermalStatuses.contains(bad))
            org.junit.Assert.assertFalse(measured.rankingEligible)
        }
    }
}
