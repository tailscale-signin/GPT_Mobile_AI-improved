package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.TimedKokoro
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

class VoiceSpeechPlaybackProtocolTest {
    @Test fun stabilitySeparatesThreeWarmCohortsFromColdSamplesAndFitsTheBoundedReport() {
        val config = configuration().copy(mode = VoiceSpeechBenchmarkMode.STABILITY, playbackScenarios = VoiceSpeechPlaybackScenario.entries)
        val plans = VoiceSpeechPlaybackProtocol.expandedPlans(config, VoiceSpeechSustainedSeries.MAX_REPLIES)
        assertTrue(plans.size <= VoiceSpeechPlaybackProtocol.MAX_SAMPLES_PER_BACKEND)
        val varied = plans.filter { it.scenario == VoiceSpeechPlaybackScenario.VARIED_SHAPES }
        assertEquals(1, varied.count { it.warmup })
        for (fixture in VoiceSpeechBenchmarkFixture.entries) {
            assertEquals(20, varied.count { it.expectedResident && !it.warmup && it.segments == listOf(fixture.text) })
        }
        assertTrue(plans.filter { it.scenario == VoiceSpeechPlaybackScenario.COLD_START }.all { !it.expectedResident && !it.warmup })
    }

    @Test fun joinsSeparateRetainedSilenceTextWaitAndTrackRefill() {
        val tracker = VoiceSpeechJoinTracker()
        val head = floatArrayOf(0f, 0f, 1f)
        val tail = floatArrayOf(1f, 0f, 0f, 0f)
        assertNull(tracker.observe('t', VoiceSpeechJoinBoundary.TOKEN_LIMIT, tail, tail.copyOf(2), 0, 0, 0, 1000))
        val join = requireNotNull(tracker.observe('.', VoiceSpeechJoinBoundary.SOURCE_END, head, head.copyOfRange(1, 3), 2, 10, 0, 1000))
        assertEquals(VoiceSpeechJoinBoundary.TOKEN_LIMIT, join.boundary)
        assertEquals(listOf(3, 2, 1, 1), listOf(join.rawTailFrames, join.rawHeadFrames, join.retainedTailFrames, join.retainedHeadFrames))
        assertEquals(5L, join.rawQuietMs)
        assertEquals(2L, join.retainedQuietMs)
        assertEquals(13L, join.copy(refillMs = 11).readyUnpunctuatedGapMs)
        assertNull(join.copy(textWaitMs = 1).readyUnpunctuatedGapMs)
        assertNull(tracker.observe('s', VoiceSpeechJoinBoundary.WORD_TARGET, tail, tail, 4, 0, 0, 1000)?.readyUnpunctuatedGapMs)
    }

    private fun configuration(full: Boolean = false) = VoiceSpeechBenchmarkConfiguration(
        if (full) VoiceSpeechBenchmarkMode.FULL else VoiceSpeechBenchmarkMode.QUICK,
        listOf(VoiceSpeechBackend.CPU), KokoroVoice.HEART, 1, 1f, "a".repeat(64), "test-runtime", 147, 36, "b".repeat(64), playback = true
    )

    private fun evidence() = VoiceSpeechExecutionEvidence(
        VoiceSpeechBackend.CPU, "cpu", "a".repeat(64), "test-runtime", "b".repeat(64), VoiceSpeechStage.READY,
        verifiedAudio = true, observedAtMillis = 0, profileAvailable = false, profileId = 8,
        cacheHit = true, currentPrepareMs = 0, frontendMs = 3, validationMs = 2, profileFinalizeMs = 0, cacheEvictions = 0
    )

