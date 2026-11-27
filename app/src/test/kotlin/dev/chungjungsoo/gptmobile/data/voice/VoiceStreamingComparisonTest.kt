package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamingComparisonTest {
    private val cpu = VoiceSpeechBackend.LITERT_KOKORO_CPU
    private val gpu = VoiceSpeechBackend.LITERT_KOKORO_GPU
    private val routeIdentity = "speaker:" + "d".repeat(32)

    private fun report(): VoiceSpeechBenchmarkReport {
        val config = VoiceSpeechBenchmarkConfiguration(
            VoiceSpeechBenchmarkMode.FULL, listOf(cpu, gpu), KokoroVoice.HEART, 2, 1.5f,
            "a".repeat(64), "fixture-runtime", 153, 37, "b".repeat(64),
            playback = true,
            playbackScenarios = VoiceSpeechPlaybackScenario.entries - VoiceSpeechPlaybackScenario.SUSTAINED,
            engine = VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO,
            engineVoiceId = "af_heart"
        )
        val rows = config.backends.flatMap { backend ->
            VoiceSpeechPlaybackProtocol.plans(config).map { plan ->
                val cancelled = plan.cancellationTarget != null
                val words = plan.manifest.spans.size
                VoiceSpeechPlaybackResult(
                    requestedBackend = backend,
                    state = if (cancelled) VoiceSpeechPlaybackState.CANCELLED else VoiceSpeechPlaybackState.COMPLETE,
                    firstPresentedAudioMs = if (cancelled) {
                        null
                    } else if (backend == gpu) {
                        90
                    } else {
                        140
                    },
                    totalSynthesisMs = if (cancelled) 0 else 50,
                    totalAudioMs = if (cancelled) 0 else 1_000,
                    chunks = if (cancelled) 0 else 1,
                    cancellationMs = 80L.takeIf { cancelled },
                    route = "speaker",
                    thermalBefore = 0,
                    thermalAfter = 0,
                    captionWordsExpected = words,
                    captionWordsObserved = words,
                    protocolVersion = VoiceSpeechPlaybackProtocol.VERSION,
                    scenario = plan.scenario,
                    repeat = plan.repeat,
                    warmup = plan.warmup,
                    expectedResident = plan.expectedResident,
                    engineReused = plan.expectedResident,
                    fixtureHash = plan.manifest.hash,
                    manifestWordsExpected = words,
                    manifestWordsPrepared = words,
                    manifestWordsObserved = words,
                    renderedManifestWords = words,
                    unexpectedCaptionWords = 0,
                    activePlaybackUnderruns = 0,
                    chunkRecords = if (cancelled) emptyList() else listOf(VoiceSpeechPlaybackChunk(0, 50, 50, 1_000, null, null, null, false, null, null, null)),
                    calibrations = listOf(VoiceSpeechCalibrationObservation("speaker", 0, 0)),
                    cancellationTarget = plan.cancellationTarget,
                    cancellationTargetReached = true.takeIf { cancelled },
                    cleanupState = "RETIRED",
                    arrivalIntervalMs = plan.arrivalIntervalMs,
                    streamedAudioValidated = true,
                    requestedDelegate = requestedSpeechDelegate(backend),
                    observedProviderStatus = "NOT_EXPOSED_BY_SDK",
                    engineStreamCalls = if (cancelled) 0 else 1,
                    prewarmMode = plan.prewarmMode,
                    recoveryAfter = plan.recoveryAfter,
                    captionNativeTimingWords = 0,
                    captionEstimatedTimingWords = words,
                    captionTimingRevisions = 0,
                    outputRouteIdentity = routeIdentity,
                    outputRouteIdentityChanges = 0
                )
            }
        }
        return VoiceSpeechBenchmarkReport(
            1,
            config,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            finishedAtMillis = 2,
            playbackResults = rows,
            engineResults = config.backends.map { VoiceSpeechEngineBenchmarkResult(it, VoiceSpeechBenchmarkBackendState.COMPLETE) }
        )
    }

    @Test fun matchedMeasuredLatencyCandidateDoesNotClaimAcousticQualityOrAutomaticQualification() {
        val report = report()
        val rows = report.streamingComparisons()
        assertTrue(rows.all { it.eligibleForLatencyComparison })
        assertEquals(gpu, report.measuredStreamingCandidate())
        assertNull(report.recommendation())
        assertTrue(rows.all { it.independentQualityStatus == "NOT_MEASURED" && !it.qualifiedForAutomaticSelection })
        assertTrue(rows.all { it.cueProvenance == "ESTIMATED" && it.captionCoverage == 1.0 })
        assertTrue(rows.all { it.p95FirstPresentedMs == null })
    }

    @Test fun missingMeasurementsDifferentRoutesAndStallsCannotWinLatencySelection() {
        val original = report()
        fun changed(update: (VoiceSpeechPlaybackResult) -> VoiceSpeechPlaybackResult) = original.copy(playbackResults = original.playbackResults.map { if (it.requestedBackend == gpu) update(it) else it })
        assertNull(changed { it.copy(activePlaybackUnderruns = 1) }.measuredStreamingCandidate())
        assertNull(changed { it.copy(activePlaybackUnderruns = null) }.measuredStreamingCandidate())
        assertNull(changed { it.copy(route = "bluetooth") }.measuredStreamingCandidate())
        assertNull(changed { it.copy(outputRouteIdentity = "speaker:" + "e".repeat(32)) }.measuredStreamingCandidate())
        assertNull(changed { it.copy(outputRouteIdentity = null, outputRouteIdentityChanges = null) }.measuredStreamingCandidate())
        assertNull(changed { it.copy(outputRouteIdentityChanges = 1) }.measuredStreamingCandidate())
        assertNull(changed { it.copy(captionEstimatedTimingWords = null) }.measuredStreamingCandidate())
        assertNull(changed { it.copy(engineReused = false) }.measuredStreamingCandidate())
        val incomplete = original.copy(playbackResults = original.playbackResults.dropLast(1))
        assertNull(incomplete.measuredStreamingCandidate())
        assertNull(original.copy(state = VoiceSpeechBenchmarkRunState.INTERRUPTED).measuredStreamingCandidate())
        assertNull(original.copy(configuration = original.configuration.copy(pcmProcessingId = null)).measuredStreamingCandidate())
        assertNull(original.copy(configuration = original.configuration.copy(pcmProcessingId = "old-sdk-resampling")).measuredStreamingCandidate())
    }

    @Test fun independentQualityIsBoundToVoiceModelRateAndActualFixturesAndRoundTrips() {
        val original = report()
        val c = original.configuration
        val q = VoiceIndependentSpeechQuality(
            gpu, c.modelSha256, c.runtimeId, requireNotNull(c.engineVoiceId), c.speechRate, "speaker",
            original.playbackResults.filter { it.requestedBackend == gpu && it.cancellationTarget == null && !it.warmup }.mapNotNull { it.fixtureHash }.toSet(),
            pitchDeviationPercent = 1.0, pitchPairs = 20, wordTimingP95Ms = 80.0, alignedWords = 100, pronunciationPassRate = 1.0, ratedWords = 20, outputRouteIdentity = routeIdentity
        )
        val qualified = original.copy(independentSpeechQuality = listOf(q))
        assertTrue(qualified.streamingComparisons().single { it.backend == gpu }.qualifiedForAutomaticSelection)
        assertEquals(qualified, speechBenchmarkReportFromJson(speechBenchmarkJson(qualified, local = true)))
        val foreignVoice = original.copy(independentSpeechQuality = listOf(q.copy(voiceId = "af_bella")))
        assertFalse(foreignVoice.streamingComparisons().single { it.backend == gpu }.qualifiedForAutomaticSelection)
        assertNull(speechBenchmarkReportFromJson(speechBenchmarkJson(foreignVoice, local = true)))
        val otherDevice = original.copy(independentSpeechQuality = listOf(q.copy(outputRouteIdentity = "speaker:" + "e".repeat(32))))
        assertFalse(otherDevice.streamingComparisons().single { it.backend == gpu }.qualifiedForAutomaticSelection)
        val badPitch = original.copy(independentSpeechQuality = listOf(q.copy(pitchDeviationPercent = 20.0)))
        assertNull(badPitch.measuredStreamingCandidate())
        assertEquals("QUALITY_TARGET_NOT_MET", badPitch.streamingComparisons().single { it.backend == gpu }.independentQualityStatus)
    }

    @Test fun outputRouteIdentityRetainsOnlyOpaqueDeviceIdentityAndMarksChanges() {
        val measurement = VoiceSpeechPlaybackMeasurement(gpu, 0)
        assertNull(measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0).outputRouteIdentity)
        measurement.outputRouteIdentity(routeIdentity)
        measurement.outputRouteIdentity(routeIdentity)
        assertEquals(0, measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0).outputRouteIdentityChanges)
        val changed = "bluetooth:" + "a".repeat(32)
        measurement.outputRouteIdentity(changed)
        measurement.terminal(0, 0, 0, "audio_timestamp", "bluetooth")
        measurement.outputRouteIdentity(routeIdentity)
        val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(changed, result.outputRouteIdentity)
        assertEquals(1, result.outputRouteIdentityChanges)
        val lost = VoiceSpeechPlaybackMeasurement(gpu, 0)
        lost.outputRouteIdentity(routeIdentity)
        lost.outputRouteIdentity(null)
        assertNull(lost.result(VoiceSpeechPlaybackState.COMPLETE, 0).outputRouteIdentity)
        assertEquals(1, lost.result(VoiceSpeechPlaybackState.COMPLETE, 0).outputRouteIdentityChanges)
        lost.outputRouteIdentity("speaker:Private headphones")
        assertNull(lost.result(VoiceSpeechPlaybackState.COMPLETE, 0).outputRouteIdentity)
        assertEquals(1, lost.result(VoiceSpeechPlaybackState.COMPLETE, 0).outputRouteIdentityChanges)
        val original = report()
        for (changes in listOf(0, 1)) {
            val unknown = original.copy(playbackResults = original.playbackResults.map { it.copy(outputRouteIdentity = null, outputRouteIdentityChanges = changes) })
            assertEquals(unknown, speechBenchmarkReportFromJson(speechBenchmarkJson(unknown, local = true)))
            assertNull(unknown.measuredStreamingCandidate())
        }
    }

    @Test fun enginePcmScopeReflectsTheRecordedProcessorAndPreservesLegacyFieldCompatibility() {
        val original = report()
        for ((processor, scope) in listOf(
            VoiceTempoProcessor.IMPLEMENTATION_ID to "ENGINE_CALLBACKS_AFTER_APP_TEMPO_BEFORE_PAUSE_TRIMMING",
            null to "LEGACY_ENGINE_CALLBACK_PROCESSING_UNRECORDED",
            "other-processor" to "ENGINE_CALLBACK_PROCESSING_UNRECOGNIZED"
        )) {
            val value = original.copy(
                configuration = original.configuration.copy(pcmProcessingId = processor),
                playbackResults = original.playbackResults.map {
                    if (it.cancellationTarget != null) it else it.copy(firstSdkPcmAfterStreamStartMs = 20, sdkPcm = VoicePcmSignal(24_000, .1, .2, 0, 24_000))
                }
            )
            val document = speechBenchmarkJson(value, local = true)
            val row = document.getValue("playbackResults").jsonArray.first().jsonObject
            assertEquals(JsonPrimitive(scope), row["enginePcmObservationScope"])
            assertEquals(row["firstSdkPcmAfterStreamStartMs"], row["firstEnginePcmAfterStreamStartMs"])
            assertEquals(row["sdkPcmSignal"], row["enginePcmSignal"])
            assertEquals(JsonPrimitive(scope), row.getValue("enginePcmSignal").jsonObject["scope"])
            assertEquals(value, speechBenchmarkReportFromJson(document))
        }
    }

    @Test fun provenanceCountsDeduplicateWordsAndCountOnlyNewerCorrections() {
        val measurement = VoiceSpeechPlaybackMeasurement(gpu, 0)
        measurement.captionTimingObserved(0, 0..3, VoiceWordCueProvenance.ESTIMATED)
        measurement.captionTimingObserved(0, 0..3, VoiceWordCueProvenance.ESTIMATED)
        measurement.captionTimingObserved(0, 0..3, VoiceWordCueProvenance.ESTIMATED, 2)
        measurement.captionTimingObserved(0, 0..3, VoiceWordCueProvenance.ESTIMATED, 1)
        measurement.captionTimingObserved(0, 5..9, VoiceWordCueProvenance.NATIVE_DURATION)
        val row = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(1, row.captionEstimatedTimingWords)
        assertEquals(1, row.captionNativeTimingWords)
        assertEquals(1, row.captionTimingRevisions)
        val fresh = VoiceSpeechPlaybackMeasurement(cpu, 0).result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertNull(fresh.captionEstimatedTimingWords)
    }
}
