package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMeasuredBackendPolicyTest {
    private val now = 1_800_000_000_000L
    private fun receipt(backend: VoiceSpeechBackend, ms: Long, verified: Boolean = true) =
        VoiceBackendCapability("key", backend, now, listOf(ms, ms, ms), verified)

    @Test fun noBaselineOrOnlyProviderRegistrationKeepsCpu() {
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(emptyList(), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.QNN_GPU, 10)), now))
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.CPU, 100), receipt(VoiceSpeechBackend.QNN_GPU, 10, false)), now))
    }

    @Test fun measuredCompleteSpeechCostChoosesFastestVerifiedAccelerator() {
        assertEquals(
            VoiceSpeechBackend.QNN_GPU,
            VoiceMeasuredBackendPolicy.choose(
                listOf(
                    receipt(VoiceSpeechBackend.CPU, 100),
                    receipt(VoiceSpeechBackend.QNN_GPU, 60),
                    receipt(VoiceSpeechBackend.QNN_HTP, 80)
                ),
                now
            )
        )
        assertEquals(
            VoiceSpeechBackend.CPU,
            VoiceMeasuredBackendPolicy.choose(
                listOf(
                    receipt(VoiceSpeechBackend.CPU, 60),
                    receipt(VoiceSpeechBackend.QNN_GPU, 100),
                    receipt(VoiceSpeechBackend.QNN_HTP, 80)
                ),
                now
            )
        )
    }

    @Test fun noisyAdvantageDoesNotSwitchEngines() {
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.CPU, 100), receipt(VoiceSpeechBackend.QNN_HTP, 91)), now))
        assertEquals(VoiceSpeechBackend.QNN_HTP, VoiceMeasuredBackendPolicy.choose(listOf(receipt(VoiceSpeechBackend.CPU, 100), receipt(VoiceSpeechBackend.QNN_HTP, 90)), now))
    }

    @Test fun failuresExpiredMeasurementsAndClockRollbackCannotQualify() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val fast = receipt(VoiceSpeechBackend.QNN_GPU, 10)
        for (invalid in listOf(
            fast.copy(failure = VoiceSpeechFailureCode.CPU_ONLY),
            fast.copy(observedAtMillis = now + 1),
            fast.copy(observedAtMillis = now - 8 * 24 * 60 * 60_000L),
            fast.copy(sampleMs = listOf(10)),
            fast.copy(sampleMs = listOf(10, 10)),
            fast.copy(sampleMs = listOf(10, 10, 10, 10)),
            fast.copy(sampleMs = listOf(0, 10, 10)),
            fast.copy(sampleMs = listOf(10, 300_001, 10))
        )) {
            assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, invalid), now))
        }
    }

    @Test fun negativeReceiptsExpireSooner() {
        val failure = receipt(VoiceSpeechBackend.QNN_HTP, 10).copy(failure = VoiceSpeechFailureCode.GRAPH_PARTITION)
        assertTrue(failure.current(now + 6 * 60 * 60_000L))
        assertFalse(failure.current(now + 6 * 60 * 60_000L + 1))
        assertTrue(failure.copy(failure = null).current(now + 24 * 60 * 60_000L))
    }

    @Test fun threeShapeComparisonIncludesColdPreparationAndEveryShapeCost() {
        assertEquals(VoiceSpeechBenchmarkFixture.entries.size, VoiceMeasuredBackendPolicy.FIXTURE_COUNT)
        val cpu = receipt(VoiceSpeechBackend.CPU, 100).copy(sampleMs = listOf(100, 100, 100))
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 10).copy(sampleMs = listOf(1000, 10, 10))
        assertEquals(340L, gpu.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun legacyCpuPairCannotRankCurrentThreeFixtureGpuReceipt() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 400).copy(sampleMs = listOf(400, 400))
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 200).copy(sampleMs = listOf(180, 200, 220))
        assertNull(cpu.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun legacyGpuPairCannotRankAgainstCurrentThreeFixtureCpuReceipt() {
        val cpu = receipt(VoiceSpeechBackend.CPU, 100)
        val gpu = receipt(VoiceSpeechBackend.QNN_GPU, 10).copy(sampleMs = listOf(10, 10))
        assertNull(gpu.scoreMs)
        assertEquals(VoiceSpeechBackend.CPU, VoiceMeasuredBackendPolicy.choose(listOf(cpu, gpu), now))
    }

    @Test fun diagnosticEvidencePreservesFailedAndEffectiveProvidersWithoutRawText() {
        val evidence = VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.QNN_HTP, "cpu", "model", "runtime", "device",
            VoiceSpeechStage.CPU_FALLBACK, VoiceSpeechFailureCode.GRAPH_PARTITION, observedAtMillis = now,
            nativeCode = 11, failureStage = VoiceSpeechStage.PREPARING, failedProvider = "qnn-htp", failedTokenCount = 19
        )
        val fields = evidence.diagnostics()
        assertEquals("QNN_HTP", fields["speech_backend_selected"])
        assertEquals("cpu", fields["speech_backend_effective"])
        assertEquals("qnn-htp", fields["speech_failed_provider"])
        assertEquals(19, fields["speech_failed_token_count"])
        assertEquals("PREPARING", fields["speech_failure_stage"])
        assertEquals("first_inference_of_exact_graph_shape", fields["speech_profile_scope"])
        assertFalse(fields.keys.any { it.contains("text") || it.contains("message") })
    }
}
