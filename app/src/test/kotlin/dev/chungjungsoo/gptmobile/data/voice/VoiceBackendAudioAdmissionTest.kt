package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBackendAudioAdmissionTest {
    private val now = 1_800_000_000_000L
    private val cpu get() = receipt(VoiceSpeechBackend.CPU)
    private val gpu get() = receipt(VoiceSpeechBackend.QNN_GPU)

    @Test fun manualAcceleratorsCannotOpenFromUnknownOrSelfValidatedSupportOnly() {
        for (backend in listOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)) {
            val support = receipt(backend).copy(sampleMs = emptyList(), comparisonId = null, audioObservations = emptyList())
            for (records in listOf(emptyList(), listOf(support), listOf(cpu, support))) {
                val selection = VoiceBackendAudioAdmission.select(backend, records, now)
                assertEquals(VoiceSpeechBackend.CPU, selection.selected)
                assertEquals(backend, selection.denied)
            }
        }
    }

    @Test fun observed1075Versus2450MillisecondMismatchCannotReachManualPlayback() {
        val truncated = gpu.copy(audioObservations = VoiceSpeechWorkload.calibrationPhrases.map { speechReferenceFixture(it, 43) })
        assertEquals(1_075.0, truncated.audioObservations.first().audioMs, 0.0)
        assertEquals(2_450.0, cpu.audioObservations.first().audioMs, 0.0)
        val decision = VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, listOf(cpu, truncated), now)
        assertEquals(VoiceSpeechBackend.CPU, decision.selected)
        assertEquals(VoiceSpeechAudioReferenceStatus.MISMATCH, decision.comparison?.status)
        assertEquals(VoiceSpeechAudioReferenceReason.DURATION_MISMATCH, decision.comparison?.reason)
        val finalized = VoiceBackendAudioAdmission.finalized(listOf(truncated, cpu), now)
        val failed = finalized.single { it.backend == VoiceSpeechBackend.QNN_GPU }
        assertEquals(VoiceSpeechFailureCode.OUTPUT_INVALID, failed.failure)
        assertEquals(VoiceSpeechStage.VALIDATING, failed.failureStage)
        assertEquals(VoiceSpeechBackend.CPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, finalized, now).selected)
    }

    @Test fun freshPairedManualSelectionDoesNotPretendASlowerEngineWinsAuto() {
        val slower = gpu.copy(sampleMs = List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { 200 })
        val records = listOf(slower, cpu)
        assertEquals(VoiceSpeechBackend.QNN_GPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, records, now).selected)
        assertEquals(VoiceSpeechBackend.CPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.AUTO, records, now).selected)
    }

    @Test fun missingStaleFutureOrAmbiguousCpuReferenceCannotAdmitManualGpu() {
        val references = listOf(
            emptyList(),
            listOf(cpu.copy(observedAtMillis = now - 8 * 24 * 60 * 60_000L)),
            listOf(cpu.copy(observedAtMillis = now + 1)),
            listOf(cpu.copy(failure = VoiceSpeechFailureCode.OUTPUT_INVALID)),
            listOf(cpu.copy(audioObservations = cpu.audioObservations.dropLast(1))),
            listOf(cpu, cpu)
        )
        references.forEach {
            assertEquals(VoiceSpeechBackend.CPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, it + gpu, now).selected)
        }
    }

    @Test fun currentRuntimeProtocolVoiceRateAndComparisonIdentityMustAllMatch() {
        val invalid = listOf(
            gpu.copy(protocol = VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL.removeSuffix("-audio-admission-v1")),
            gpu.copy(comparisonId = "d".repeat(32)),
            gpu.copy(comparisonScope = "b".repeat(64)),
            gpu.copy(speakerId = 11),
            gpu.copy(speechRate = 1.25f),
            gpu.copy(speechRate = Float.NaN),
            gpu.copy(verifiedAccelerator = false),
            gpu.copy(observedAtMillis = now - 8 * 24 * 60 * 60_000L),
            gpu.copy(audioObservations = emptyList())
        )
        invalid.forEach {
            assertEquals(VoiceSpeechBackend.CPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, listOf(cpu, it), now).selected)
        }
    }

    @Test fun numericalConsistencyWithUnknownThermalsDoesNotGrantSpeedRanking() {
        val records = listOf(cpu, gpu).map { record ->
            record.copy(sampleMs = emptyList(), thermalStatuses = List(VoiceMeasuredBackendPolicy.thermalSampleCount) { null })
        }
        assertEquals(VoiceSpeechBackend.QNN_GPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, records, now).selected)
        assertEquals(VoiceSpeechBackend.CPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.AUTO, records, now).selected)
        assertTrue(records.all { it.scoreMs == null })
    }

    @Test fun manualRecheckMeasuresOnlyCpuAndRequestedBackendInRotatingOrder() {
        for (backend in listOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)) {
            for (revision in 0L..2L) {
                val order = VoiceBackendAudioAdmission.recheckOrder(backend, revision)
                assertEquals(setOf(VoiceSpeechBackend.CPU, backend), order.toSet())
                assertEquals(2, order.size)
            }
        }
        assertEquals(3, VoiceBackendAudioAdmission.recheckOrder(VoiceSpeechBackend.AUTO, 0).size)
    }

    @Test fun cpuLastStillQuarantinesMismatchBeforePublishingTheCohort() {
        val order = mutableListOf<VoiceSpeechBackend>()
        val publications = mutableListOf<List<VoiceBackendCapability>>()
        VoiceBackendAudioAdmission.recheck(VoiceSpeechBackend.QNN_GPU, 1, {}, { now }, { backend ->
            order += backend
            if (backend == VoiceSpeechBackend.CPU) cpu else gpu.copy(audioObservations = VoiceSpeechWorkload.calibrationPhrases.map { speechReferenceFixture(it, 43) })
        }, publications::add)
        assertEquals(listOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.CPU), order)
        assertEquals(1, publications.size)
        assertEquals(VoiceSpeechFailureCode.OUTPUT_INVALID, publications.single().single { it.backend == VoiceSpeechBackend.QNN_GPU }.failure)
    }

    @Test fun cancellationAfterAnyCandidateDiscardsStagedSuccesses() {
        for (stopAfter in 1..2) {
            var measurements = 0
            var cancelled = false
            var published = false
            assertThrows(CancellationException::class.java) {
                VoiceBackendAudioAdmission.recheck(
                    VoiceSpeechBackend.QNN_GPU,
                    1,
                    { if (cancelled) throw CancellationException("cancelled") },
                    { now },
                    { backend ->
                        cancelled = ++measurements == stopAfter
                        receipt(backend)
                    },
                    { published = true }
                )
            }
            assertEquals(stopAfter, measurements)
            assertFalse(published)
        }
    }

    @Test fun failedCandidateFollowedByCancellationPublishesNoPartialFailureReceipt() {
        var measured = 0
        val publications = mutableListOf<List<VoiceBackendCapability>>()
        assertThrows(CancellationException::class.java) {
            VoiceBackendAudioAdmission.recheck(
                VoiceSpeechBackend.QNN_GPU,
                1,
                { if (measured > 0) throw CancellationException("cancelled after provider failure") },
                { now },
                { backend ->
                    measured++
                    receipt(backend).copy(failure = VoiceSpeechFailureCode.PROVIDER_LOAD, sampleMs = emptyList(), audioObservations = emptyList())
                },
                publications::add
            )
        }
        assertEquals(1, measured)
        assertTrue(publications.isEmpty())
    }

    @Test fun failedCpuMeasurementCannotPromoteAnOtherwiseCompleteGpuReceipt() {
        var finalized: List<VoiceBackendCapability> = emptyList()
        VoiceBackendAudioAdmission.recheck(VoiceSpeechBackend.QNN_GPU, 1, {}, { now }, { backend ->
            if (backend == VoiceSpeechBackend.CPU) null else gpu
        }, { finalized = it })
        assertEquals(listOf(gpu), finalized)
        assertEquals(VoiceSpeechBackend.CPU, VoiceBackendAudioAdmission.select(VoiceSpeechBackend.QNN_GPU, finalized, now).selected)
    }

    private fun receipt(backend: VoiceSpeechBackend) = VoiceBackendCapability(
        (backend.ordinal + 1).toString(16).padStart(64, '0'), backend, now,
        sampleMs = List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { if (backend == VoiceSpeechBackend.CPU) 100 else 70 },
        verifiedAccelerator = backend != VoiceSpeechBackend.CPU,
        comparisonScope = "a".repeat(64), speakerId = 3, speechRate = 1f,
        thermalStatuses = List(VoiceMeasuredBackendPolicy.thermalSampleCount) { 0 },
        comparisonId = "c".repeat(32),
        audioObservations = VoiceSpeechWorkload.calibrationPhrases.map(::speechReferenceFixture)
    )
}
