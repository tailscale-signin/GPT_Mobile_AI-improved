package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechWorkloadTest {
    private fun configuration(
        mode: VoiceSpeechBenchmarkMode = VoiceSpeechBenchmarkMode.QUICK,
        backends: List<VoiceSpeechBackend> = listOf(VoiceSpeechBackend.CPU),
        workload: VoiceSpeechBenchmarkWorkload = VoiceSpeechBenchmarkWorkload.UTTERANCE
    ) = VoiceSpeechBenchmarkConfiguration(mode, backends, KokoroVoice.HEART, 4, 1.25f, "a".repeat(64), "runtime", 147, 36, "b".repeat(64), workload = workload, sourceCommit = "c".repeat(40))

    private class Clock(var value: Long = 0) {
        fun add(ms: Long) {
            value += ms * 1_000_000
        }
    }

    /** Enforces the real native input boundary instead of accepting the formerly invalid LONG. */
    private class BoundedEngine(private val backend: VoiceSpeechBackend, private val clock: Clock, private val durationMs: Long = 100) : VoicePhraseEngine {
        val calls = mutableListOf<String>()
        var closed = false
        override val executionEvidence get() = VoiceSpeechExecutionEvidence(
            backend, VoiceSpeechBenchmarkBackendResult.provider(backend), "a".repeat(64), "runtime", "device", VoiceSpeechStage.READY,
            acceleratorKernels = if (backend == VoiceSpeechBackend.CPU) 0 else 39,
            cpuKernels = if (backend == VoiceSpeechBackend.CPU) 0 else 50,
            tokenCount = 3, verifiedAudio = true, observedAtMillis = 1,
            profileAvailable = backend != VoiceSpeechBackend.CPU,
            profileId = if (backend == VoiceSpeechBackend.CPU) null else 1,
            profileReused = calls.size > 1, cacheHit = calls.size > 1, currentPrepareMs = if (calls.size > 1) 0 else 7,
            graphPreparedThisCall = calls.size == 1, cachedShapeCount = 1, cacheLimit = 2, cacheEstimatedBytes = 1024,
            audioRms = .1, audioPeak = .1, nonSilentSamples = 1800, clippedSamples = 0
        )
        override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
            require(text.toByteArray(Charsets.UTF_8).size in 1..128) { "Native phrase byte contract" }
            require(text.all { it in 'a'..'z' || it in " ',.:;!?" }) { "Native frontend alphabet" }
            calls += text
            clock.add(durationMs)
            return VoicePhraseAudio(FloatArray(1800) { .1f }, longArrayOf(1, 1, 1), intArrayOf(1, 2))
        }
        override fun resetCancellation() = Unit
        override fun cancel() = Unit
        override fun close() {
            closed = true
        }
    }

    private fun completed(configuration: VoiceSpeechBenchmarkConfiguration = configuration(), thermal: Int? = 0): VoiceSpeechBenchmarkReport {
        val clock = Clock()
        return VoiceSpeechBenchmarkRunner(
            create = {
                clock.add(10)
                BoundedEngine(it, clock)
            },
            nanoTime = { clock.value },
            wallTime = { 1000 },
            thermalStatus = { thermal }
        ).run(VoiceSpeechBenchmarkReport(1, configuration)) {}
    }

    @Test fun productionPlannerPreservesAllWordsAndPunctuationWhileBoundingEveryNativeCall() {
        assertEquals(268, VoiceSpeechBenchmarkFixture.LONG.text.toByteArray(Charsets.UTF_8).size)
        for (fixture in VoiceSpeechBenchmarkFixture.entries) {
            val chunks = VoiceSpeechWorkload.phrases(fixture)
            assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size in 1..128 })
            assertEquals(fixture.text, chunks.joinToString(" "))
        }
        assertTrue(VoiceSpeechWorkload.phrases(VoiceSpeechBenchmarkFixture.LONG).size > 1)
    }

    @Test fun nativeAndAlternatingWorkloadsAreBoundedAndHaveDifferentVersionedIdentities() {
        val hashes = VoiceSpeechBenchmarkWorkload.entries.map(VoiceSpeechWorkload::fixtureHash)
        assertEquals(VoiceSpeechBenchmarkWorkload.entries.size, hashes.distinct().size)
        for (fixture in VoiceSpeechBenchmarkFixture.entries) {
            assertEquals(1, VoiceSpeechWorkload.phrases(fixture, VoiceSpeechBenchmarkWorkload.NATIVE_PHRASE).size)
            val varied = VoiceSpeechWorkload.phrases(fixture, VoiceSpeechBenchmarkWorkload.VARIED_SHAPES)
            assertEquals(3, varied.size)
            assertTrue(varied.zipWithNext().all { it.first != it.second })
            varied.forEach(VoiceSpeechWorkload::validate)
        }
    }

    @Test fun interleavedRunVisitsEachLengthBeforeReturningToTheSameFixture() {
        val fixtures = VoiceSpeechBenchmarkFixture.entries
        val interleaved = VoiceSpeechWorkload.schedule(VoiceSpeechBenchmarkWorkload.INTERLEAVED_UTTERANCES, VoiceSpeechBenchmarkMode.QUICK)
        val grouped = VoiceSpeechWorkload.schedule(VoiceSpeechBenchmarkWorkload.UTTERANCE, VoiceSpeechBenchmarkMode.QUICK)
        assertEquals(grouped.toSet(), interleaved.toSet())
        assertEquals((0..2).flatMap { repeat -> fixtures.map { it to repeat } }, interleaved)
        assertEquals(List(3) { fixtures.first() }, grouped.take(3).map { it.first })
        fixtures.forEach { assertEquals(VoiceSpeechWorkload.phrases(it), VoiceSpeechWorkload.phrases(it, VoiceSpeechBenchmarkWorkload.INTERLEAVED_UTTERANCES)) }
    }

    @Test fun autoCalibrationUsesTwoCompleteTraversalsOfTheSameProductionPlanner() {
        val groups = VoiceSpeechWorkload.calibrationFixtures
        assertEquals(VoiceSpeechWorkload.CALIBRATION_FIXTURE_COUNT, groups.size)
        assertEquals(groups.take(3), groups.drop(3))
        assertEquals(VoiceSpeechBenchmarkFixture.entries.map { VoiceSpeechWorkload.phrases(it) }, groups.take(3))
        groups.flatten().forEach(VoiceSpeechWorkload::validate)
    }

    @Test fun quickAndFullBothCompleteAllFixturesThroughStrictNativeBoundaries() {
        for (mode in VoiceSpeechBenchmarkMode.entries) {
            for (workload in VoiceSpeechBenchmarkWorkload.entries) {
                val report = completed(configuration(mode = mode, workload = workload))
                assertEquals(VoiceSpeechBenchmarkRunState.COMPLETE, report.state)
                assertEquals(VoiceSpeechBenchmarkOutcome.QUALIFIED, report.outcome)
                assertEquals(3 * (mode.warmRepeats + 1), report.completedSamples)
                assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
                val long = report.results.single().samples.first { it.fixture == VoiceSpeechBenchmarkFixture.LONG }
                assertEquals(100L, long.firstPcmMs)
                assertEquals(long.chunks.size * 100L, long.generationMs)
                assertEquals(long.chunks.sumOf { it.audioMs }, long.audioMs, .0001)
            }
        }
    }

    @Test fun invalidWorkloadIsRejectedBeforeAnyNativeEngineIsCreated() {
        var opened = 0
        val report = VoiceSpeechBenchmarkRunner(
            create = {
                opened++
                error("Must not run")
            },
            plan = { fixture, _ -> listOf(fixture.text) }
        ).run(VoiceSpeechBenchmarkReport(1, configuration(backends = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS))) {}
        assertEquals(0, opened)
        assertEquals(VoiceSpeechBenchmarkOutcome.INVALID_WORKLOAD, report.outcome)
        assertEquals(VoiceSpeechBenchmarkIssue.INVALID_WORKLOAD, report.issue)
        assertTrue(report.results.all { it.state == VoiceSpeechBenchmarkBackendState.NOT_RUN })
        assertNull(report.recommendation())
    }

    @Test fun failingChunkRetainsCompletedChunksAndReportsExactSafeContextWithoutRetryingOtherBackends() {
        val clock = Clock()
        val opened = mutableListOf<VoiceSpeechBackend>()
        val target = VoiceSpeechWorkload.phrases(VoiceSpeechBenchmarkFixture.LONG)[1]
        val snapshots = mutableListOf<VoiceSpeechBenchmarkReport>()
        val report = VoiceSpeechBenchmarkRunner(
            create = { backend ->
                opened += backend
                val engine = BoundedEngine(backend, clock)
                object : VoicePhraseEngine by engine {
                    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                        if (text == target) throw VoiceSpeechWorkloadFailure("FRONTEND_INPUT_INVALID", text.length)
                        return engine.generate(text, speaker, speed)
                    }
                }
            },
            nanoTime = { clock.value },
            thermalStatus = { 0 }
        ).run(VoiceSpeechBenchmarkReport(1, configuration(backends = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS)), snapshots::add)
        val failed = report.results.first()
        assertEquals(listOf(VoiceSpeechBackend.CPU), opened)
        assertEquals(VoiceSpeechBenchmarkOutcome.INVALID_WORKLOAD, report.outcome)
        assertEquals(VoiceSpeechBenchmarkFixture.LONG, failed.failedFixture)
        assertEquals(0, failed.failedRepeat)
        assertEquals(1, failed.failedChunk)
        assertEquals(target.length, failed.failedInputBytes)
        assertEquals(128, failed.failedInputByteLimit)
        assertEquals(VoiceSpeechStage.FRONTEND, failed.failureStage)
        assertEquals(1, failed.incompleteChunks.size)
        assertEquals(6, failed.samples.size)
        assertTrue(failed.executionVerified)
        assertFalse(failed.verified)
        assertNull(failed.failureEffectiveProvider)
        assertTrue(snapshots.any { it.results.first().incompleteChunks.size == 1 })
        assertFalse(voiceSpeechBenchmarkExport(report).contains(target))
        assertNull(report.recommendation())
        assertEquals(report, speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
    }

    @Test fun unknownThermalStateRetainsExecutionEvidenceButCannotRecommendAnEngine() {
        val report = completed(thermal = null)
        assertTrue(report.results.single().executionVerified)
        assertTrue(report.results.single().verified)
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, report.outcome)
        assertNull(report.recommendation())
    }

    @Test fun finishedLoopWithNoPassingBackendDoesNotClaimAQualifiedComparison() {
        val report = VoiceSpeechBenchmarkRunner(create = { error("EXECUTION_FAILED") }).run(VoiceSpeechBenchmarkReport(1, configuration())) {}
        assertEquals(VoiceSpeechBenchmarkRunState.COMPLETE, report.state)
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, report.outcome)
        assertFalse(report.results.single().executionVerified)
        assertNull(report.recommendation())
    }

    @Test fun acceleratorWithoutACollectedProfileCannotQualifyEvenWithPositiveKernelCounters() {
        val clock = Clock()
        val engine = BoundedEngine(VoiceSpeechBackend.QNN_HTP, clock)
        val missing = object : VoicePhraseEngine by engine {
            override val executionEvidence get() = engine.executionEvidence.copy(profileAvailable = null)
        }
        val report = VoiceSpeechBenchmarkRunner(create = { missing }, nanoTime = { clock.value }, thermalStatus = { 0 })
            .run(VoiceSpeechBenchmarkReport(1, configuration(backends = listOf(VoiceSpeechBackend.QNN_HTP)))) {}
        assertEquals(VoiceSpeechFailureCode.EXECUTION_EVIDENCE_MISSING, report.results.single().failure)
        assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, report.results.single().state)
        assertFalse(report.results.single().executionVerified)
        assertTrue(report.results.single().samples.isEmpty())
        assertNull(report.recommendation())
    }

    @Test fun immutableNativeFailureReceiptPreservesOnlyThisAttemptAndFrontendCannotBorrowIt() {
        val data = LongArray(25).also {
            it[0] = 2
            it[2] = 27
            it[7] = 43
            it[8] = 2
            it[12] = 1
            it[13] = 7
            it[16] = 1
            it[17] = 2
        }
        val constructor = dev.melo.kokoro.TimedKokoro.Failure::class.java.declaredConstructors.single().also { it.isAccessible = true }
        fun run(stage: String, code: String): VoiceSpeechBenchmarkReport {
            val failure = constructor.newInstance(stage, code, -1, 43, "qnn-htp", 41, 128, 510, "requested=2;status=selected", data) as dev.melo.kokoro.TimedKokoro.Failure
            val clock = Clock()
            val engine = BoundedEngine(VoiceSpeechBackend.QNN_HTP, clock)
            val failed = object : VoicePhraseEngine by engine {
                override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio = throw failure
            }
            return VoiceSpeechBenchmarkRunner(create = { failed }, nanoTime = { clock.value }, thermalStatus = { 0 })
                .run(VoiceSpeechBenchmarkReport(1, configuration(backends = listOf(VoiceSpeechBackend.QNN_HTP)))) {}
        }
        val sameAttempt = run("validating", "CPU_ONLY").results.single()
        assertEquals("cpu", sameAttempt.failureEffectiveProvider)
        assertEquals(0, sameAttempt.failureAcceleratorKernels)
        assertEquals(27, sameAttempt.failureCpuKernels)
        assertEquals(41, sameAttempt.failedInputBytes)
        assertEquals(43, sameAttempt.failedTokenCount)
        assertEquals(510, sameAttempt.failedTokenLimit)
        assertFalse(sameAttempt.executionVerified)
        val frontend = run("frontend", "FRONTEND_INPUT_TOO_LONG")
        assertEquals(VoiceSpeechBenchmarkOutcome.INVALID_WORKLOAD, frontend.outcome)
        assertNull(frontend.results.single().failureEffectiveProvider)
        assertNull(frontend.results.single().failureAcceleratorKernels)
    }

    @Test fun backendRotationIsRecordedAndKeepsResultIdentityStable() {
        for (seed in 0L..2L) {
            val config = configuration(backends = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS).copy(backendOrderSeed = seed)
            val opened = mutableListOf<VoiceSpeechBackend>()
            val clock = Clock()
            val report = VoiceSpeechBenchmarkRunner(create = {
                opened += it
                BoundedEngine(it, clock)
            }, nanoTime = { clock.value }, thermalStatus = { 0 })
                .run(VoiceSpeechBenchmarkReport(1, config)) {}
            assertEquals(config.plannedBackendOrder, opened)
            assertEquals(config.backends, report.results.map { it.requestedBackend })
            assertEquals(config.backends[seed.toInt()], opened.first())
            assertEquals(config.plannedBackendOrder.map { JsonPrimitive(it.name) }, speechBenchmarkJson(report)["plannedBackendOrder"]!!.jsonArray.toList())
        }
    }

    @Test fun medianUsesBothMiddleValuesAndStatisticsDoNotPoolDifferentFixtures() {
        assertEquals(3435.5, VoiceSpeechBenchmarkBackendResult.median(listOf(1893.0, 1901.0, 1923.0, 1927.0, 1984.0, 4887.0, 4894.0, 4959.0, 5009.0, 5036.0))!!, .0001)
        val result = completed().results.single()
        assertEquals(100.0, result.fixtureStatistics(VoiceSpeechBenchmarkFixture.SHORT).medianWarmMs!!, .0001)
        assertTrue(result.fixtureStatistics(VoiceSpeechBenchmarkFixture.LONG).medianWarmMs!! > 100)
        assertEquals(2, result.fixtureStatistics(VoiceSpeechBenchmarkFixture.LONG).warmSampleCount)
    }

    @Test fun recommendationUsesExactTenPercentOfAllNativeChunksAndPreparation() {
        fun recommended(acceleratorMs: Long): VoiceSpeechBackend? {
            val clock = Clock()
            val runner = VoiceSpeechBenchmarkRunner(
                create = { backend ->
                    val cost = if (backend == VoiceSpeechBackend.CPU) 1000L else acceleratorMs
                    clock.add(cost)
                    BoundedEngine(backend, clock, cost)
                },
                nanoTime = { clock.value },
                thermalStatus = { 0 }
            )
            return runner.run(VoiceSpeechBenchmarkReport(1, configuration(backends = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_HTP)))) {}.recommendation()
        }
        assertEquals(VoiceSpeechBackend.QNN_HTP, recommended(900))
        assertEquals(VoiceSpeechBackend.CPU, recommended(903))
    }

    @Test fun historyRetainsLegacyProtocolAndCannotReinterpretOldFixturesAsCurrent() {
        val report = completed()
        val current = speechBenchmarkJson(report, local = true)
        val legacy = JsonObject(current + mapOf("schemaVersion" to JsonPrimitive(1), "fixtureVersion" to JsonPrimitive(1)))
        val restored = speechBenchmarkReportFromJson(legacy)
        assertNotNull(restored)
        assertEquals("legacy-fixtures-v1", restored!!.configuration.protocolId)
        assertFalse(restored.configuration.currentProtocol)
        assertEquals(VoiceSpeechBenchmarkOutcome.HISTORICAL, restored.outcome)
        assertNull(restored.recommendation())
        assertEquals(report.completedSamples, restored.completedSamples)
        val altered = report.copy(configuration = report.configuration.copy(fixtureHash = "d".repeat(64)))
        assertNull(speechBenchmarkReportFromJson(speechBenchmarkJson(altered, local = true))!!.recommendation())
    }

    @Test fun unprofiledCpuIsExplicitlyUnknownRatherThanZeroKernelMeasurement() {
        val report = completed()
        val exported = speechBenchmarkJson(report)
        val sample = exported.getValue("results").jsonArray.single().jsonObject.getValue("samples").jsonArray.first().jsonObject
        val chunk = sample.getValue("chunks").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(false), chunk["profileAvailable"])
        assertEquals(JsonNull, chunk["cpuKernels"])
        assertEquals(JsonNull, chunk["cpuMicros"])
        assertEquals(report, speechBenchmarkReportFromJson(speechBenchmarkJson(report, local = true)))
        assertFalse(voiceSpeechBenchmarkExport(report).contains("localDeviceKey"))
        assertTrue(voiceSpeechBenchmarkExport(report).contains("c".repeat(40)))
    }

    @Test fun persistedChunkIdentityAndTotalsMustAgreeBeforeQualification() {
        val report = completed()
        val result = report.results.single()
        val first = result.samples.first()
        val changed = first.copy(chunks = first.chunks.map { it.copy(inputSha256 = "e".repeat(64)) })
        val invalid = report.copy(results = listOf(result.copy(samples = listOf(changed) + result.samples.drop(1))))
        assertNull(invalid.recommendation())
        val inconsistent = report.copy(results = listOf(result.copy(samples = listOf(first.copy(generationMs = first.generationMs + 1)) + result.samples.drop(1))))
        assertNull(speechBenchmarkReportFromJson(speechBenchmarkJson(inconsistent, local = true)))
    }

    @Test fun playbackCoverageRemainsSeparateFromSilentSynthesisRecommendations() {
        val config = configuration(backends = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS).copy(playback = true)
        val unmeasured = VoiceSpeechBenchmarkReport(1, config, VoiceSpeechBenchmarkRunState.COMPLETE, playbackResults = listOf(VoiceSpeechPlaybackResult(VoiceSpeechBackend.CPU, VoiceSpeechPlaybackState.COMPLETE)))
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, unmeasured.outcome)
        val complete = unmeasured.copy(
            playbackResults = config.backends.map {
                VoiceSpeechPlaybackResult(it, VoiceSpeechPlaybackState.COMPLETE, firstPresentedAudioMs = 5, totalAudioMs = 75, chunks = 1, thermalBefore = 0, thermalAfter = 0, validatedChunks = 1, captionWordsExpected = 1, captionWordsObserved = 1)
            }
        )
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, complete.outcome)
        assertNull(complete.recommendation())
        assertEquals(complete, speechBenchmarkReportFromJson(speechBenchmarkJson(complete, local = true)))
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, complete.copy(playbackResults = complete.playbackResults.take(1)).outcome)
        assertEquals(VoiceSpeechBenchmarkOutcome.PARTIAL, complete.copy(playbackResults = complete.playbackResults.mapIndexed { index, result -> if (index == 1) result.copy(state = VoiceSpeechPlaybackState.FAILED) else result }).outcome)
        val missingProof = complete.copy(playbackResults = complete.playbackResults.map { it.copy(validatedChunks = 0) })
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, missingProof.outcome)
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, complete.copy(playbackResults = complete.playbackResults.map { it.copy(thermalAfter = null) }).outcome)
        assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, complete.copy(playbackResults = complete.playbackResults.map { it.copy(captionWordsObserved = 0) }).outcome)
    }

    @Test fun allFailedOrUnsupportedPlaybackCannotClaimPartialSuccess() {
        val config = configuration(backends = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS).copy(playback = true)
        for (terminal in listOf(VoiceSpeechPlaybackState.FAILED, VoiceSpeechPlaybackState.UNSUPPORTED)) {
            val report = VoiceSpeechBenchmarkReport(
                1,
                config,
                VoiceSpeechBenchmarkRunState.COMPLETE,
                playbackResults = config.backends.map { VoiceSpeechPlaybackResult(it, terminal) }
            )
            assertEquals(VoiceSpeechBenchmarkOutcome.NO_QUALIFIED_COMPARISON, report.outcome)
            assertNull(report.recommendation())
        }
    }
}
