package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePerformanceExportTest {
    @Test fun streamingModelAndDelegateRequestNeverBecomeProcessorOrThreadEvidence() {
        for ((family, provider, delegate) in listOf(
            Triple("MEDIAPIPE_KOKORO", "mediapipe-kokoro-cpu-requested", "CPU"),
            Triple("MEDIAPIPE_KOKORO", "mediapipe-kokoro-gpu-requested", "GPU"),
            Triple("MEDIAPIPE_PHONON", "mediapipe-phonon-cpu-requested", "CPU"),
            Triple("MEDIAPIPE_POCKET", "mediapipe-pocket-cpu-requested", "CPU")
        )) {
            val history = VoicePerformanceHistory()
            history.observe("text_to_audio", 125, family, true)
            val sample = VoiceSynthesisFeedback(generatedAudioMs = 1000, synthesisMs = 100, executionProvider = provider, threads = 4)
            val document = Json.parseToJsonElement(voicePerformanceExport(history.snapshots.value, sample)).jsonObject
            val row = document.getValue("series").jsonArray.single().jsonObject
            assertEquals(family, row.getValue("modelFamily").jsonPrimitive.content)
            assertEquals(JsonNull, row["observedProvider"])
            val synthesis = document.getValue("latestSynthesis").jsonObject
            assertEquals(family, synthesis.getValue("modelFamily").jsonPrimitive.content)
            assertEquals(delegate, synthesis.getValue("requestedDelegate").jsonPrimitive.content)
            assertEquals(JsonNull, synthesis["observedProvider"])
            assertEquals(JsonNull, synthesis["threads"])
            assertEquals("not_exposed_by_sdk", synthesis.getValue("threadsScope").jsonPrimitive.content)
            assertEquals(JsonNull, synthesis["executionEvidence"])
        }
    }

    @Test fun graphicsOnlyExportIdentifiesMissingSpeechAndUtilizationWithoutInventingZeros() {
        val history = VoicePerformanceHistory()
        repeat(6295) {
            history.observe("frame_duration", 5, publish = false)
            history.observe("gpu_duration", 0, publish = false)
        }
        history.framesDropped(3)
        history.publish()
        val json = Json.parseToJsonElement(voicePerformanceExport(history.snapshots.value)).jsonObject
        val availability = json.getValue("availability").jsonObject
        assertEquals("NO_RECORDED_SAMPLES", availability.getValue("speechStageTimings").jsonPrimitive.content)
        assertEquals("OBSERVED", availability.getValue("graphicsGpuFrameDuration").jsonPrimitive.content)
        assertEquals("NOT_MEASURED", availability.getValue("cpuGpuNpuUtilization").jsonPrimitive.content)
        assertEquals("NOT_MEASURED", availability.getValue("endToEndConversationLatency").jsonPrimitive.content)
        for (field in listOf("cpuUtilization", "gpuUtilization", "npuUtilization", "latestSynthesis")) assertEquals(JsonNull, json[field])
        assertEquals(6295L, json.getValue("observedFrameSamples").jsonPrimitive.long)
        assertEquals(3L, json.getValue("droppedFrameMetricCallbacks").jsonPrimitive.long)
        for (row in json.getValue("series").jsonArray.map { it.jsonObject }) {
            assertEquals(64, row.getValue("samples").jsonPrimitive.int)
            assertEquals(6295L, row.getValue("observedSamples").jsonPrimitive.long)
            assertEquals(6231L, row.getValue("evictedSamples").jsonPrimitive.long)
        }
    }

    @Test fun absentMeasurementsStayUnknownAndDoNotCertifyPhysicalPerformance() {
        val json = Json.parseToJsonElement(voicePerformanceExport(VoicePerformanceSnapshot())).jsonObject
        assertEquals(3, json.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(JsonNull, json["captureQueueMs"])
        assertEquals(JsonNull, json["thermalStatus"])
        assertEquals(JsonNull, json["latestSynthesis"])
        assertEquals(JsonNull, json["acousticSpeakerLatency"])
        assertEquals(JsonNull, json["batteryEnergy"])
        assertFalse(json.getValue("pairedSpeedComparison").jsonPrimitive.boolean)
        assertTrue(json.getValue("series").jsonArray.isEmpty())
    }

    @Test fun coldWarmAndActualProvidersKeepTheirOwnRecordedSeries() {
        val history = VoicePerformanceHistory(8)
        history.observe("text_to_audio", 600, "KOKORO_CPU", false)
        (1L..12).forEach { history.observe("text_to_audio", it * 10, "QNN_GPU", true) }
        val exported = voicePerformanceExport(history.snapshots.value, VoiceSynthesisFeedback(generatedAudioMs = 1000, synthesisMs = 120, executionProvider = "qnn-gpu", route = "speaker", warm = true))
        val json = Json.parseToJsonElement(exported).jsonObject
        val rows = json.getValue("series").jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        val warm = rows.single { it.getValue("warm").jsonPrimitive.boolean }
        assertEquals("QNN_GPU", warm.getValue("observedProvider").jsonPrimitive.content)
        assertEquals(8, warm.getValue("samples").jsonPrimitive.int)
        assertEquals(80L, warm.getValue("p50Ms").jsonPrimitive.long)
        assertEquals(120L, warm.getValue("p95Ms").jsonPrimitive.long)
        assertEquals("qnn-gpu", json.getValue("latestSynthesis").jsonObject.getValue("observedProvider").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("latestSynthesis").jsonObject["firstAudioMs"])
        assertEquals("playback_operation_start_to_first_observed_presented_audio", json.getValue("latestSynthesis").jsonObject.getValue("firstAudioScope").jsonPrimitive.content)
    }

    @Test fun unknownLabelsCannotExportConversationTextOrDeviceNames() {
        val privateText = "private transcript and personal headset name"
        val snapshot = VoicePerformanceSnapshot(rows = listOf(VoicePerformanceRow(privateText, "QNN_GPU", true, 1, 5, 5)))
        val exported = voicePerformanceExport(snapshot, VoiceSynthesisFeedback(generatedAudioMs = 1, route = privateText, clockBasis = privateText, executionProvider = privateText))
        assertFalse(exported.contains(privateText))
        val json = Json.parseToJsonElement(exported).jsonObject
        assertTrue(json.getValue("series").jsonArray.isEmpty())
        assertEquals(JsonNull, json.getValue("latestSynthesis").jsonObject["route"])
        assertEquals(JsonNull, json.getValue("latestSynthesis").jsonObject["observedProvider"])
    }

    @Test fun frozenExportIsUnaffectedByResetOrSubsequentObservations() {
        val history = VoicePerformanceHistory()
        history.observe("text_to_audio", 140, "KOKORO_CPU", true)
        val captured = voicePerformanceExport(history.snapshots.value)
        history.clear()
        history.observe("text_to_audio", 700, "QNN_HTP", false)
        val row = Json.parseToJsonElement(captured).jsonObject.getValue("series").jsonArray.single().jsonObject
        assertEquals("KOKORO_CPU", row.getValue("observedProvider").jsonPrimitive.content)
        assertEquals(140L, row.getValue("p50Ms").jsonPrimitive.long)
    }

    @Test fun selectedAccelerationAndActualCpuFallbackStayDistinctWithoutDeviceIdentifiers() {
        val secret = "private-device-or-runtime-identifier"
        val evidence = VoiceSpeechExecutionEvidence(
            selectedBackend = VoiceSpeechBackend.QNN_HTP,
            effectiveProvider = "cpu",
            modelSha256 = secret,
            runtimeId = secret,
            deviceKey = secret,
            stage = VoiceSpeechStage.CPU_FALLBACK,
            failure = VoiceSpeechFailureCode.GRAPH_PARTITION,
            verifiedAudio = true,
            observedAtMillis = 1000,
            profileAvailable = false
        )
        val exported = voicePerformanceExport(VoicePerformanceSnapshot(), VoiceSynthesisFeedback(generatedAudioMs = 1000, executionProvider = "cpu", executionEvidence = evidence))
        assertFalse(exported.contains(secret))
        val observed = Json.parseToJsonElement(exported).jsonObject.getValue("latestSynthesis").jsonObject.getValue("executionEvidence").jsonObject
        assertEquals("QNN_HTP", observed.getValue("selectedBackend").jsonPrimitive.content)
        assertEquals("cpu", observed.getValue("effectiveProvider").jsonPrimitive.content)
        assertEquals("GRAPH_PARTITION", observed.getValue("failure").jsonPrimitive.content)
        assertEquals(JsonNull, observed["acceleratorKernelEvents"])
        assertEquals(JsonNull, observed["cpuKernelEvents"])
        assertEquals("not_collected", observed.getValue("profileScope").jsonPrimitive.content)
        assertTrue(observed.getValue("audioTimingValidated").jsonPrimitive.boolean)
    }

    @Test fun cacheAdmissionReportsBudgetAndUnknownMemoryWithoutClaimingUtilization() {
        val evidence = VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.QNN_HTP, "qnn-htp", "private-model", "private-runtime", "private-device", VoiceSpeechStage.READY,
            observedAtMillis = 1000, cacheBudgetBytes = 536870912, activeShapeEstimatedBytes = 240000000,
            availableMemoryBytes = null, cacheAdmission = "MEMORY_UNKNOWN"
        )
        fun exported(value: VoiceSpeechExecutionEvidence) = Json.parseToJsonElement(
            voicePerformanceExport(VoicePerformanceSnapshot(), VoiceSynthesisFeedback(generatedAudioMs = 1000, executionEvidence = value))
        ).jsonObject.getValue("latestSynthesis").jsonObject.getValue("executionEvidence").jsonObject
        val observed = exported(evidence)
        assertEquals(536870912L, observed.getValue("cacheBudgetBytes").jsonPrimitive.long)
        assertEquals(240000000L, observed.getValue("activeShapeEstimatedBytes").jsonPrimitive.long)
        assertEquals(JsonNull, observed["availableMemoryBytes"])
        assertEquals("MEMORY_UNKNOWN", observed.getValue("cacheAdmission").jsonPrimitive.content)
        assertFalse(observed.toString().contains("private"))
        val invalid = exported(evidence.copy(cacheBudgetBytes = -1, activeShapeEstimatedBytes = Long.MAX_VALUE, availableMemoryBytes = -1, cacheAdmission = "private device details"))
        for (field in listOf("cacheBudgetBytes", "activeShapeEstimatedBytes", "availableMemoryBytes", "cacheAdmission")) assertEquals(JsonNull, invalid[field])
    }

    @Test fun collectedProfileKeepsCachedEvidenceDistinctFromCurrentPreparation() {
        val evidence = VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.QNN_HTP, "qnn-htp", "model", "runtime", "device", VoiceSpeechStage.READY,
            acceleratorKernels = 39, cpuKernels = 50, acceleratorMicros = 120, cpuMicros = 30,
            prepareMs = 300, synthesisMs = 100, tokenCount = 41, verifiedAudio = true, observedAtMillis = 1000,
            profileAvailable = true, profileId = 7, profileReused = true, cacheHit = true, currentPrepareMs = 0,
            graphPreparedThisCall = false, cachedShapeCount = 2, cacheLimit = 2, cacheEstimatedBytes = 1024,
            audioRms = .1, audioPeak = .2, clippedSamples = 0, nonSilentSamples = 1200
        )
        val observed = Json.parseToJsonElement(voicePerformanceExport(VoicePerformanceSnapshot(), VoiceSynthesisFeedback(generatedAudioMs = 1000, executionEvidence = evidence)))
            .jsonObject.getValue("latestSynthesis").jsonObject.getValue("executionEvidence").jsonObject
        assertTrue(observed.getValue("profileAvailable").jsonPrimitive.boolean)
        assertEquals("first_inference_of_exact_graph_shape", observed.getValue("profileScope").jsonPrimitive.content)
        assertEquals(7L, observed.getValue("profileId").jsonPrimitive.long)
        assertTrue(observed.getValue("profileReused").jsonPrimitive.boolean)
        assertEquals(39, observed.getValue("acceleratorKernelEvents").jsonPrimitive.int)
        assertEquals(50, observed.getValue("cpuKernelEvents").jsonPrimitive.int)
        assertEquals(120L, observed.getValue("acceleratorMicros").jsonPrimitive.long)
        assertEquals(30L, observed.getValue("cpuMicros").jsonPrimitive.long)
        assertEquals(300L, observed.getValue("prepareMs").jsonPrimitive.long)
        assertEquals(0L, observed.getValue("currentPrepareMs").jsonPrimitive.long)
        assertFalse(observed.getValue("graphPreparedThisCall").jsonPrimitive.boolean)
        assertTrue(observed.getValue("cacheHit").jsonPrimitive.boolean)
        assertEquals(2, observed.getValue("cachedShapeCount").jsonPrimitive.int)
        assertEquals(2, observed.getValue("cacheLimit").jsonPrimitive.int)
        assertEquals(1024L, observed.getValue("cacheEstimatedBytes").jsonPrimitive.long)
        assertEquals(.1, observed.getValue("audioRms").jsonPrimitive.double, .0001)
        assertEquals(.2, observed.getValue("audioPeak").jsonPrimitive.double, .0001)
        assertEquals(0L, observed.getValue("clippedSamples").jsonPrimitive.long)
        assertEquals(1200L, observed.getValue("nonSilentSamples").jsonPrimitive.long)
    }

    @Test fun unknownOrUnavailableProfilesCannotExportCountersAndInvalidNumbersStayUnknown() {
        for (profile in listOf(null, false)) {
            val evidence = VoiceSpeechExecutionEvidence(
                VoiceSpeechBackend.CPU, "cpu", "model", "runtime", "device", VoiceSpeechStage.READY,
                acceleratorKernels = 39, cpuKernels = 50, acceleratorMicros = 120, cpuMicros = 30, observedAtMillis = 1000,
                profileAvailable = profile, profileId = 7, profileReused = true,
                currentPrepareMs = -1, cachedShapeCount = -1, cacheLimit = 100, cacheEstimatedBytes = Long.MAX_VALUE,
                audioRms = Double.NaN, audioPeak = Double.POSITIVE_INFINITY, clippedSamples = -1, nonSilentSamples = Long.MAX_VALUE
            )
            val observed = Json.parseToJsonElement(voicePerformanceExport(VoicePerformanceSnapshot(), VoiceSynthesisFeedback(generatedAudioMs = 1000, executionEvidence = evidence)))
                .jsonObject.getValue("latestSynthesis").jsonObject.getValue("executionEvidence").jsonObject
            assertEquals(if (profile == null) "unknown" else "not_collected", observed.getValue("profileScope").jsonPrimitive.content)
            if (profile == null) assertEquals(JsonNull, observed["profileAvailable"]) else assertFalse(observed.getValue("profileAvailable").jsonPrimitive.boolean)
            for (field in listOf("acceleratorKernelEvents", "cpuKernelEvents", "acceleratorMicros", "cpuMicros", "profileId", "profileReused", "currentPrepareMs", "cachedShapeCount", "cacheLimit", "cacheEstimatedBytes", "audioRms", "audioPeak", "clippedSamples", "nonSilentSamples")) {
                assertEquals(field, JsonNull, observed[field])
            }
        }
    }
}