    private fun observed(plan: VoiceSpeechPlaybackPlan, omit: Int = 0): VoiceSpeechPlaybackResult {
        var clock = 0L
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0, plan) { clock }
        measurement.nativePreparation(10, 1, 1)
        measurement.engineReady(plan.expectedResident)
        measurement.calibration("speaker", 0, 1)
        measurement.underrunObserved(0, "startup")
        measurement.underrunObserved(0, "active")
        measurement.underrunObserved(0, "draining")
        val sustained = plan.scenario == VoiceSpeechPlaybackScenario.SUSTAINED
        clock = 100_000_000L
        repeat(if (sustained) 6 else 1) {
            measurement.attemptStarted("the voice")
            measurement.audioGenerated(speechReferenceFixture("the voice", if (sustained) 1200 else 98))
            measurement.pcmReady(70, if (sustained) 720_000 else 24_000, 24_000, evidence())
        }
        clock = 110_000_000L
        measurement.pcmQueued()
        clock = 120_000_000L
        measurement.firstAudioTrackWrite()
        clock = 130_000_000L
        measurement.trackPlayRequested()
        measurement.reserveCompleted("startup", 20, 800, 1_000, "TARGET_REACHED")
        clock = 150_000_000L
        measurement.firstPresented("audio_timestamp", "speaker")
        plan.manifest.spans.toList().dropLast(omit).groupBy { it.segment }.forEach { (segment, spans) ->
            measurement.captionsPrepared(segment, spans.map { it.first..it.last })
            spans.forEach { measurement.caption(VoiceCaptionTiming("local", 0, 24_000, 200, 24_000, 0, true, bufferedEndFrame = 24_000), segment, it.first..it.last) }
        }
        clock = if (sustained) 180_200_000_000L else 1_200_000_000L
        measurement.terminal(0, 0, 0, "audio_timestamp", "speaker")
        val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0).copy(cleanupState = "RETIRED")
        return if (!sustained) {
            result
        } else {
            result.copy(
                sustainedTargetAudioMs = 300_000,
                sustainedObservedAudioMs = (plan.repeat + 1) * 180_000L,
                sustainedWallElapsedMs = (plan.repeat + 1) * 180_200L,
                sustainedWallDeadlineMs = 720_000,
                sustainedCompletedRepeats = plan.repeat + 1,
                sustainedQualification = if (plan.repeat == 0) "IN_PROGRESS" else "TARGET_REACHED"
            )
        }
    }

    @Test fun independentManifestCatchesTheSameWordOmittedByBothProducerAndCaptionObserver() {
        val plan = VoiceSpeechPlaybackPlan(VoiceSpeechPlaybackScenario.COLD_START, 0, listOf("the voice is ready."))
        val incomplete = observed(plan, omit = 1)
        assertEquals(3, incomplete.captionWordsExpected)
        assertEquals(3, incomplete.captionWordsObserved)
        assertEquals(4, incomplete.manifestWordsExpected)
        assertEquals(3, incomplete.manifestWordsObserved)
        assertFalse(incomplete.captionManifestValid)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, incomplete.quality)
        assertTrue(observed(plan).captionManifestValid)
        assertEquals(VoiceSpeechPlaybackQuality.PLAYBACK_VALIDATED, observed(plan).quality)
    }

    @Test fun stageBoundariesAndRealChunkEvidenceRemainDistinct() {
        val plan = VoiceSpeechPlaybackPlan(VoiceSpeechPlaybackScenario.COLD_START, 0, listOf("the voice"))
        val result = observed(plan)
        assertEquals(listOf(100L, 110L, 120L, 130L, 150L), listOf(result.firstPcmMs, result.firstQueuedPcmMs, result.firstAudioTrackWriteMs, result.trackPlayRequestedMs, result.firstPresentedAudioMs))
        assertEquals(10L, result.sessionPrepareMs)
        assertEquals(70L, result.totalSynthesisMs)
        assertEquals(1, result.effectiveThreads)
        assertEquals(9, result.chunkRecords.single().inputBytes)
        assertEquals(3L, result.chunkRecords.single().observation?.frontendMs)
        assertEquals(2L, result.chunkRecords.single().observation?.validationMs)
        assertEquals(0, result.chunkRecords.single().observation?.cacheEvictions)
        assertEquals(110L, result.reserves.single().startMs)
        assertEquals(130L, result.reserves.single().endMs)
    }

    @Test fun completionDoesNotHideStallsSlowStartupOrUnmeasuredCounters() {
        val result = observed(VoiceSpeechPlaybackPlan(VoiceSpeechPlaybackScenario.COLD_START, 0, listOf("the voice")))
        assertEquals(VoiceSpeechPlaybackState.COMPLETE, result.state)
        assertEquals(VoiceSpeechPlaybackQuality.COMPLETE_WITH_STALLS, result.copy(bufferRestarts = 2, longestRefillMs = 13_631).quality)
        assertEquals(VoiceSpeechPlaybackQuality.COMPLETE_WITH_STALLS, result.copy(activePlaybackUnderruns = 1).quality)
        assertEquals(VoiceSpeechPlaybackQuality.SLOW_START, result.copy(firstPresentedAudioMs = 25_797).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, result.copy(activePlaybackUnderruns = null).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, result.copy(expectedResident = true, engineReused = false).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, result.copy(cleanupState = "PENDING").quality)
    }

    @Test fun fullSuiteContainsBoundedActualWarmStreamingChangingSustainedAndCancelPlans() {
        val config = configuration(full = true).copy(playbackScenarios = VoiceSpeechPlaybackScenario.entries)
        val plans = VoiceSpeechPlaybackProtocol.plans(config)
        assertEquals(24, plans.size)
        assertEquals(24, plans.map { it.identity }.distinct().size)
        val warm = plans.filter { it.scenario == VoiceSpeechPlaybackScenario.RESIDENT_WARM }
        assertEquals(6, warm.size)
        assertTrue(warm.first().warmup)
        assertFalse(warm.first().expectedResident)
        assertTrue(warm.drop(1).all { it.expectedResident && !it.warmup })
        assertTrue(plans.filter { it.scenario == VoiceSpeechPlaybackScenario.STREAMED_SEGMENTS }.all { it.segments.size == 3 && it.arrivalIntervalMs == 500L })
        assertEquals(282, plans.single { it.scenario == VoiceSpeechPlaybackScenario.SUSTAINED }.manifest.spans.size)
        val expanded = VoiceSpeechPlaybackProtocol.expandedPlans(config, VoiceSpeechSustainedSeries.MAX_REPLIES)
        assertEquals(39, VoiceSpeechPlaybackProtocol.maximumSamples(config))
        assertEquals(39, expanded.size)
        assertEquals(expanded.size, expanded.map { it.identity }.distinct().size)
        val sustained = expanded.filter { it.scenario == VoiceSpeechPlaybackScenario.SUSTAINED }
        assertEquals(16, sustained.size)
        assertEquals(1, sustained.map { it.manifest }.distinct().size)
        assertTrue(sustained.drop(1).all { it.expectedResident })
        assertEquals(VoiceSpeechCancellationTarget.entries.toSet(), plans.mapNotNull { it.cancellationTarget }.toSet())
        assertEquals(47, VoiceSpeechCaptionManifest.from(listOf(VoiceSpeechBenchmarkFixture.LONG.text)).spans.size)
    }

    @Test fun independentManifestMatchesProductionPlannerForEverySelectedSegmentAndFixture() {
        val configuration = configuration(full = true).copy(playbackScenarios = VoiceSpeechPlaybackScenario.entries)
        val inputs = VoiceSpeechPlaybackProtocol.plans(configuration).map { it.segments } + VoiceSpeechBenchmarkFixture.entries.map { listOf(it.text) }
        inputs.distinct().forEach { segments ->
            val expected = VoiceSpeechCaptionManifest.from(segments)
            val context = KokoroSpeechContext()
            val actual = segments.flatMapIndexed { segment, text ->
                val projected = context.project(text, { true }, emptyMap(), 16, timed = true)
                assertNull(projected.observation.failure)
                checkNotNull(projected.phrases).flatMap { phrase ->
                    phrase.wordRanges.map { range -> VoiceSpeechCaptionSpan(segment, range.first, range.last) }
                }
            }
            assertEquals(expected.spans, actual.toSet())
            assertEquals(expected.spans.size, actual.size)
            assertTrue(actual.all { span -> segments[span.segment].substring(span.first, span.last + 1).matches(Regex("[a-zA-Z]+(?:'[a-zA-Z]+)*")) })
        }
    }

    @Test fun aReplyPinsItsReceiptBeforeAnyLaterWarmReplyStarts() {
        var clock = 0L
        val first = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0) { clock }
        val second = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0) { clock }
        val relay = VoiceSpeechPlaybackMeasurementRelay()
        relay.current = first
        val firstObserver = relay.forPlayback()
        clock = 100_000_000
        firstObserver.firstAudioTrackWrite()
        firstObserver.terminal(0, 0, 0, "audio_timestamp", "speaker")
        relay.current = second
        val secondObserver = relay.forPlayback()
        clock = 200_000_000
        firstObserver.terminal(9, 7, 12_000, "playback_head", "bluetooth")
        firstObserver.pcmReady(99, 24_000, 24_000, evidence())
        firstObserver.calibration("bluetooth", 500, 4)
        firstObserver.firstAudioTrackWrite()
        assertEquals(0, second.result(VoiceSpeechPlaybackState.COMPLETE, 0).chunks)
        assertNull(second.result(VoiceSpeechPlaybackState.COMPLETE, 0).underruns)
        assertNull(second.result(VoiceSpeechPlaybackState.COMPLETE, 0).firstAudioTrackWriteMs)
        assertTrue(second.result(VoiceSpeechPlaybackState.COMPLETE, 0).calibrations.isEmpty())
        secondObserver.firstAudioTrackWrite()
        secondObserver.terminal(0, 0, 0, "audio_timestamp", "speaker")
        assertEquals(100L, first.result(VoiceSpeechPlaybackState.COMPLETE, 0).firstAudioTrackWriteMs)
        assertEquals(200L, second.result(VoiceSpeechPlaybackState.COMPLETE, 0).firstAudioTrackWriteMs)
        assertEquals(0, first.result(VoiceSpeechPlaybackState.COMPLETE, 0).underruns)
    }

    @Test fun qualificationRequiresTheExactDeclaredSuiteAndActualResidentReuse() {
        val configuration = configuration(full = true).copy(playbackScenarios = VoiceSpeechPlaybackScenario.entries)
        val rows = VoiceSpeechPlaybackProtocol.expandedPlans(configuration, 2).map { plan ->
            observed(plan).let { result ->
                if (plan.cancellationTarget == null) result else result.copy(state = VoiceSpeechPlaybackState.CANCELLED, cancellationMs = 7, cancellationTargetReached = true)
            }
        }
        val report = VoiceSpeechBenchmarkReport(
            1,
            configuration,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.COMPLETE)),
            playbackResults = rows
        )
        assertEquals(listOf(VoiceSpeechBackend.CPU), report.qualifiedPlaybackBackends())
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
        fun withChangedWarm(change: (VoiceSpeechPlaybackResult) -> VoiceSpeechPlaybackResult) = report.copy(
            playbackResults = rows.map {
                if (it.scenario == VoiceSpeechPlaybackScenario.RESIDENT_WARM && it.repeat == 1) change(it) else it
            }
        )
        assertNull(withChangedWarm { it.copy(expectedResident = false, engineReused = false) }.recommendation())
        assertNull(withChangedWarm { it.copy(warmup = true) }.recommendation())
        assertNull(withChangedWarm { it.copy(arrivalIntervalMs = 500) }.recommendation())
        assertNull(withChangedWarm { it.copy(manifestWordsExpected = 46, manifestWordsPrepared = 46, manifestWordsObserved = 46) }.recommendation())
        assertNull(report.copy(playbackResults = rows.dropLast(1)).recommendation())
        assertNull(report.copy(configuration = configuration.copy(calibrationPolicy = VoiceSpeechCalibrationPolicy.CURRENT_SETTINGS)).recommendation())
        assertNull(report.copy(results = report.results.map { it.copy(state = VoiceSpeechBenchmarkBackendState.FAILED) }).recommendation())
    }

    @Test fun stabilityJoinQualificationExcludesWarmupsAndKeepsMeasuredCountAndP95Limits() {
        val config = configuration(full = true).copy(
            mode = VoiceSpeechBenchmarkMode.STABILITY,
            playbackScenarios = VoiceSpeechPlaybackScenario.entries
        )
        val rows = VoiceSpeechPlaybackProtocol.expandedPlans(config, 2).map { plan ->
            observed(plan).let { result ->
                if (plan.cancellationTarget == null) result else result.copy(state = VoiceSpeechPlaybackState.CANCELLED, cancellationMs = 7, cancellationTargetReached = true)
            }
        }
        fun report(measuredGapMs: Long = 20, measuredJoinCount: Int = 20): VoiceSpeechBenchmarkReport {
            var remaining = measuredJoinCount
            return VoiceSpeechBenchmarkReport(
                1,
                config,
                VoiceSpeechBenchmarkRunState.COMPLETE,
                results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.COMPLETE)),
                playbackResults = rows.map { row ->
                    val gap = when {
                        row.warmup -> 500L
                        row.cancellationTarget == null && remaining > 0 -> {
                            remaining--
                            measuredGapMs
                        }
                        else -> null
                    }
                    val join = gap?.let {
                        VoiceSpeechPhraseJoin(
                            24_000, VoiceSpeechJoinBoundary.TOKEN_LIMIT, VoiceSpeechJoinPunctuation.NONE,
                            it, it, 1_000, 0, (it * 24).toInt(), 0, (it * 24).toInt(), 0
                        )
                    }
                    row.copy(chunkRecords = row.chunkRecords.map { chunk -> chunk.copy(join = join.takeIf { chunk.index == 0 }) })
                }
            )
        }
        val qualified = report()
        assertEquals(listOf(500L, 500L), qualified.playbackResults.filter { it.warmup }.flatMap { row -> row.chunkRecords.mapNotNull { it.join?.readyUnpunctuatedGapMs } })
        assertEquals(List(20) { 20L }, qualified.playbackResults.filter { !it.warmup }.flatMap { row -> row.chunkRecords.mapNotNull { it.join?.readyUnpunctuatedGapMs } })
        assertEquals(listOf(VoiceSpeechBackend.CPU), qualified.qualifiedPlaybackBackends())
        assertEquals(listOf(VoiceSpeechBackend.CPU), report(measuredGapMs = 150).qualifiedPlaybackBackends())
        assertTrue(report(measuredGapMs = 151).qualifiedPlaybackBackends().isEmpty())
        assertTrue(report(measuredJoinCount = 19).qualifiedPlaybackBackends().isEmpty())
    }

    @Test fun playbackRankingRejectsFastShortenedNativeAudioAndUnobservedLegacySignals() {
        val configuration = configuration(full = true).copy(backends = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU), playbackScenarios = VoiceSpeechPlaybackScenario.entries)
        val cpu = VoiceSpeechPlaybackProtocol.expandedPlans(configuration, 2).map { plan ->
            observed(plan).let { if (plan.cancellationTarget == null) it else it.copy(state = VoiceSpeechPlaybackState.CANCELLED, cancellationMs = 7, cancellationTargetReached = true) }
        }
        val gpu = cpu.map { row ->
            row.copy(
                requestedBackend = VoiceSpeechBackend.QNN_GPU,
                effectiveProvider = "qnn-gpu",
                acceleratorExecutionObserved = true,
                elapsedMs = 300,
                chunkRecords = row.chunkRecords.map { it.copy(effectiveProvider = "qnn-gpu", acceleratorKernels = 40, audioObservation = speechReferenceFixture("the voice", 43)) }
            )
        }
        val report = VoiceSpeechBenchmarkReport(
            1,
            configuration,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = configuration.backends.map { VoiceSpeechBenchmarkBackendResult(it, VoiceSpeechBenchmarkBackendState.COMPLETE) },
            playbackResults = cpu + gpu
        )
        assertEquals(VoiceSpeechAudioReferenceStatus.MISMATCH, report.playbackAudioReference(VoiceSpeechBackend.QNN_GPU))
        assertEquals(listOf(VoiceSpeechBackend.CPU), report.qualifiedPlaybackBackends())
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
        assertEquals(report, speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
        val missing = report.copy(playbackResults = report.playbackResults.map { row -> row.copy(chunkRecords = row.chunkRecords.map { it.copy(audioObservation = null) }) })
        assertNull(missing.recommendation())
        assertEquals(VoiceSpeechAudioReferenceStatus.UNOBSERVED, missing.playbackAudioReference(VoiceSpeechBackend.CPU))
    }

    @Test fun sustainedQualificationDoesNotMixDurationWorkIntoTheFixedWorkloadRanking() {
        val config = configuration(full = true).copy(
            backends = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU),
            playbackScenarios = VoiceSpeechPlaybackScenario.entries
        )
        val cpu = VoiceSpeechPlaybackProtocol.expandedPlans(config, 2).map { plan ->
            observed(plan).let { if (plan.cancellationTarget == null) it else it.copy(state = VoiceSpeechPlaybackState.CANCELLED, cancellationMs = 7, cancellationTargetReached = true) }
        }
        val gpu = cpu.map { row ->
            val sustained = row.scenario == VoiceSpeechPlaybackScenario.SUSTAINED
            row.copy(
                requestedBackend = VoiceSpeechBackend.QNN_GPU,
                effectiveProvider = "qnn-gpu",
                acceleratorExecutionObserved = true,
                elapsedMs = if (sustained) 239_000 else row.elapsedMs * 9 / 10,
                sustainedWallElapsedMs = if (sustained) (row.repeat + 1) * 239_000L else null,
                chunkRecords = row.chunkRecords.map { it.copy(effectiveProvider = "qnn-gpu", acceleratorKernels = 40) }
            )
        }
        val report = VoiceSpeechBenchmarkReport(
            1,
            config,
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = config.backends.map { VoiceSpeechBenchmarkBackendResult(it, VoiceSpeechBenchmarkBackendState.COMPLETE) },
            playbackResults = cpu + gpu
        )
        assertEquals(config.backends, report.qualifiedPlaybackBackends())
        assertTrue(gpu.sumOf { it.elapsedMs } > cpu.sumOf { it.elapsedMs })
        assertEquals(VoiceSpeechBackend.QNN_GPU, report.recommendation())
        val legacy = report.copy(
            playbackResults = report.playbackResults.map {
                if (it.scenario == VoiceSpeechPlaybackScenario.SUSTAINED) it.copy(sustainedQualification = null) else it
            }
        )
        assertNull(legacy.recommendation())
    }

    @Test fun runtimeSetupFailureCannotBeMisclassifiedAsAnInvalidFixedFixture() {
        for (code in listOf(VoiceSpeechFailureCode.FRONTEND_LOAD, VoiceSpeechFailureCode.MODEL_CONFIGURATION, VoiceSpeechFailureCode.MODEL_OPEN)) {
            val report = VoiceSpeechBenchmarkRunner(create = { throw IllegalStateException(code.name) }, thermalStatus = { 0 })
                .run(VoiceSpeechBenchmarkReport(1, configuration().copy(playback = false))) {}
            assertEquals(code, report.results.single().failure)
            assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, report.results.single().state)
            assertTrue(report.issue != VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD)
            assertNull(report.recommendation())
        }
    }

    @Test fun wrappedNativeFailureRetainsExactStageCodeLimitsAndDiscovery() {
        val native = TimedKokoro.Failure::class.java.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            "preparing", "GRAPH_PREPARATION", 42, 21, "qnn-gpu", 87, 128, 510, "stage=selected;type=1", null
        ) as TimedKokoro.Failure
        val failure = checkNotNull(speechPlaybackFailureEvidence(IllegalStateException("Private driver prose must not leave the process", native), VoiceSpeechBackend.QNN_GPU, "device"))
        assertEquals(VoiceSpeechFailureCode.GRAPH_PREPARATION, failure.classification.code)
        assertEquals(VoiceSpeechStage.PREPARING, failure.classification.stage)
        assertEquals(42, failure.classification.nativeCode)
        assertEquals(87, failure.inputBytes)
        assertEquals(128, failure.inputByteLimit)
        assertEquals(510, failure.tokenLimit)
        assertEquals("qnn-gpu", failure.requestedProvider)
        assertEquals("stage=selected;type=1", failure.discoverySummary)
        assertNull(failure.observation)
        assertNull(failure.effectiveProvider)
    }

    @Test fun phaseTimeoutHasAnExplicitStageAndBudgetWithoutInventingNativeExecution() {
        val timeout = VoicePlaybackTimeout(VoiceSpeechStage.PREPARING, 60_000, 60_250, "PREPARATION")
        val failure = checkNotNull(speechPlaybackFailureEvidence(timeout, VoiceSpeechBackend.QNN_GPU, "device"))
        assertEquals(VoiceSpeechStage.PREPARING, failure.classification.stage)
        assertEquals(60_000L, failure.timeoutLimitMs)
        assertEquals(60_250L, failure.timeoutElapsedMs)
        assertEquals("PREPARATION", failure.timeoutScope)
        assertNull(failure.classification.nativeCode)
        assertNull(failure.observation)
    }

    @Test fun playbackOnlyExportDoesNotPublishZeroCostSynthesisRowsAndRoundTripsActualReceipts() {
        val plan = VoiceSpeechPlaybackProtocol.plans(configuration()).single()
        val result = observed(plan)
        val report = VoiceSpeechBenchmarkReport(
            1,
            configuration(),
            VoiceSpeechBenchmarkRunState.COMPLETE,
            results = listOf(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU, VoiceSpeechBenchmarkBackendState.COMPLETE)),
            playbackResults = listOf(result)
        )
        val json = speechBenchmarkJson(report, local = true)
        assertEquals(6, (json.getValue("schemaVersion") as JsonPrimitive).content.toInt())
        assertTrue(json.getValue("results").jsonArray.isEmpty())
        assertEquals(1, json.getValue("backendStates").jsonArray.size)
        assertFalse(json.getValue("backendStates").toString().contains("measuredCostMs"))
        assertEquals(JsonPrimitive("NOT_APPLICABLE_PLAYBACK_MODE"), json.getValue("measurementAvailability").jsonObject["synthesisSamples"])
        assertNull(report.recommendation())
        assertEquals(report, speechBenchmarkReportFromJson(json))
        val exported = voiceSpeechBenchmarkExport(report)
        assertFalse(exported.contains("localDeviceKey"))
        assertFalse(exported.contains("the voice"))
    }

    @Test fun storedTotalsCannotBeChangedWithoutInvalidatingThePlaybackReceipt() {
        val report = VoiceSpeechBenchmarkReport(1, configuration(), playbackResults = listOf(observed(VoiceSpeechPlaybackProtocol.plans(configuration()).single())))
        val original = speechBenchmarkJson(report, local = true)
        val sample = original.getValue("playbackResults").jsonArray.single().jsonObject
        val damaged = JsonObject(original + ("playbackResults" to JsonArray(listOf(JsonObject(sample + ("totalSynthesisMs" to JsonPrimitive(0)))))))
        assertNull(speechBenchmarkReportFromJson(damaged))
        assertNull(speechBenchmarkReportFromJson(JsonObject(original + ("schemaVersion" to JsonPrimitive(99)))))
    }

    @Test fun oldPlaybackCanBeReadButCannotRegainQualificationAfterResaving() {
        val oldReceipt = VoiceSpeechPlaybackResult(
            VoiceSpeechBackend.CPU, VoiceSpeechPlaybackState.COMPLETE,
            firstPcmMs = 10_153, firstPresentedAudioMs = 13_750, elapsedMs = 24_000, totalSynthesisMs = 13_644, totalAudioMs = 9_675,
            chunks = 2, validatedChunks = 2, captionWordsExpected = 47, captionWordsObserved = 47, underruns = 1
        )
        val report = VoiceSpeechBenchmarkReport(1, configuration(), VoiceSpeechBenchmarkRunState.COMPLETE, playbackResults = listOf(oldReceipt))
        val current = speechBenchmarkJson(report, local = true)
        val legacyKeys = setOf("requestedBackend", "state", "firstPcmMs", "firstQueuedPcmMs", "firstPresentedAudioMs", "elapsedMs", "totalSynthesisMs", "totalAudioMs", "chunks", "underruns", "bufferRestarts", "longestRefillMs", "cancellationMs", "captionObservations", "captionClockRegressions", "captionTimingViolations", "clockBasis", "route", "effectiveProvider", "acceleratorExecutionObserved", "thermalBefore", "thermalAfter", "failure", "failureStage", "validatedChunks", "captionWordsExpected", "captionWordsObserved")
        val legacy = JsonObject(
            current + mapOf(
                "schemaVersion" to JsonPrimitive(2),
                "configuration" to JsonObject(current.getValue("configuration").jsonObject - setOf("playbackProtocolVersion", "playbackScenarios", "calibrationPolicy", "captionOffsetMs")),
                "playbackResults" to JsonArray(current.getValue("playbackResults").jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key in legacyKeys }) }),
                "results" to JsonArray(listOf(JsonObject(mapOf("requestedBackend" to JsonPrimitive("CPU"), "state" to JsonPrimitive("COMPLETE"), "samples" to JsonArray(emptyList())))))
            )
        )
        val restored = checkNotNull(speechBenchmarkReportFromJson(legacy))
        assertEquals(0, restored.configuration.playbackProtocolVersion)
        assertFalse(restored.configuration.currentProtocol)
        assertEquals(oldReceipt, restored.playbackResults.single())
        assertEquals(VoiceSpeechPlaybackQuality.UNASSESSED, restored.playbackResults.single().quality)
        assertEquals(VoiceSpeechBenchmarkOutcome.HISTORICAL, restored.outcome)
        assertEquals(restored, speechBenchmarkReportFromJson(speechBenchmarkJson(restored, local = true)))
        assertNull(restored.recommendation())
    }

    @Test fun wrapperEntryCannotBeMistakenForAnObservedNativeInferenceStage() {
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0)
        measurement.preparationStarted()
        assertTrue(measurement.reached(VoiceSpeechCancellationTarget.PREPARATION))
        measurement.engineReady(false)
        assertFalse(measurement.reached(VoiceSpeechCancellationTarget.PREPARATION))
        measurement.attemptStarted("the voice")
        assertFalse(measurement.reached(VoiceSpeechCancellationTarget.INFERENCE))
        measurement.executionProgress(VoiceSpeechProgress(VoiceSpeechStage.PREPARING, 1, 2, 50, 60, false))
        assertFalse(measurement.reached(VoiceSpeechCancellationTarget.INFERENCE))
        measurement.executionProgress(VoiceSpeechProgress(VoiceSpeechStage.SYNTHESIS, 1, 3, 1, 61, false))
        assertTrue(measurement.reached(VoiceSpeechCancellationTarget.INFERENCE))
        measurement.executionProgress(VoiceSpeechProgress(VoiceSpeechStage.SYNTHESIS, 1, 3, 5, 65, false, active = false))
        assertFalse(measurement.reached(VoiceSpeechCancellationTarget.INFERENCE))
    }

    @Test fun unavailableFreshProgressCannotQualifyAnEarlierNativeStageObservation() {
        for ((target, stage) in listOf(
            VoiceSpeechCancellationTarget.INFERENCE to VoiceSpeechStage.SYNTHESIS,
            VoiceSpeechCancellationTarget.PREPARATION to VoiceSpeechStage.PREPARING
        )) {
            var clock = 0L
            val plan = VoiceSpeechPlaybackPlan(VoiceSpeechPlaybackScenario.CANCELLATION, 0, listOf("the voice"), cancellationTarget = target)
            val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0, plan) { clock }
            measurement.preparationStarted()
            measurement.engineReady(false)
            measurement.attemptStarted("the voice")
            val reached = measurement.reached(target, VoiceSpeechProgress(stage, 1, 3, 1, 61, false))
            assertTrue(reached)
            clock = 100_000_000L
            // The final native read is unavailable after the polling read observed an active stage.
            measurement.cancelRequested(reached && measurement.reached(target, null))
            clock = 107_000_000L
            measurement.terminal(0, 0, 0, "audio_timestamp", "speaker")
            val result = measurement.result(VoiceSpeechPlaybackState.CANCELLED, 0).copy(cleanupState = "RETIRED")
            assertEquals(VoiceSpeechPlaybackQuality.CANCELLATION_TARGET_NOT_REACHED, result.quality)
            assertEquals(false, result.cancellationTargetReached)
            assertEquals(7L, result.cancellationMs)
            assertEquals(stage, result.progress.single().stage)
            assertTrue(result.progress.single().active)
        }
    }

    @Test fun modelOpenAndPresentedPlaybackCancellationDoNotRequireNativeSnapshots() {
        for (target in listOf(VoiceSpeechCancellationTarget.PREPARATION, VoiceSpeechCancellationTarget.PLAYBACK)) {
            var clock = 0L
            val plan = VoiceSpeechPlaybackPlan(VoiceSpeechPlaybackScenario.CANCELLATION, 0, listOf("the voice"), cancellationTarget = target)
            val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0, plan) { clock }
            if (target == VoiceSpeechCancellationTarget.PREPARATION) {
                measurement.preparationStarted()
            } else {
                measurement.engineReady(false)
                measurement.firstPresented("audio_timestamp", "speaker")
            }
            clock = 100_000_000L
            measurement.cancelRequested(measurement.reached(target, null))
            clock = 107_000_000L
            measurement.terminal(0, 0, 0, "audio_timestamp", "speaker")
            val result = measurement.result(VoiceSpeechPlaybackState.CANCELLED, 0).copy(cleanupState = "RETIRED")
            assertEquals(VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED, result.quality)
            assertEquals(true, result.cancellationTargetReached)
            assertEquals(7L, result.cancellationMs)
            assertTrue(result.progress.isEmpty())
        }
    }

    @Test fun cancellationNeedsAReachedTargetMeasuredStopAndCompletedRetirement() {
        val result = VoiceSpeechPlaybackResult(
            VoiceSpeechBackend.CPU,
            VoiceSpeechPlaybackState.CANCELLED,
            protocolVersion = VoiceSpeechPlaybackProtocol.VERSION,
            cancellationTarget = VoiceSpeechCancellationTarget.PLAYBACK,
            cancellationTargetReached = true,
            cancellationMs = 7,
            cleanupState = "RETIRED"
        )
        assertEquals(VoiceSpeechPlaybackQuality.CANCELLATION_VALIDATED, result.quality)
        assertEquals(VoiceSpeechPlaybackQuality.CANCELLATION_TARGET_NOT_REACHED, result.copy(cancellationTargetReached = false).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, result.copy(cleanupState = "PENDING").quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, result.copy(cancellationMs = null).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, result.copy(cancellationMs = 501).quality)
    }
}
