package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
        assertNull(fresh.captionAsrAlignmentTimingWords)
    }

    @Test fun asrAlignmentCountsStayDistinctAndDoNotQualifyIndependentAcousticAccuracy() {
        val measurement = VoiceSpeechPlaybackMeasurement(gpu, 0)
        repeat(3) { measurement.captionTimingObserved(0, 0..3, VoiceWordCueProvenance.ASR_ALIGNMENT) }
        val measured = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(1, measured.captionAsrAlignmentTimingWords)
        assertEquals(0, measured.captionNativeTimingWords)
        assertEquals(0, measured.captionEstimatedTimingWords)
        assertEquals(0, measured.captionTimingRevisions)

        val original = report()
        val aligned = original.copy(
            playbackResults = original.playbackResults.map {
                it.copy(captionEstimatedTimingWords = 0, captionAsrAlignmentTimingWords = it.captionEstimatedTimingWords)
            }
        )
        val comparisons = aligned.streamingComparisons()
        assertTrue(comparisons.all { it.cueProvenance == "ASR_ALIGNMENT" && checkNotNull(it.asrAlignmentTimingWords) > 0 })
        assertTrue(comparisons.all { it.eligibleForLatencyComparison && !it.qualifiedForAutomaticSelection && it.independentQualityStatus == "NOT_MEASURED" })
        val document = speechBenchmarkJson(aligned, local = true)
        assertEquals(aligned, speechBenchmarkReportFromJson(document))
        val exported = document.getValue("streamingComparisons").jsonArray.first().jsonObject
        assertEquals(JsonPrimitive("ASR_ALIGNMENT"), exported["cueProvenance"])
        assertEquals(JsonPrimitive("TEXT_BOUNDARIES_OBSERVED_NOT_ACOUSTIC_WORD_ACCURACY"), exported["captionCoverageScope"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), exported["independentQualityStatus"])
        assertEquals(JsonPrimitive(false), exported["qualifiedForAutomaticSelection"])
    }

    @Test fun newAsrCountsPreserveLegacyReportsButRejectMissingOrImpossibleCounts() {
        val original = report()
        fun edited(report: VoiceSpeechBenchmarkReport, edit: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
            val document = speechBenchmarkJson(report, local = true)
            return JsonObject(document + ("playbackResults" to JsonArray(document.getValue("playbackResults").jsonArray.map { JsonObject(edit(it.jsonObject)) })))
        }
        val legacy = edited(original) { it - setOf("captionAsrAlignmentTimingWords", "captionAlignmentStatuses") }
        assertEquals(original, speechBenchmarkReportFromJson(legacy))
        assertTrue(original.streamingComparisons().all { it.asrAlignmentTimingWords == null && it.cueProvenance == "ESTIMATED" })
        for (invalid in listOf(-1, VoiceSpeechCaptionManifest.MAX_WORDS + 1)) {
            assertNull(speechBenchmarkReportFromJson(edited(original) { it + ("captionAsrAlignmentTimingWords" to JsonPrimitive(invalid)) }))
        }
        val aligned = original.copy(
            playbackResults = original.playbackResults.map {
                it.copy(captionEstimatedTimingWords = 0, captionAsrAlignmentTimingWords = it.captionEstimatedTimingWords)
            }
        )
        assertNull(speechBenchmarkReportFromJson(edited(aligned) { it - "captionAsrAlignmentTimingWords" }))
        assertNull(speechBenchmarkReportFromJson(edited(aligned) { it - "captionNativeTimingWords" }))
        val absent = original.copy(playbackResults = original.playbackResults.map { it.copy(captionNativeTimingWords = 0, captionEstimatedTimingWords = 0) })
        assertTrue(absent.streamingComparisons().all { !it.eligibleForLatencyComparison })
    }

    @Test fun mixedAsrAndSdkSourcesRemainVisibleRatherThanCollapsingIntoNativeTiming() {
        val row = report().streamingComparisons().first()
        assertEquals("MIXED_ESTIMATED_AND_ASR_ALIGNMENT", row.copy(asrAlignmentTimingWords = 1).cueProvenance)
        assertEquals("MIXED_NATIVE_DURATION_AND_ASR_ALIGNMENT", row.copy(nativeTimingWords = 1, estimatedTimingWords = 0, asrAlignmentTimingWords = 1).cueProvenance)
        assertEquals("MIXED_NATIVE_DURATION_ESTIMATED_AND_ASR_ALIGNMENT", row.copy(nativeTimingWords = 1, asrAlignmentTimingWords = 1).cueProvenance)
    }

    @Test fun captionPackFailuresAreBoundedObservedReasonsAndNeverInventedCueSuccess() {
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.POCKET_GPU, 0)
        fun observed(summary: String) = VoiceSpeechExecutionEvidence(
            VoiceSpeechBackend.POCKET_GPU,
            "litert-compiled-pocket-gpu-requested",
            "a".repeat(64),
            "fixture-runtime",
            "fixture-device",
            VoiceSpeechStage.READY,
            observedAtMillis = 0,
            discoverySummary = summary
        )
        for (summary in listOf("caption_status=PACK_UNAVAILABLE;caption_words=0", "caption_status=TEXT_MISMATCH", "caption_status=PACK_UNAVAILABLE")) {
            measurement.pcmReady(10, 240, 24_000, observed(summary))
        }
        for (summary in listOf("caption_status=private transcript", "caption_status=ALIGNED;caption_status=DISABLED", "caption_status=ALIGNED;" + "a".repeat(512))) {
            measurement.pcmReady(10, 240, 24_000, observed(summary))
        }
        val measured = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(setOf("PACK_UNAVAILABLE", "TEXT_MISMATCH"), measured.captionAlignmentStatuses)
        assertNull(measured.captionAsrAlignmentTimingWords)
        val original = report()
        val missing = original.copy(
            playbackResults = original.playbackResults.map {
                it.copy(captionAlignmentStatuses = setOf("PACK_UNAVAILABLE"), captionNativeTimingWords = null, captionEstimatedTimingWords = null, captionTimingRevisions = null, manifestWordsObserved = 0, renderedManifestWords = 0)
            }
        )
        assertTrue(missing.streamingComparisons().all { it.captionAlignmentStatuses == setOf("PACK_UNAVAILABLE") && !it.eligibleForLatencyComparison && !it.qualifiedForAutomaticSelection })
        assertEquals(missing, speechBenchmarkReportFromJson(speechBenchmarkJson(missing, local = true)))
    }
}
