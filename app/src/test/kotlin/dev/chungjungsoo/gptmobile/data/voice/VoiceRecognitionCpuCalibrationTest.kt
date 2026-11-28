package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionCpuCalibrationTest {
    private val pack = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_EN))
    private val counts = listOf(1, 2, 4)

    @Test
    fun matchedWarmCorrectCoolCpuCandidatesChooseFewerWorkersWithinFivePercent() {
        val selected = VoiceRecognitionCpuCalibration.select(pack, counts, samples())
        assertEquals(2, selected.threads)
        assertNull(selected.reason)
        assertEquals(mapOf(1 to 200.0, 2 to 120.0, 4 to 116.0), selected.decodeBusyMs)
        assertEquals(listOf(1), VoiceRecognitionCpuCalibration.candidates(1))
        assertEquals(listOf(1, 2), VoiceRecognitionCpuCalibration.candidates(3))
        assertEquals(listOf(1, 2, 4), VoiceRecognitionCpuCalibration.candidates(8))
    }

    @Test
    fun incompleteMixedCohortsOrDuplicateFixturesNeverPublishARecommendation() {
        val all = samples()
        assertNull(VoiceRecognitionCpuCalibration.select(pack, counts, all.dropLast(1)).threads)
        assertEquals("RECOGNITION_CPU_COHORT_MISMATCH", selection(all.replaceFirst { it.copy(metadata = it.metadata + ("recognitionCalibrationCohort" to "d".repeat(32))) }).reason)
        val duplicate = all.toMutableList().apply { this[1] = this[0] }
        assertEquals("RECOGNITION_CPU_FIXTURE_COVERAGE_INCOMPLETE", selection(duplicate).reason)
    }

    @Test
    fun missingWarmupExecutionAccuracyOrThermalEvidenceBlocksOtherwiseFastCandidates() {
        val all = samples()
        for (changed in listOf(
            all.replaceFirst { it.copy(status = VoiceBenchStatus.INTERRUPTED, reason = "CANCELLED") },
            all.replaceFirst { it.copy(metadata = it.metadata + ("recognitionCalibrationWarmupComplete" to "false")) },
            all.replaceFirst { it.copy(metadata = it.metadata + ("recognitionExecutionVerified" to "false")) },
            all.replaceFirst { it.copy(metadata = it.metadata + ("recognitionActualProvider" to "qnn-gpu+cpu")) },
            all.replaceFirst { it.copy(metrics = it.metrics + ("wordErrors" to 1.0)) },
            all.replaceFirst { it.copy(metrics = it.metrics - "thermalBefore") },
            all.replaceFirst { it.copy(metrics = it.metrics + ("unknownThermalSampleCount" to 1.0)) },
            all.replaceFirst { it.copy(metrics = it.metrics + ("maximumObservedThermalStatus" to 2.0)) },
            all.replaceFirst { it.copy(metrics = it.metrics + ("thermalSampleCount" to Double.NaN)) }
        )) {
            assertNull(selection(changed).threads)
        }
    }

    @Test
    fun changedRuntimeModelReferenceProtocolAndInputLengthCannotReuseQualifiedSamples() {
        val all = samples()
        for (changed in listOf(
            all.replaceFirst { it.copy(recognition = it.recognition!!.copy(modelSha256 = "a".repeat(64))) },
            all.replaceFirst { it.copy(recognition = it.recognition!!.copy(runtimeId = "stale-runtime")) },
            all.replaceFirst { it.copy(recognition = it.recognition!!.copy(referenceHashes = emptyMap())) },
            all.replaceFirst { it.copy(metadata = it.metadata + ("recognitionRuntime" to "stale-runtime")) },
            all.replaceFirst { it.copy(metrics = it.metrics + ("recognitionProtocolVersion" to 0.0)) },
            all.replaceFirst { it.copy(metrics = it.metrics + ("inputAudioFrames" to 1.0)) },
            all.replaceFirst { it.copy(metrics = it.metrics + ("decodeBusyMs" to Double.POSITIVE_INFINITY)) }
        )) {
            assertNull(selection(changed).threads)
        }
    }

    @Test
    fun onlyCpuPacksWithControllableThreadsAndMatchingEnglishFixturesParticipate() {
        for (excluded in listOf(
            requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.MOONSHINE_SMALL)),
            requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_FR)),
            requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU)),
            requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.NPU))
        )) {
            assertFalse(VoiceRecognitionCpuCalibration.supported(excluded))
            assertNull(VoiceRecognitionCpuCalibration.select(excluded, counts, samples()).threads)
        }
    }

    @Test
    fun receiptPersistsOnlyCompleteCohortsAndSurvivesRecreationOnTheSameIdentity() {
        val bytes = mutableMapOf<String, String>()
        fun store() = VoiceRecognitionCpuCalibration.Store(bytes::get, { key, value ->
            bytes[key] = value
            true
        }, "device-a", counts) { 1_800_000_000_000 }
        assertTrue(store().save(pack, samples()))
        val original = bytes.toMap()
        assertEquals(2, store().recommendedThreads(pack))
        assertFalse(store().save(pack, samples().dropLast(1)))
        assertEquals(original, bytes)
        assertEquals(2, store().recommendedThreads(pack))
        assertNull(VoiceRecognitionCpuCalibration.Store(bytes::get, { _, _ -> false }, "device-b", counts) { 1_800_000_000_000 }.recommendedThreads(pack))
        assertNull(VoiceRecognitionCpuCalibration.Store(bytes::get, { _, _ -> false }, "device-a", listOf(1, 2)) { 1_800_000_000_000 }.recommendedThreads(pack))
    }

    @Test
    fun corruptExpiredFutureAndMismatchedWinnerReceiptsCannotSupplyAutoThreads() {
        val bytes = mutableMapOf<String, String>()
        val now = 1_800_000_000_000L
        val store = VoiceRecognitionCpuCalibration.Store(bytes::get, { key, value ->
            bytes[key] = value
            true
        }, "device", counts) { now }
        assertTrue(store.save(pack, samples()))
        val key = bytes.keys.single()
        val original = Json.decodeFromString<VoiceRecognitionCpuReceipt>(bytes.getValue(key))
        for (receipt in listOf(
            original.copy(observedAtMillis = 0),
            original.copy(observedAtMillis = now + 1),
            original.copy(selectedThreads = 1),
            original.copy(samples = original.samples.dropLast(1)),
            original.copy(key = "wrong-key")
        )) {
            bytes[key] = Json.encodeToString(receipt)
            assertNull(store.recommendedThreads(pack))
        }
        bytes[key] = "invalid-json"
        assertNull(store.recommendedThreads(pack))
        bytes[key] = "x".repeat(65_537)
        assertNull(store.recommendedThreads(pack))
    }

    @Test
    fun failedStorageCommitIsNotReportedAsASavedRecommendation() {
        var writes = 0
        val store = VoiceRecognitionCpuCalibration.Store({ null }, { _, _ ->
            writes++
            false
        }, "device", counts) { 1_800_000_000_000 }
        assertFalse(store.save(pack, samples()))
        assertEquals(1, writes)
        assertNull(store.recommendedThreads(pack))
        assertNotEquals(VoiceRecognitionCpuCalibration.recommendationKey(pack, "device-a", counts), VoiceRecognitionCpuCalibration.recommendationKey(pack, "device-b", counts))
    }

    private fun selection(rows: List<VoiceBenchObservation>) = VoiceRecognitionCpuCalibration.select(pack, counts, rows)

    private fun samples(): List<VoiceBenchObservation> = counts.flatMap { threads ->
        val identity = VoiceRecognitionBenchmarkCase.forPack(pack, threads).identity
        voiceBenchRecordings.map { (id, reference) ->
            VoiceBenchObservation(
                "cpu-$threads/$id",
                VoiceBenchStatus.MEASURED,
                metrics = mapOf(
                    "referenceWords" to voiceBenchWords(reference).size.toDouble(),
                    "wordErrors" to 0.0,
                    "wordErrorRate" to 0.0,
                    "recognitionProtocolVersion" to VoiceRecognitionBenchmarkProtocol.VERSION.toDouble(),
                    "realtimePacedInput" to 1.0,
                    "inputSampleRate" to 16_000.0,
                    "inputAudioFrames" to voiceBenchRecordingFrames.getValue(id).toDouble(),
                    "thermalBefore" to 0.0,
                    "thermalAfter" to 1.0,
                    "maximumObservedThermalStatus" to 1.0,
                    "thermalSampleCount" to 7.0,
                    "unknownThermalSampleCount" to 0.0,
                    "decodeBusyMs" to mapOf(1 to 100.0, 2 to 60.0, 4 to 58.0).getValue(threads)
                ),
                recognition = identity,
                metadata = mapOf(
                    "recognitionFixtureId" to id,
                    "recognitionFixtureSha256" to voiceBenchRecordingHashes.getValue(id),
                    "recognitionWarmup" to "false",
                    "recognitionCalibrationCohort" to "c".repeat(32),
                    "recognitionCalibrationWarmupComplete" to "true",
                    "recognitionActualProvider" to "cpu",
                    "recognitionExecutionVerified" to "true",
                    "recognitionRuntime" to identity.runtimeId,
                    "recognitionModelIdentity" to identity.modelSha256
                )
            )
        }
    }

    private fun List<VoiceBenchObservation>.replaceFirst(change: (VoiceBenchObservation) -> VoiceBenchObservation): List<VoiceBenchObservation> =
        toMutableList().also { it[0] = change(it[0]) }
}
