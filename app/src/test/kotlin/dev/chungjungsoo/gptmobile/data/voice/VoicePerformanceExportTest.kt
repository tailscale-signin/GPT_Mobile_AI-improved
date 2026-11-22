package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
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
    @Test fun absentMeasurementsStayUnknownAndDoNotCertifyPhysicalPerformance() {
        val json = Json.parseToJsonElement(voicePerformanceExport(VoicePerformanceSnapshot())).jsonObject
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
            cpuKernels = 14,
            verifiedAudio = true,
            observedAtMillis = 1000
        )
        val exported = voicePerformanceExport(VoicePerformanceSnapshot(), VoiceSynthesisFeedback(generatedAudioMs = 1000, executionProvider = "cpu", executionEvidence = evidence))
        assertFalse(exported.contains(secret))
        val observed = Json.parseToJsonElement(exported).jsonObject.getValue("latestSynthesis").jsonObject.getValue("executionEvidence").jsonObject
        assertEquals("QNN_HTP", observed.getValue("selectedBackend").jsonPrimitive.content)
        assertEquals("cpu", observed.getValue("effectiveProvider").jsonPrimitive.content)
        assertEquals("GRAPH_PARTITION", observed.getValue("failure").jsonPrimitive.content)
        assertEquals(0, observed.getValue("acceleratorKernelEvents").jsonPrimitive.int)
        assertTrue(observed.getValue("audioTimingValidated").jsonPrimitive.boolean)
    }
}
