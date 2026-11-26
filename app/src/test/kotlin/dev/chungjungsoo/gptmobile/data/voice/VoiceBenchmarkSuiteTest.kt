package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceBenchmarkSuiteTest {
    @get:Rule val temp = TemporaryFolder()

    private val stages = fullVoiceBenchPlan(0, 1.2f, 8)
    private fun manifest(rows: List<VoiceBenchResult>, timestamp: Long = 1_800_000_000_000L) = VoiceBenchManifest(
        runId = "$timestamp-${UUID.randomUUID()}",
        startedAtMillis = timestamp,
        build = 152,
        commit = "a".repeat(40),
        androidApi = 36,
        results = rows
    )

    @Test fun everyStageFitsTheRealRunnersAndEverySupportedRouteHasACompleteBaseline() {
        assertEquals(stages.size, stages.map { it.label }.distinct().size)
        assertEquals(VoiceBenchProbe.entries.toSet(), stages.mapNotNull { it.probe }.toSet())
        assertEquals(VoiceListeningStyle.entries.toSet(), stages.mapNotNull { it.listeningStyle }.toSet())
        assertEquals(SpeechLanguages.synthesis.map { it.tag }.toSet(), stages.mapNotNull { it.language }.toSet())
        val configs = stages.filter { it.engine != null }.mapIndexed { i, stage -> stage.configuration(KokoroVoice.HEART, "ff_siwis", 240, i.toLong(), 152, 36, "fixture", "a".repeat(40)) }
        for (engine in VoiceSpeechBenchmarkEngine.entries) {
            val part = configs.filter { it.engine == engine }
            val base = part.first { !it.playback && it.mode == VoiceSpeechBenchmarkMode.FULL && it.workload == VoiceSpeechBenchmarkWorkload.UTTERANCE }
            assertEquals(engine.supportedBackends, base.backends)
            assertEquals(VoiceSpeechBenchmarkWorkload.entries.toSet(), part.map { it.workload }.toSet())
            assertTrue(part.any { it.mode.warmRepeats == 20 })
            for (route in engine.supportedBackends) {
                val audible = part.filter { it.playback && route in it.backends }
                assertEquals(VoiceSpeechPlaybackScenario.entries.toSet(), audible.flatMap { it.playbackScenarios }.toSet())
                assertEquals(VoiceSpeechSustainedSeries.TARGETS, audible.filter { VoiceSpeechPlaybackScenario.SUSTAINED in it.playbackScenarios }.map { it.sustainedTargetAudioMs }.toSet())
                assertTrue(audible.flatMap(VoiceSpeechPlaybackProtocol::plans).isNotEmpty())
            }
            if (engine.mediaPipeEngine()?.isKokoro == true) {
                assertEquals(MediaPipeKokoroBucket.entries.flatMap { b -> listOf(b to true, b to false) }.toSet(), part.map { it.kokoroBucket to it.incrementalInput }.toSet())
                assertEquals("af_heart", base.engineVoiceId)
                assertEquals("ff_siwis", part.first { it.calibrationPolicy == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS }.engineVoiceId)
            }
            assertEquals(0, base.captionOffsetMs)
            assertEquals(240, part.first { it.calibrationPolicy == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS }.captionOffsetMs)
        }
        for (variant in KokoroModelVariant.entries) {
            val native = configs.filter { it.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO && it.nativeModelVariant == variant }
            assertEquals((1..8).toSet(), native.filter { it.backends == listOf(VoiceSpeechBackend.CPU) }.map { it.threads }.toSet())
            assertEquals(setOf("balanced", "burst", "high_performance"), native.filter { VoiceSpeechBackend.QNN_HTP in it.backends }.map { it.htpPerformanceMode }.toSet())
        }
    }

    @Test fun deviceThreadLimitsAndInvalidRatesCannotCreateUnrunnableStages() {
        for (count in listOf(0, 1, 4, 32)) {
            val plan = fullVoiceBenchPlan(64, Float.NaN, count)
            assertTrue(plan.all { it.threads in 1..count.coerceIn(1, 16) && it.rate == 1f })
        }
    }

    @Test fun campaignRunsMeasuredCasesSeriallyAndExportsMoreThanTheFiveLegacyHistorySlots() = runBlocking {
        val plan = stages.take(11)
        val initial = manifest(plan.mapIndexed { i, stage -> if (i == 3) VoiceBenchResult(stage.label, VoiceBenchStatus.UNAVAILABLE, "MODEL_NOT_INSTALLED") else VoiceBenchResult(stage.label) })
        val store = VoiceBenchmarkSuiteStore(temp.newFolder())
        store.begin(initial, VoicePerformanceSnapshot(), null)
        val seen = mutableListOf<Int>()
        val updates = mutableListOf<VoiceBenchManifest>()
        VoiceBenchmarkCampaign(store, updates::add) { 88L }.run(initial, plan, { null }, { true }) { index, stage ->
            seen += index
            store.evidence(initial.runId, index) { it.write("""{"measuredIndex":$index}""".toByteArray()) }
            VoiceBenchResult(stage.label, VoiceBenchStatus.MEASURED, measurements = 1)
        }
        val result = requireNotNull(store.latest())
        assertEquals((0..10).filter { it != 3 }, seen)
        assertEquals("FINISHED", result.state)
        assertEquals(11, result.completed)
        assertTrue(updates.none { m -> m.results.count { it.status == VoiceBenchStatus.RUNNING } > 1 })
        val output = ByteArrayOutputStream()
        store.export(result, output)
        val document = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
        assertEquals(11, document.getValue("stageEvidence").jsonArray.size)
        assertEquals(JsonNull, document.getValue("stageEvidence").jsonArray[3].jsonObject["report"])
        assertEquals("10", document.getValue("stageEvidence").jsonArray[10].jsonObject.getValue("report").jsonObject.getValue("measuredIndex").jsonPrimitive.content)
        assertEquals("current_app_process", document.getValue("liveSessionPerformance").jsonObject.getValue("scope").jsonPrimitive.content)
    }

    @Test fun cancellationThermalAndUnconfirmedCleanupLeaveRemainingWorkUnmeasured() = runBlocking {
        for (reason in listOf("CANCELLED", "NATIVE_CLEANUP_UNCONFIRMED", "THERMAL_LIMIT")) {
            val plan = stages.take(4)
            val store = VoiceBenchmarkSuiteStore(temp.newFolder())
            val initial = manifest(plan.map { VoiceBenchResult(it.label) })
            store.begin(initial, VoicePerformanceSnapshot(), null)
            var completed = 0
            VoiceBenchmarkCampaign(store, {}).run(initial, plan, { reason.takeIf { completed == 1 && it != "THERMAL_LIMIT" } }, { completed == 0 || reason != "THERMAL_LIMIT" }) { _, stage ->
                completed++
                VoiceBenchResult(stage.label, VoiceBenchStatus.MEASURED)
            }
            val result = requireNotNull(store.latest())
            assertEquals(reason, result.state)
            assertEquals(1, result.completed)
            assertEquals(3, result.results.count { it.status == VoiceBenchStatus.PENDING })
        }
    }

    @Test fun reopeningAfterProcessDeathKeepsPartialStageAndIgnoresIncompleteWrite() {
        val folder = temp.newFolder()
        val store = VoiceBenchmarkSuiteStore(folder)
        val initial = manifest(listOf(VoiceBenchResult("partial", VoiceBenchStatus.RUNNING)))
        store.begin(initial, VoicePerformanceSnapshot(), null)
        store.evidence(initial.runId, 0) { it.write("""{"samples":2}""".toByteArray()) }
        assertTrue(
            runCatching {
                store.evidence(initial.runId, 0) {
                    it.write("bad".toByteArray())
                    throw java.io.IOException()
                }
            }.isFailure
        )
        val restored = requireNotNull(VoiceBenchmarkSuiteStore(folder).latest())
        assertEquals("INTERRUPTED", restored.state)
        assertNull(restored.finishedAtMillis)
        assertEquals(VoiceBenchStatus.INTERRUPTED, restored.results.single().status)
        val output = ByteArrayOutputStream()
        store.export(restored, output)
        assertTrue(output.toString("UTF-8").contains("\"samples\":2"))
        assertFalse(output.toString("UTF-8").contains("bad"))
        assertEquals("RUNNING", store.latest(active = true)?.state)
    }

    @Test fun newRunSurvivesWallClockRollbackAndOnePreviousExportRemainsReadable() {
        val store = VoiceBenchmarkSuiteStore(temp.newFolder())
        val a = manifest(emptyList())
        val b = manifest(emptyList(), a.startedAtMillis - 10_000)
        store.begin(a, VoicePerformanceSnapshot(), null)
        store.begin(b, VoicePerformanceSnapshot(), null)
        assertEquals(b.runId, store.latest()?.runId)
        store.export(a, ByteArrayOutputStream())
        val c = manifest(emptyList(), b.startedAtMillis - 10_000)
        store.begin(c, VoicePerformanceSnapshot(), null)
        assertEquals(c.runId, store.latest()?.runId)
        store.export(b, ByteArrayOutputStream())
        assertTrue(runCatching { store.export(a, ByteArrayOutputStream()) }.isFailure)
    }

    @Test fun languageDictionaryAndThinkingFixturesExerciseProductionMappings() {
        for (profile in SpeechLanguages.synthesis) {
            val dictionary = voiceBenchDictionary(profile)
            assertEquals(profile.tag, VoiceBenchStatus.MEASURED, dictionary.status)
            assertEquals(dictionary.metrics["cases"], dictionary.metrics["correctMappings"])
        }
        assertTrue(voiceBenchDialogue().all { it.status == VoiceBenchStatus.MEASURED && it.metrics.getValue("plannedLookupDelayMs") < it.metrics.getValue("plannedThinkingDelayMs") })
    }

    @Test fun fixedRecognitionFixturesAreNonSilentAndWordErrorsCountInsertionsDeletionsAndSubstitutions() {
        for ((id, _) in voiceBenchRecordings) {
            val path = listOf(File("src/main/assets"), File("app/src/main/assets")).map { File(it, "voice-benchmark/$id.mulaw") }.first { it.isFile }
            val input = path.readBytes()
            assertTrue(voiceBenchFixtureVerified(id, input))
            assertFalse(voiceBenchFixtureVerified(id, input.copyOf(input.size - 1)))
            val pcm = input.map(::decodeVoiceBenchSample)
            assertTrue(pcm.size in 16_000..120_000 && pcm.all { it.isFinite() && it in -1f..1f })
            assertTrue(pcm.any { kotlin.math.abs(it) > .1 })
        }
        assertEquals(0f, decodeVoiceBenchSample(0xFF.toByte()), 0f)
        val expected = voiceBenchWords("The voice is ready.")
        assertEquals(0, voiceBenchWordErrors(expected, voiceBenchWords("THE voice IS ready!")))
        assertEquals(4, voiceBenchWordErrors(expected, voiceBenchWords("the item ready for you")))
        assertEquals(expected.size, voiceBenchWordErrors(expected, emptyList()))
    }

    @Test fun accuracyAndTimingSummariesKeepMissingObservationsOutOfTheMedian() {
        val summary = listOf(
            VoiceBenchObservation("fr", VoiceBenchStatus.MEASURED, metrics = mapOf("wordErrorRate" to .25, "firstPcmMs" to 200.0)),
            VoiceBenchObservation("en", VoiceBenchStatus.MEASURED, metrics = mapOf("firstPcmMs" to 400.0)),
            VoiceBenchObservation.unavailable("ja", "MISSING")
        ).summarize("languages")
        assertEquals(VoiceBenchStatus.PARTIAL, summary.status)
        assertEquals(VoiceBenchMetric("fr · word error rate", 25.0, "%", 1), summary.metrics.first())
        assertEquals(VoiceBenchMetric("First PCM", 300.0, "ms", 2), summary.metrics.last())
    }

    @Test fun platformSynthesisOnlyReportsDurationForCompleteValidWaveData() {
        val f = temp.newFile()
        val data = ByteBuffer.allocate(44 + 320).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(356)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16000)
            putInt(32000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(320)
        }.array()
        f.writeBytes(data)
        val silence = requireNotNull(voiceBenchWav(f))
        assertEquals(10.0, silence.durationMs, 0.0)
        assertEquals(16_000, silence.sampleRate)
        assertEquals(0L, silence.signal.nonSilent)
        f.writeBytes(data.clone().also { it[45] = 64 })
        assertEquals(1L, requireNotNull(voiceBenchWav(f)).signal.nonSilent)
        f.writeBytes(data.copyOf(data.size - 1))
        assertNull(voiceBenchWav(f))
        f.writeBytes(data.clone().also { it[32] = 4 })
        assertNull(voiceBenchWav(f))
    }
}
