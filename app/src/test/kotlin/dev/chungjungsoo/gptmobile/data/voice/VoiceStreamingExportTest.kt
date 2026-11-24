package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamingExportTest {
    private fun configuration() = VoiceSpeechBenchmarkConfiguration(
        VoiceSpeechBenchmarkMode.STABILITY, listOf(VoiceSpeechBackend.CPU), KokoroVoice.HEART, 2, 1f,
        "a".repeat(64), "test-runtime", 149, 36, "b".repeat(64)
    )

    @Test fun completeLargeNativeReportStreamsEveryFinalRecordAndMatchesCanonicalSchema() {
        val chunk = VoiceSpeechBenchmarkChunk(
            0, 90, "c".repeat(64), 100, 12_775.0, "cpu", 0, 0, 0, 0, 0, 511, true, 0, 0, null,
            audioObservation = VoiceSpeechAudioObservation("c".repeat(64), 90, 306_600, List(511) { 1L }, .1, .2, 0, 306_600)
        )
        val samples = VoiceSpeechBenchmarkFixture.entries.flatMap { fixture ->
            (0..20).map { repeat ->
                VoiceSpeechBenchmarkSample(
                    fixture, repeat, 2400, 100, 100, 306_600.0, "cpu", 0, 0, 0, 0, 0, 511, true, 0, 0, null,
                    chunks = List(24) { chunk.copy(index = it) }
                )
            }
        }
        val report = VoiceSpeechBenchmarkReport(
            10,
            configuration(),
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.COMPLETE, samples = samples))
        )
        val bytes = ByteArrayOutputStream()
        var largestWrite = 0
        var closed = false
        val bounded = object : OutputStream() {
            override fun write(value: Int) = bytes.write(value)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                largestWrite = maxOf(largestWrite, length)
                bytes.write(buffer, offset, length)
            }
            override fun close() {
                closed = true
            }
        }
        writeVoiceSpeechBenchmarkExport(report, bounded, local = true)
        assertTrue("Exercise a multi-megabyte export", bytes.size() > 2 * 1024 * 1024)
        assertTrue("Never write the complete document at once", largestWrite <= 16 * 1024)
        assertFalse("Destination ownership stays with the artifact store", closed)
        val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(speechBenchmarkJson(report, local = true), document)
        val last = document.getValue("results").jsonArray.single().jsonObject.getValue("samples").jsonArray.last().jsonObject
        assertEquals(JsonPrimitive(20), last["repeat"])
        assertEquals(JsonPrimitive(23), last.getValue("chunks").jsonArray.last().jsonObject["index"])
    }

    @Test fun sdkPartialSampleAndPerformanceExportPreserveSchemaAndAvailability() {
        val sample = VoiceSpeechEngineBenchmarkSample(
            VoiceSpeechBenchmarkFixture.SHORT, 0, 40, "d".repeat(64), 500, 100, 400, 120,
            24_000, 2, 1, 300, true, 0, 0, .1, .2, 0, 24_000,
            listOf(VoiceSpeechEnginePcmReceipt(0, 100, 12_000, 0), VoiceSpeechEnginePcmReceipt(1, 400, 12_000, 0))
        )
        val config = configuration().copy(engine = VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO, backends = listOf(VoiceSpeechBackend.LITERT_KOKORO_CPU))
        val report = VoiceSpeechBenchmarkReport(
            11,
            config,
            VoiceSpeechBenchmarkRunState.FAILED,
            results = emptyList(),
            engineResults = listOf(VoiceSpeechEngineBenchmarkResult(VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBenchmarkBackendState.FAILED, samples = listOf(sample), partialSample = sample.copy(repeat = 1), failureCode = "STREAM_FAILED"))
        )
        val output = ByteArrayOutputStream()
        writeVoiceSpeechBenchmarkExport(report, output)
        assertEquals(speechBenchmarkJson(report), Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())))
        output.reset()
        val snapshot = VoicePerformanceSnapshot(rows = listOf(VoicePerformanceRow("synthesis", "CPU", true, 2, 100, 150)))
        writeVoicePerformanceExport(snapshot, null, output)
        assertEquals(Json.parseToJsonElement(voicePerformanceExport(snapshot)), Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())))
    }

    @Test fun streamingEscapesUnicodeAndControlCharactersAndPropagatesDestinationFailures() {
        val value = buildJsonObject { put("quote\"\\\nΩ", "text\t\r\n\"\\文😀") }
        val output = ByteArrayOutputStream()
        VoiceExportJsonWriter(output).apply {
            element(value)
            finish()
        }
        assertEquals(value, Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())))
        assertThrows(IOException::class.java) {
            writeVoiceSpeechBenchmarkExport(
                VoiceSpeechBenchmarkReport(10, configuration()),
                object : OutputStream() {
                    override fun write(value: Int): Unit = throw IOException("document provider failed")
                }
            )
        }
    }

    @Test fun nativeContextAndModelVariantEvidenceRemainIdenticalInStreamedAndCanonicalExports() {
        val audio = speechReferenceFixture("the voice")
        val chunk = VoiceSpeechBenchmarkChunk(
            0, audio.inputBytes, audio.inputSha256, 150, audio.audioMs, "qnn-htp", 5, 2, 1000, 2000, 47, 43, true, 0, 0, 2,
            profileAvailable = true, profileId = 42, cacheHit = false, currentPrepareMs = 47,
            audioRms = audio.rms, audioPeak = audio.peak, clippedSamples = audio.clippedSamples, nonSilentSamples = audio.nonSilentSamples,
            audioObservation = audio, graphPreparationSource = "DISK_CONTEXT", contextCacheStatus = "HIT",
            contextLoadMs = 47, contextStoreMs = 0, contextBytes = 65_536, contextCacheEvictions = 0, contextFallback = false,
            htpPerformanceMode = "burst"
        )
        val sample = VoiceSpeechBenchmarkSample(
            VoiceSpeechBenchmarkFixture.SHORT, 0, 150, 150, 150, audio.audioMs, "qnn-htp", 5, 2, 1000, 2000, 47, 43, true, 0, 0, 2, chunks = listOf(chunk)
        )
        val config = configuration().copy(
            backends = listOf(VoiceSpeechBackend.QNN_HTP),
            nativeModelVariant = KokoroModelVariant.ORIGINAL_FP32,
            modelSha256 = KokoroModelVariant.ORIGINAL_FP32.modelSha256,
            htpPerformanceMode = "burst",
            sustainedTargetAudioMs = 60_000
        )
        val report = VoiceSpeechBenchmarkReport(
            12,
            config,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.QNN_HTP, VoiceSpeechBenchmarkBackendState.COMPLETE, samples = listOf(sample)))
        )
        val output = ByteArrayOutputStream()
        writeVoiceSpeechBenchmarkExport(report, output, local = true)
        val streamed = Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(speechBenchmarkJson(report, local = true), streamed)
        assertEquals(report, speechBenchmarkReportFromJson(streamed))
        val exported = streamed.getValue("results").jsonArray.single().jsonObject.getValue("samples").jsonArray.single().jsonObject.getValue("chunks").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("DISK_CONTEXT"), exported["graphPreparationSource"])
        assertEquals(JsonPrimitive("HIT"), exported["contextCacheStatus"])
        assertEquals(JsonPrimitive(47L), exported["contextLoadMs"])
        assertEquals(JsonPrimitive(0L), exported["contextStoreMs"])
        assertEquals(JsonPrimitive(65_536L), exported["contextBytes"])
        assertEquals(JsonPrimitive(0L), exported["contextCacheEvictions"])
        assertEquals(JsonPrimitive(false), exported["contextFallback"])
        assertEquals(JsonPrimitive("burst"), exported["htpPerformanceMode"])
        assertTrue(report.qualifiedResults().isEmpty())
    }
}
