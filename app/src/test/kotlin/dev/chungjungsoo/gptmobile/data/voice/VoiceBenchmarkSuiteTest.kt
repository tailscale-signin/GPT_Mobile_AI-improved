package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
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

    @Test fun quickScreeningCoversAllPacksBeforeFullSynthesis() {
        val screens = stages.filter { it.label.endsWith("quick synthesis screening") }
        assertEquals(VoiceSpeechBenchmarkEngine.entries.toSet(), screens.mapNotNull { it.engine }.toSet())
        assertEquals(KokoroModelVariant.entries.toSet(), screens.filter { it.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO }.map { it.variant }.toSet())
        assertTrue(screens.all { it.mode == VoiceSpeechBenchmarkMode.QUICK })
        assertTrue(screens.all { stages.indexOf(it) < stages.indexOfFirst { row -> row.label.endsWith("first audio and warm synthesis") } })
        assertEquals(VoiceSpeechBenchmarkEngine.POCKET, screens.first().engine)
        assertTrue(screens.none { VoiceSpeechBackend.QNN_GPU in it.backends || VoiceSpeechBackend.QNN_HTP in it.backends })
    }

    @Test fun installedStreamingEnginesGetPlaybackCancellationAndRatesBeforeExhaustiveNativeOrLanguageWork() {
        val firstExhaustive = stages.indexOfFirst {
            it.label.endsWith("first audio and warm synthesis") || it.probe == VoiceBenchProbe.LANGUAGE
        }
        val early = stages.take(firstExhaustive)
        for (engine in listOf(VoiceSpeechBenchmarkEngine.POCKET, VoiceSpeechBenchmarkEngine.POCKET_MULTILINGUAL, VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO)) {
            val playback = early.filter { it.engine == engine && it.playback.isNotEmpty() }
            assertTrue(playback.any { it.playback == listOf(VoiceSpeechPlaybackScenario.COLD_START) })
            assertTrue(playback.any { it.playback == listOf(VoiceSpeechPlaybackScenario.CANCELLATION) })
            assertTrue(playback.filter { VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS in it.playback }.map { it.rate }.containsAll(listOf(.75f, 1.25f, 1.5f, 2f)))
            assertTrue(playback.any { it.calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS })
            assertTrue(playback.all { it.mode == VoiceSpeechBenchmarkMode.QUICK })
        }
        assertTrue(early.none { VoiceSpeechBackend.QNN_GPU in it.backends || VoiceSpeechBackend.QNN_HTP in it.backends })
        val cancellation = early.filter { it.playback == listOf(VoiceSpeechPlaybackScenario.CANCELLATION) }
        assertEquals(VoiceSpeechBenchmarkEngine.POCKET, cancellation.first().engine)
        val pocketConfig = cancellation.first().configuration(KokoroVoice.HEART, "alba", 0, 0, 153, 36, "fixture", "a".repeat(40))
        assertEquals(VoiceSpeechPlaybackProtocol.cancellationTargets(pocketConfig).toSet(), VoiceSpeechPlaybackProtocol.plans(pocketConfig).mapNotNull { it.cancellationTarget }.toSet())
        assertTrue(VoiceSpeechPlaybackProtocol.plans(pocketConfig).any { it.recoveryAfter != null })
    }

    @Test fun continuationPreservesCompletedEvidenceAndRetriesInterruptedStages() = runBlocking {
        val plan = stages.take(4)
        val rows = plan.map { VoiceBenchResult(it.label) }
        val store = VoiceBenchmarkSuiteStore(temp.newFolder())
        val initial = manifest(rows).copy(continuationSignature = "signature")
        store.begin(initial, VoicePerformanceSnapshot(), null)
        store.evidence(initial.runId, 0) { it.write("""{"complete":1}""".toByteArray()) }
        store.evidence(initial.runId, 1) { it.write("""{"partial":2}""".toByteArray()) }
        val interrupted = initial.update(0, rows[0].copy(status = VoiceBenchStatus.MEASURED, measurements = 1))
            .update(1, rows[1].copy(status = VoiceBenchStatus.RUNNING)).stopped("CANCELLED", 10)
        store.save(interrupted)
        assertTrue(interrupted.canContinue("signature", initial.build, initial.commit, initial.androidApi, rows))
        assertFalse(interrupted.canContinue("other", initial.build, initial.commit, initial.androidApi, rows))
        assertFalse(interrupted.canContinue("signature", initial.build + 1, initial.commit, initial.androidApi, rows))
        assertFalse(interrupted.canContinue("signature", initial.build, initial.commit, initial.androidApi, rows.reversed()))
        val reused = interrupted.reusableStages(rows)
        assertEquals(listOf(0), reused)
        val next = manifest(rows, initial.startedAtMillis + 1).copy(
            continuationSignature = "signature",
            resumedFromRunId = interrupted.runId,
            reusedStageIndices = reused,
            results = rows.mapIndexed { index, row -> if (index in reused) interrupted.results[index] else row }
        )
        store.begin(next, VoicePerformanceSnapshot(), null, interrupted)
        val previous = requireNotNull(store.previous(next))
        assertEquals(interrupted, previous)
        val oldExport = ByteArrayOutputStream()
        store.export(previous, oldExport)
        assertTrue(oldExport.toString("UTF-8").contains("""{"partial":2}"""))
        val measured = mutableListOf<Int>()
        VoiceBenchmarkCampaign(store, {}).run(next, plan, { null }, { true }) { index, stage ->
            measured += index
            VoiceBenchResult(stage.label, VoiceBenchStatus.MEASURED)
        }
        val finished = requireNotNull(store.latest())
        assertEquals(listOf(1, 2, 3), measured)
        assertEquals("FINISHED", finished.state)
        val output = ByteArrayOutputStream()
        store.export(finished, output)
        val json = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
        assertEquals("1", json.getValue("stageEvidence").jsonArray[0].jsonObject.getValue("report").jsonObject.getValue("complete").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("stageEvidence").jsonArray[1].jsonObject["report"])
    }

    @Test fun emptyProbeIsNotSilentlyUnavailable() {
        val result = emptyList<VoiceBenchObservation>().summarize("capture")
        assertEquals(VoiceBenchStatus.UNAVAILABLE, result.status)
        assertEquals("NO_OBSERVATIONS", result.reason)
    }

    @Test fun microphoneAndRecognitionOmissionsKeepTheirIdentityAndCannotAppearMeasured() {
        for (probe in listOf(VoiceBenchProbe.MICROPHONE, VoiceBenchProbe.RECOGNITION)) {
            val stage = stages.first { it.probe == probe }
            val rows = voiceBenchProbeEvidence(stage, emptyList())
            val summary = rows.summarize(stage.label)
            assertEquals(VoiceBenchStatus.UNAVAILABLE, summary.status)
            assertEquals("${probe.name}_NO_OBSERVATIONS", summary.reason)
            assertEquals(0, summary.measurements)
            assertTrue(requireNotNull(rows.single().measurementScope).contains("NOT_"))
        }
        val stage = stages.first { it.probe == VoiceBenchProbe.MICROPHONE }
        val missing = voiceBenchProbeEvidence(stage, listOf(VoiceBenchObservation.unavailable("microphone", "MICROPHONE_PERMISSION_MISSING")))
        assertEquals("MICROPHONE_PERMISSION_MISSING", missing.summarize(stage.label).reason)
        val multiple = (missing + VoiceBenchObservation.unavailable("capture", "CAPTURE_INITIALIZATION_FAILED")).summarize(stage.label)
        assertEquals("MULTIPLE_OBSERVATION_REASONS", multiple.reason)
    }

    @Test fun exportsCountMissingAndInterruptedCoverageWithoutCallingItPassed() {
        val rows = listOf(
            VoiceBenchResult("pcm", VoiceBenchStatus.MEASURED, measurements = 5),
            VoiceBenchResult("capture", VoiceBenchStatus.UNAVAILABLE, "MICROPHONE_PERMISSION_MISSING"),
            VoiceBenchResult("native", VoiceBenchStatus.INTERRUPTED, "CANCELLED", measurements = 2),
            VoiceBenchResult("rates")
        )
        val initial = manifest(rows)
        val store = VoiceBenchmarkSuiteStore(temp.newFolder())
        store.begin(initial, VoicePerformanceSnapshot(), null)
        store.evidence(initial.runId, 0) { it.write("{\"samples\":5}".toByteArray()) }
        store.evidence(initial.runId, 2) { it.write("{\"samples\":2}".toByteArray()) }
        val output = ByteArrayOutputStream()
        store.export(initial.stopped("CANCELLED", 99), output)
        val json = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
        val coverage = json.getValue("coverage").jsonObject
        assertEquals(4, coverage.getValue("plannedStages").jsonPrimitive.int)
        assertEquals(2, coverage.getValue("stagesWithMeasurements").jsonPrimitive.int)
        assertEquals(7, coverage.getValue("recordedMeasurements").jsonPrimitive.int)
        assertEquals(1, coverage.getValue("statusCounts").jsonObject.getValue("INTERRUPTED").jsonPrimitive.int)
        assertEquals(1, coverage.getValue("unavailableReasonCounts").jsonObject.getValue("MICROPHONE_PERMISSION_MISSING").jsonPrimitive.int)
        assertEquals("OBSERVATIONS_NOT_PASSED_TESTS_OR_QUALIFIED_ENGINES", coverage.getValue("measurementScope").jsonPrimitive.content)
        assertEquals("PREEXISTING_SNAPSHOT_ATTACHED_AT_CAMPAIGN_START_NOT_BENCHMARK_STAGE_TIMINGS", json.getValue("liveSessionPerformanceWindow").jsonPrimitive.content)
        assertEquals("MICROPHONE_PERMISSION_MISSING", json.getValue("stageEvidence").jsonArray[1].jsonObject.getValue("stageReason").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("stageEvidence").jsonArray[1].jsonObject["report"])
    }

    @Test fun playbackStageSummaryRetainsStallsAndFailuresWithoutPromotingCompletedEngineState() {
        val stage = stages.first { it.engine == VoiceSpeechBenchmarkEngine.POCKET && it.playback == listOf(VoiceSpeechPlaybackScenario.COLD_START) }
        val config = stage.configuration(KokoroVoice.HEART, "alba", 0, 0, 153, 36, "fixture", "a".repeat(40))
        val sample = VoiceSpeechPlaybackResult(
            VoiceSpeechBackend.POCKET_CPU, VoiceSpeechPlaybackState.COMPLETE, firstPresentedAudioMs = 300, totalAudioMs = 1_000, chunks = 1,
            protocolVersion = VoiceSpeechPlaybackProtocol.VERSION, thermalBefore = 0, thermalAfter = 0, activePlaybackUnderruns = 0,
            manifestWordsExpected = 1, manifestWordsPrepared = 1, manifestWordsObserved = 1, renderedManifestWords = 1, unexpectedCaptionWords = 0,
            cleanupState = "RETIRED", requestedDelegate = "CPU", observedProviderStatus = "NOT_EXPOSED_BY_SDK", engineStreamCalls = 1, streamedAudioValidated = true
        )
        val report = VoiceSpeechBenchmarkReport(1, config, state = VoiceSpeechBenchmarkRunState.COMPLETE, engineResults = listOf(VoiceSpeechEngineBenchmarkResult(VoiceSpeechBackend.POCKET_CPU, VoiceSpeechBenchmarkBackendState.COMPLETE)), playbackResults = listOf(sample))
        assertEquals(VoiceSpeechPlaybackQuality.STREAMING_PLAYBACK_OBSERVED, sample.quality)
        assertEquals(VoiceBenchStatus.MEASURED, report.suiteResult(stage.label).status)
        assertNull(report.suiteResult(stage.label).recommendedRoute)
        val stalled = report.copy(playbackResults = listOf(sample.copy(bufferRestarts = 1))).suiteResult(stage.label)
        assertEquals(VoiceBenchStatus.PARTIAL, stalled.status)
        assertEquals("PLAYBACK_QUALITY_INCOMPLETE", stalled.reason)
        val failed = report.copy(playbackResults = listOf(sample.copy(state = VoiceSpeechPlaybackState.FAILED, chunks = 0))).suiteResult(stage.label)
        assertEquals(VoiceBenchStatus.FAILED, failed.status)
        assertEquals("NO_SUCCESSFUL_PLAYBACK_OBSERVATIONS", failed.reason)
    }

    @Test fun deviceThreadLimitsAndInvalidRatesCannotCreateUnrunnableStages() {
        for (count in listOf(0, 1, 4, 32)) {
            val plan = fullVoiceBenchPlan(64, Float.NaN, count)
            assertTrue(plan.all { it.threads in 1..count.coerceIn(1, 16) && it.rate.isFinite() && it.rate in .5f..2f })
            assertTrue(plan.filter { it.calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS }.all { it.rate == 1f })
        }
    }

    @Test fun onlyNativeAutoCasesUseCalibrationAndExplicitSweepsStayFixed() {
        val native = stages.filter { it.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO }
        assertTrue(native.filterNot { it.label.contains("CPU threads") }.all { it.useCalibratedThreads })
        assertTrue(native.filter { it.label.contains("CPU threads") }.all { !it.useCalibratedThreads })
        assertTrue(stages.filterNot { it.engine == VoiceSpeechBenchmarkEngine.NATIVE_KOKORO }.none { it.useCalibratedThreads })
        assertTrue(fullVoiceBenchPlan(2, 1f, 8).none { it.useCalibratedThreads })
    }

    @Test fun currentCalibrationUsesTheSelectedStreamingVoiceOnlyInItsOwnPack() {
        fun config(engine: VoiceSpeechBenchmarkEngine, id: String) = stages.first {
            it.engine == engine && it.calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS
        }.configuration(KokoroVoice.HEART, id, 90, 0, 153, 37, "fixture", "a".repeat(40))
        assertEquals("marius", config(VoiceSpeechBenchmarkEngine.POCKET, "marius").engineVoiceId)
        assertEquals("rafael", config(VoiceSpeechBenchmarkEngine.POCKET_MULTILINGUAL, "rafael").engineVoiceId)
        assertEquals("claire", config(VoiceSpeechBenchmarkEngine.PHONON, "claire").engineVoiceId)
        assertEquals("alba", config(VoiceSpeechBenchmarkEngine.POCKET, "rafael").engineVoiceId)
        assertEquals("af_heart", config(VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO, "marius").engineVoiceId)
        assertEquals("HEART", config(VoiceSpeechBenchmarkEngine.NATIVE_KOKORO, "marius").engineVoiceId)
    }

    @Test fun fullCampaignCoversBundledVoicesAndPitchSensitivePlaybackRates() {
        for (engine in VoiceSpeechBenchmarkEngine.entries) {
            val part = stages.filter { it.engine == engine }
            assertTrue(part.filter { it.playback.isNotEmpty() }.map { it.rate }.containsAll(listOf(.75f, 1.25f, 1.5f, 2f)))
            engine.mediaPipeEngine()?.takeUnless { it.isKokoro }?.let { sdk ->
                assertEquals(MediaPipeSpeechVoices.ids(sdk).toSet(), part.mapNotNull { it.selectedVoiceId }.toSet())
            }
        }
    }

    @Test fun compiledPocketRetainsSeparateArtifactRuntimeVoicesAndActualInputContract() {
        val compiled = stages.filter { it.engine == VoiceSpeechBenchmarkEngine.POCKET_COMPILED }
        assertEquals(setOf(VoiceSpeechBackend.POCKET_GPU, VoiceSpeechBackend.POCKET_NPU), compiled.flatMap { it.backends }.toSet())
        assertEquals(PocketCompiledSpeechCatalog.VOICES.toSet(), compiled.mapNotNull { it.selectedVoiceId }.toSet())
        assertTrue(compiled.none { it.incremental || it.useCalibratedThreads })
        val configuration = compiled.first { it.calibration == VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS }
            .copy(threads = 16).configuration(KokoroVoice.HEART, "mary", 90, 0, 153, 36, "fixture", "a".repeat(40))
        assertEquals(PocketCompiledSpeechCatalog.MODEL_SHA256, configuration.modelSha256)
        assertEquals(PocketCompiledSpeechCatalog.RUNTIME_ID, configuration.runtimeId)
        assertEquals("mary", configuration.engineVoiceId)
        assertEquals(16, configuration.threads)
        assertEquals("GPU", requestedSpeechDelegate(VoiceSpeechBackend.POCKET_GPU))
        assertEquals("NPU", requestedSpeechDelegate(VoiceSpeechBackend.POCKET_NPU))
        val pocketSpeed = stages.indexOfFirst { it.engine == VoiceSpeechBenchmarkEngine.POCKET && it.rate == 2f && it.playback.isNotEmpty() }
        assertTrue(stages.indexOf(compiled.first()) > pocketSpeed)
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
            assertEquals("TEXT_MAPPING_NOT_SPOKEN_PRONUNCIATION", dictionary.measurementScope)
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
