package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceSpeechBenchmarkStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report(at: Long, state: VoiceSpeechBenchmarkRunState = VoiceSpeechBenchmarkRunState.COMPLETE) = VoiceSpeechBenchmarkReport(
        at,
        VoiceSpeechBenchmarkConfiguration(
            VoiceSpeechBenchmarkMode.QUICK, listOf(VoiceSpeechBackend.CPU), KokoroVoice.HEART, 2, 1f,
            "a".repeat(64), "runtime", 147, 34, "b".repeat(64)
        ),
        state = state
    )

    @Test fun savesReplaceTheCurrentRunAndRetainOnlyFiveIndependentRuns() {
        val directory = temporary.newFolder()
        val store = VoiceSpeechBenchmarkStore(directory)
        for (at in 1L..7L) assertTrue(store.save(report(at)))
        assertTrue(store.save(report(7).copy(issue = VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE)))
        val restored = VoiceSpeechBenchmarkStore(directory).read()
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), restored.map { it.startedAtMillis })
        assertEquals(VoiceSpeechBenchmarkIssue.MODEL_UNAVAILABLE, restored.first().issue)
    }

    @Test fun processInterruptionRestoresPartialResultsWithoutClaimingCompletion() {
        val directory = temporary.newFolder()
        val store = VoiceSpeechBenchmarkStore(directory)
        val pending = VoiceSpeechBenchmarkEnergyObservation(VoiceSpeechBackend.CPU, VoiceSpeechEnergyEndpoint(1000, VoiceSpeechEnergyChargeState.DISCHARGING, false), monitorRegistered = true)
        assertTrue(store.save(report(1, VoiceSpeechBenchmarkRunState.RUNNING).copy(energyObservations = listOf(pending))))
        val restored = VoiceSpeechBenchmarkStore(directory).read().single()
        assertEquals(VoiceSpeechBenchmarkRunState.INTERRUPTED, restored.state)
        assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, restored.results.single().state)
        assertFalse(restored.results.single().verified)
        assertNull(restored.recommendation())
        val energy = restored.energyObservations.single()
        assertEquals(VoiceSpeechEnergyStatus.INTERRUPTED, energy.status)
        assertEquals(pending.start, energy.start)
        assertNull(energy.end)
        assertNull(energy.observedCounterDecreaseNanowattHours)
        assertEquals(restored, speechBenchmarkReportFromJson(speechBenchmarkJson(restored, local = true)))
    }

    @Test fun corruptAndOversizedHistoryCannotProduceBenchmarksAndDoesNotBlockANewRun() {
        val directory = temporary.newFolder()
        val file = File(directory, "results-v1.json")
        file.writeText("not a report")
        val store = VoiceSpeechBenchmarkStore(directory)
        assertTrue(store.read().isEmpty())
        file.outputStream().use { output ->
            output.write(JsonArray(listOf(speechBenchmarkJson(report(1), local = true))).toString().toByteArray())
            val padding = ByteArray(8192) { ' '.code.toByte() }
            repeat(1024) { output.write(padding) }
        }
        assertTrue(file.length() > 8 * 1024 * 1024)
        assertTrue(store.read().isEmpty())
        assertTrue(store.save(report(10)))
        assertEquals(10L, store.read().single().startedAtMillis)
    }

    @Test fun fullCurrentProtocolEvidenceAboveTwoMiBSurvivesWhileOlderRunsAreEvicted() {
        // Preserve the retained selector; the current fixture also exceeds the former 4 MiB limit.
        // Exercise the allowed trace envelope, not a claim that this schedule occurred on hardware.
        val config = report(1).configuration.copy(
            mode = VoiceSpeechBenchmarkMode.FULL,
            backends = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS,
            playback = true,
            playbackScenarios = VoiceSpeechPlaybackScenario.entries,
            sustainedTargetAudioMs = 600_000
        )
        val rows = config.backends.flatMap { backend ->
            var sustainedAudioMs = 0L
            VoiceSpeechPlaybackProtocol.expandedPlans(config, VoiceSpeechSustainedSeries.MAX_REPLIES).map { plan ->
                val provider = VoiceSpeechBenchmarkBackendResult.provider(backend)
                val phrases = plan.segments.flatMap { checkNotNull(KokoroSpeechText.timed(it) { true }.phrases) }
                val chunks = phrases.mapIndexed { index, phrase ->
                    VoiceSpeechPlaybackChunk(
                        index, (index + 1L) * 100, 100, 1_000, phrase.speech.toByteArray().size,
                        VoiceSpeechWorkload.digest(phrase.speech), provider, true, null, null, null
                    )
                }
                val sustained = plan.scenario == VoiceSpeechPlaybackScenario.SUSTAINED
                if (sustained) sustainedAudioMs += chunks.sumOf { it.audioMs }
                VoiceSpeechPlaybackResult(
                    backend, VoiceSpeechPlaybackState.COMPLETE, firstPresentedAudioMs = 100, elapsedMs = 30_000,
                    totalSynthesisMs = chunks.sumOf { it.generationMs }, totalAudioMs = chunks.sumOf { it.audioMs }, chunks = chunks.size, validatedChunks = chunks.size,
                    protocolVersion = VoiceSpeechPlaybackProtocol.VERSION, scenario = plan.scenario, repeat = plan.repeat,
                    warmup = plan.warmup, expectedResident = plan.expectedResident, engineReused = plan.expectedResident, fixtureHash = plan.manifest.hash,
                    manifestWordsExpected = plan.manifest.spans.size, manifestWordsPrepared = plan.manifest.spans.size,
                    manifestWordsObserved = plan.manifest.spans.size, unexpectedCaptionWords = 0, chunkRecords = chunks, cancellationTarget = plan.cancellationTarget,
                    arrivalIntervalMs = plan.arrivalIntervalMs, cleanupState = "RETIRED",
                    sustainedTargetAudioMs = config.sustainedTargetAudioMs.takeIf { sustained },
                    sustainedObservedAudioMs = sustainedAudioMs.takeIf { sustained },
                    sustainedWallElapsedMs = ((plan.repeat + 1) * 30_000L).takeIf { sustained },
                    sustainedWallDeadlineMs = VoiceSpeechSustainedSeries.deadlineMs(config.sustainedTargetAudioMs).takeIf { sustained },
                    sustainedCompletedRepeats = (plan.repeat + 1).takeIf { sustained },
                    sustainedQualification = if (!sustained) {
                        null
                    } else if (plan.repeat == VoiceSpeechSustainedSeries.MAX_REPLIES - 1) {
                        "INSUFFICIENT_DURATION"
                    } else {
                        "IN_PROGRESS"
                    },
                    progress = List(128) { VoiceSpeechProgressObservation(VoiceSpeechStage.PREPARING, 10, it + 1L, 29_000, 29_999, false) },
                    reserves = List(128) { VoiceSpeechReserveObservation("refill", 100, 5900, 5999, "producer_idle", it * 100L, it * 100L + 100) },
                    calibrations = List(32) { VoiceSpeechCalibrationObservation("bluetooth", 0, it.toLong()) }
                )
            }
        }
        val large = VoiceSpeechBenchmarkReport(
            1,
            config,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = config.backends.map { VoiceSpeechBenchmarkBackendResult(it, VoiceSpeechBenchmarkBackendState.COMPLETE) },
            playbackResults = rows
        )
        val bytes = Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(speechBenchmarkJson(large, local = true)))).toByteArray().size
        assertEquals(39 * config.backends.size, rows.size)
        assertEquals(16 * config.backends.size, rows.count { it.scenario == VoiceSpeechPlaybackScenario.SUSTAINED })
        assertTrue("The regression must exercise the previous capacity limit", bytes > 4 * 1024 * 1024)
        assertTrue(bytes < 8 * 1024 * 1024)
        val directory = temporary.newFolder()
        val store = VoiceSpeechBenchmarkStore(directory)
        assertTrue(store.save(large))
        assertTrue(store.save(report(2)))
        val newest = large.copy(startedAtMillis = 3)
        assertTrue(store.save(newest))
        val restored = VoiceSpeechBenchmarkStore(directory).read()
        assertEquals(listOf(3L, 2L), restored.map { it.startedAtMillis })
        assertEquals(newest, restored.first())
        assertTrue(File(directory, "results-v1.json").length() <= 8 * 1024 * 1024)
    }

    @Test fun interruptedUtteranceKeepsItsCompletedChunksAndOriginalOrderPlan() {
        val store = VoiceSpeechBenchmarkStore(temporary.newFolder())
        val phrase = VoiceSpeechWorkload.phrases(VoiceSpeechBenchmarkFixture.LONG).first()
        val chunk = VoiceSpeechBenchmarkChunk(
            0, phrase.length, VoiceSpeechWorkload.digest(phrase), 100, 75.0, "cpu", 0, 0, 0, 0, 0, 3, true, 0, 0, -1,
            profileAvailable = false, currentPrepareMs = 0
        )
        val initial = report(1, VoiceSpeechBenchmarkRunState.RUNNING)
        val partial = initial.copy(
            configuration = initial.configuration.copy(backendOrderSeed = 2, sourceCommit = "c".repeat(40)),
            results = listOf(
                initial.results.single().copy(
                    state = VoiceSpeechBenchmarkBackendState.RUNNING,
                    incompleteFixture = VoiceSpeechBenchmarkFixture.LONG,
                    incompleteRepeat = 1,
                    incompleteChunks = listOf(chunk)
                )
            )
        )
        assertTrue(store.save(partial))
        val restored = store.read().single()
        assertEquals(VoiceSpeechBenchmarkRunState.INTERRUPTED, restored.state)
        assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, restored.results.single().state)
        assertEquals(listOf(chunk), restored.results.single().incompleteChunks)
        assertEquals(VoiceSpeechBenchmarkFixture.LONG, restored.results.single().incompleteFixture)
        assertEquals(1, restored.results.single().incompleteRepeat)
        assertEquals(2L, restored.configuration.backendOrderSeed)
        assertEquals("c".repeat(40), restored.configuration.sourceCommit)
        assertEquals(1, restored.completedChunks)
        assertNull(restored.recommendation())
    }

    @Test fun legacyHistoryRemainsHistoricalAfterANewRunIsSaved() {
        val directory = temporary.newFolder()
        val original = speechBenchmarkJson(report(1), local = true)
        val legacy = JsonObject(original + mapOf("schemaVersion" to JsonPrimitive(1), "fixtureVersion" to JsonPrimitive(1)))
        File(directory, "results-v1.json").writeText(Json.encodeToString(JsonArray.serializer(), JsonArray(listOf(legacy))))
        val store = VoiceSpeechBenchmarkStore(directory)
        val old = store.read().single()
        assertEquals("legacy-fixtures-v1", old.configuration.protocolId)
        assertFalse(old.configuration.currentProtocol)
        assertEquals(VoiceSpeechBenchmarkOutcome.HISTORICAL, old.outcome)
        assertTrue(store.save(report(2)))
        val retained = store.read().last()
        assertEquals(old, retained)
        assertNull(retained.recommendation())
    }
}
