package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechAudioReferenceTest {
    private val phrase = VoiceSpeechBenchmarkFixture.SHORT.text

    @Test fun observedShortGpuOutputCannotWinDespiteGenuineExecutionAndLowerTime() {
        for (seed in 0L..2L) {
            val report = run(seed) { backend, _ -> if (backend == VoiceSpeechBackend.QNN_GPU) 43 else 98 }
            val gpu = report.results.single { it.requestedBackend == VoiceSpeechBackend.QNN_GPU }
            assertTrue(gpu.executionVerified)
            assertEquals(VoiceSpeechBenchmarkBackendState.COMPLETE, gpu.state)
            val comparison = report.audioReference(gpu).comparison
            assertEquals(VoiceSpeechAudioReferenceStatus.MISMATCH, comparison.status)
            assertEquals(VoiceSpeechAudioReferenceReason.DURATION_MISMATCH, comparison.reason)
            assertEquals(2450.0, comparison.referenceAudioMs!!, .001)
            assertEquals(1075.0, comparison.candidateAudioMs!!, .001)
            assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
            assertEquals(VoiceSpeechBenchmarkOutcome.PARTIAL, report.outcome)
            assertFalse(gpu in report.qualifiedResults())
            assertEquals(report, speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
        }
    }

    @Test fun nearReferenceDurationCanQualifyAcrossAllBackendOrders() {
        for (seed in 0L..2L) {
            val report = run(seed) { backend, _ -> if (backend == VoiceSpeechBackend.QNN_GPU) 97 else 98 }
            assertEquals(VoiceSpeechBackend.QNN_GPU, report.recommendation())
            assertEquals(VoiceSpeechAudioReferenceStatus.CONSISTENT, report.audioReference(report.results[1]).comparison.status)
        }
    }

    @Test fun equalFixtureTotalsCannotHideOppositeChunkDurationErrors() {
        val medium = VoiceSpeechWorkload.phrases(VoiceSpeechBenchmarkFixture.MEDIUM)
        assertEquals(2, medium.size)
        val report = run { backend, text ->
            if (backend != VoiceSpeechBackend.QNN_GPU) {
                98
            } else {
                when (text) {
                    medium[0] -> 49
                    medium[1] -> 147
                    else -> 98
                }
            }
        }
        val cpu = report.results[0].samples.first { it.fixture == VoiceSpeechBenchmarkFixture.MEDIUM }
        val gpu = report.results[1].samples.first { it.fixture == VoiceSpeechBenchmarkFixture.MEDIUM }
        assertEquals(cpu.audioMs, gpu.audioMs, .001)
        assertEquals(VoiceSpeechAudioReferenceReason.DURATION_MISMATCH, report.audioReference(report.results[1]).comparison.reason)
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
    }

    @Test fun numericalSignalAndTokenTimingCorruptionHaveDistinctReasons() {
        val reference = speechReferenceFixture(phrase)
        assertEquals(VoiceSpeechAudioReferenceReason.SIGNAL_LEVEL_MISMATCH, VoiceSpeechAudioReference.compare(reference, reference.copy(rms = .4, peak = .8)).reason)
        assertEquals(VoiceSpeechAudioReferenceReason.CLIPPING_MISMATCH, VoiceSpeechAudioReference.compare(reference, reference.copy(clippedSamples = 600)).reason)
        assertEquals(VoiceSpeechAudioReferenceReason.SIGNAL_ACTIVITY_MISMATCH, VoiceSpeechAudioReference.compare(reference, reference.copy(nonSilentSamples = 600)).reason)
        assertEquals(VoiceSpeechAudioReferenceReason.TOKEN_TIMING_MISMATCH, VoiceSpeechAudioReference.compare(reference, reference.copy(tokenDurations = reference.tokenDurations.reversed())).reason)
        assertEquals(VoiceSpeechAudioReferenceReason.INPUT_MISMATCH, VoiceSpeechAudioReference.compare(reference, reference.copy(inputSha256 = "f".repeat(64))).reason)
        assertEquals(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReference.compare(reference, reference.copy(rms = 0.0, nonSilentSamples = 0)).status)
    }

    @Test fun missingBaselineOrLegacyMeasurementsNeverQualifyAndExportsExcludeWaveforms() {
        val report = run { _, _ -> 98 }
        val gpuOnly = report.copy(configuration = report.configuration.copy(backends = listOf(VoiceSpeechBackend.QNN_GPU)), results = listOf(report.results[1]))
        assertTrue(gpuOnly.results.single().executionVerified)
        assertNull(gpuOnly.recommendation())
        val missing = report.copy(results = report.results.map { result -> result.copy(samples = result.samples.map { sample -> sample.copy(chunks = sample.chunks.map { it.copy(audioObservation = null) }) }) })
        assertNull(missing.recommendation())
        assertTrue(missing.qualifiedResults().isEmpty())
        val encoded = voiceSpeechBenchmarkExport(report)
        assertFalse(encoded.contains(phrase))
        assertFalse(encoded.contains("localDeviceKey"))
        assertFalse(encoded.contains("\"pcm\""))
        val restored = speechBenchmarkReportFromJson(speechBenchmarkJson(missing, local = true))!!
        assertNull(restored.recommendation())
        val manipulated = JsonObject(speechBenchmarkJson(missing, local = true) + ("recommendedBackend" to JsonNull))
        assertNull(speechBenchmarkReportFromJson(manipulated)!!.recommendation())
    }

    @Test fun cancelledOrFailedSuiteRetainsComparisonsButCannotRank() {
        val report = run { _, _ -> 98 }
        assertNull(report.copy(state = VoiceSpeechBenchmarkRunState.CANCELLED).recommendation())
        assertNull(report.copy(state = VoiceSpeechBenchmarkRunState.INTERRUPTED).recommendation())
        val failedCpu = report.copy(results = report.results.map { if (it.requestedBackend == VoiceSpeechBackend.CPU) it.copy(state = VoiceSpeechBenchmarkBackendState.FAILED, failure = VoiceSpeechFailureCode.EXECUTION_FAILED) else it })
        assertNull(failedCpu.recommendation())
    }

    private fun run(seed: Long = 0, units: (VoiceSpeechBackend, String) -> Int): VoiceSpeechBenchmarkReport {
        var clock = 0L
        val configuration = VoiceSpeechBenchmarkConfiguration(VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU), KokoroVoice.HEART, 4, 1.25f, "a".repeat(64), "runtime", 149, 37, "b".repeat(64), backendOrderSeed = seed)
        return VoiceSpeechBenchmarkRunner(
            create = { backend ->
                clock += 10_000_000
                object : VoicePhraseEngine {
                    override val executionEvidence = VoiceSpeechExecutionEvidence(backend, VoiceSpeechBenchmarkBackendResult.provider(backend), "a".repeat(64), "runtime", "device", VoiceSpeechStage.READY, acceleratorKernels = if (backend == VoiceSpeechBackend.CPU) 0 else 40, cpuKernels = 2200, tokenCount = 43, verifiedAudio = true, observedAtMillis = 1, profileAvailable = true)
                    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                        clock += if (backend == VoiceSpeechBackend.CPU) 100_000_000 else 30_000_000
                        val count = units(backend, text)
                        return VoicePhraseAudio(FloatArray(count * 600) { .1f }, LongArray(43) { if (it == 0) count - 42L else 1L }, intArrayOf(1, 2))
                    }
                    override fun resetCancellation() = Unit
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            },
            thermalStatus = { 0 },
            nanoTime = { clock },
            wallTime = { 1000 }
        ).run(VoiceSpeechBenchmarkReport(1, configuration)) {}
    }
}
