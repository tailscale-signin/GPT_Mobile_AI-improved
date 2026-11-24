package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkControlTest {
    @Test fun firstMismatchedGpuChunkStopsOnlyThatBackendAndRetainsItsObservation() {
        val fixture = Fixture()
        val report = fixture.run()
        val gpu = report.results[1]
        assertEquals(1, fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).calls)
        assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, gpu.state)
        assertEquals(VoiceSpeechFailureCode.OUTPUT_INVALID, gpu.failure)
        assertEquals(VoiceSpeechBenchmarkStopReason.AUDIO_REFERENCE_MISMATCH, gpu.stopReason)
        assertEquals(1, gpu.incompleteChunks.size)
        assertEquals(VoiceSpeechBenchmarkFixture.SHORT, gpu.incompleteFixture)
        assertEquals(0, gpu.incompleteRepeat)
        assertEquals(VoiceSpeechAudioReferenceReason.DURATION_MISMATCH, report.audioReference(gpu).comparison.reason)
        assertTrue(gpu.executionVerified)
        assertFalse(gpu in report.qualifiedResults())
        assertEquals(listOf(9, 0, 9), report.results.map { it.samples.size })
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
        assertEquals(VoiceSpeechBenchmarkOutcome.PARTIAL, report.outcome)
        assertTrue(fixture.engines.values.all { it.closed })
    }

    @Test fun explicitDeepDiagnosticsContinuesMismatchedAudioWithoutQualifyingIt() {
        val fixture = Fixture()
        val report = fixture.run(configuration().copy(deepDiagnostics = true))
        val gpu = report.results[1]
        assertEquals(21, fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).calls)
        assertEquals(VoiceSpeechBenchmarkBackendState.COMPLETE, gpu.state)
        assertNull(gpu.stopReason)
        assertEquals(VoiceSpeechAudioReferenceStatus.MISMATCH, report.audioReference(gpu).comparison.status)
        assertFalse(gpu in report.qualifiedResults())
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
    }

    @Test fun rotatedOrderRetainsUncomparedWorkAndLaterCpuReferenceStillRejectsIt() {
        val fixture = Fixture()
        val report = fixture.run(configuration().copy(backendOrderSeed = 1))
        assertEquals(21, fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).calls)
        assertEquals(VoiceSpeechAudioReferenceStatus.MISMATCH, report.audioReference(report.results[1]).comparison.status)
        assertFalse(report.results[1] in report.qualifiedResults())
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
    }

    @Test fun chunkDeadlinePublishesPendingStopAndWaitsForTheActualCallAndClose() {
        val fixture = Fixture(mismatch = false)
        var pendingWasPublished = false
        fixture.onGenerate = { engine ->
            if (engine.backend == VoiceSpeechBackend.QNN_GPU && engine.calls == 1) {
                fixture.clock.advance(60)
                fixture.scheduler.fireDue()
                assertTrue(pendingWasPublished)
                assertEquals(1, engine.cancellations)
                assertFalse(engine.closed)
                fixture.scheduler.fireAllIncludingCancelled()
                assertEquals(1, engine.cancellations)
                fixture.clock.advance(25)
            }
        }
        val report = fixture.run(configuration().copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 50))) { current ->
            val gpu = current.results[1]
            if (gpu.deadline != null && gpu.cleanupState == VoiceSpeechBenchmarkCleanupState.PENDING) {
                pendingWasPublished = true
                assertTrue(current.isRunning)
                assertFalse(fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).closed)
            }
        }
        val gpu = report.results[1]
        assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, gpu.state)
        assertEquals(VoiceSpeechBenchmarkStopReason.DEADLINE, gpu.stopReason)
        assertEquals(VoiceSpeechBenchmarkDeadlineScope.CHUNK, gpu.deadline?.scope)
        assertEquals(50L, gpu.deadline?.limitMs)
        assertEquals(60L, gpu.deadline?.elapsedMs)
        assertEquals(VoiceSpeechBenchmarkCleanupState.RETIRED, gpu.cleanupState)
        assertEquals(45L, gpu.deadlineNativeRetirementMs)
        assertEquals(0, fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).evidenceReads)
        assertEquals(1, fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).calls)
        assertEquals(VoiceSpeechBenchmarkBackendState.COMPLETE, report.results.last().state)
        assertFalse(fixture.runner.nativeCleanupPending)
    }

    @Test fun preparationDeadlineCancelsPendingFactoryThenClosesItsReturnedEngineWithoutSynthesis() {
        val fixture = Fixture(mismatch = false)
        fixture.onCreate = { backend ->
            if (backend == VoiceSpeechBackend.QNN_GPU) {
                fixture.clock.advance(60)
                fixture.scheduler.fireDue()
                assertEquals(1, fixture.preparationCancellations)
                fixture.clock.advance(25)
            }
        }
        val report = fixture.run(configuration().copy(deadlines = VoiceSpeechBenchmarkDeadlines(preparationMs = 50)))
        val gpu = report.results[1]
        assertEquals(VoiceSpeechBenchmarkDeadlineScope.PREPARATION, gpu.deadline?.scope)
        assertEquals(85L, gpu.sessionPrepareMs)
        assertEquals(35L, gpu.deadlineNativeRetirementMs)
        assertEquals(0, fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).calls)
        assertTrue(fixture.engines.getValue(VoiceSpeechBackend.QNN_GPU).closed)
        assertEquals(VoiceSpeechBenchmarkBackendState.COMPLETE, report.results.last().state)
    }

    @Test fun backendDeadlineCountsWholeWorkloadAndPreservesEarlierSamplesAndPartialChunk() {
        val fixture = Fixture(mismatch = false)
        val report = fixture.run(configuration(listOf(VoiceSpeechBackend.CPU)).copy(deadlines = VoiceSpeechBenchmarkDeadlines(backendMs = 50)))
        val result = report.results.single()
        assertEquals(VoiceSpeechBenchmarkDeadlineScope.BACKEND, result.deadline?.scope)
        assertEquals(3, result.samples.size)
        assertEquals(1, result.incompleteChunks.size)
        assertEquals(VoiceSpeechBenchmarkFixture.MEDIUM, result.failedFixture)
        assertEquals(1, result.failedChunk)
        assertEquals(5, fixture.engines.getValue(VoiceSpeechBackend.CPU).calls)
        assertNull(report.recommendation())
    }

    @Test fun userCancellationWinsAndDoesNotBecomeALateAutomaticDeadline() {
        val fixture = Fixture(mismatch = false)
        fixture.onGenerate = {
            fixture.runner.cancel()
            fixture.clock.advance(60)
            fixture.scheduler.fireAllIncludingCancelled()
        }
        val report = fixture.run(configuration().copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 50)))
        assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
        assertEquals(VoiceSpeechBenchmarkBackendState.CANCELLED, report.results.first().state)
        assertNull(report.results.first().deadline)
        assertEquals(1, fixture.engines.size)
        assertEquals(1, fixture.engines.values.single().cancellations)
        assertTrue(fixture.engines.values.single().closed)
    }

    @Test fun failedClosePreventsAnotherBackendAndDoesNotClaimRetirement() {
        val fixture = Fixture(mismatch = false)
        fixture.failClose = true
        val report = fixture.run()
        assertTrue(fixture.runner.nativeCleanupPending)
        assertEquals(VoiceSpeechBenchmarkRunState.INTERRUPTED, report.state)
        assertEquals(VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING, report.issue)
        assertEquals(VoiceSpeechBenchmarkCleanupState.FAILED, report.results.first().cleanupState)
        assertEquals(1, fixture.engines.size)
        assertTrue(report.results.drop(1).all { it.state == VoiceSpeechBenchmarkBackendState.NOT_RUN })
        assertNull(report.recommendation())
    }

    @Test fun cancellationDuringWatchdogPublicationCannotEscapeTheOwnerFinally() {
        var opened = false
        lateinit var runner: VoiceSpeechBenchmarkRunner
        runner = VoiceSpeechBenchmarkRunner(
            create = {
                opened = true
                error("Cancelled preparation must not open")
            },
            thermalStatus = { 0 },
            watchdogFactory = { limits, progress, stop ->
                VoiceSpeechBenchmarkWatchdog(limits, stage = progress, onTimeout = stop).also {
                    // Reproduce the closed watchdog observed when cancel wins immediately
                    // after publication and before the backend timer is armed.
                    runner.cancel()
                    it.close()
                }
            }
        )
        val report = runner.run(VoiceSpeechBenchmarkReport(1, configuration())) {}
        assertFalse(opened)
        assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, report.state)
        assertEquals(VoiceSpeechBenchmarkCleanupState.RETIRED, report.results.first().cleanupState)
        assertNull(report.results.first().failure)
    }

    @Test fun failingTimeoutObserverCannotPreventCancellationOrNativeClose() {
        val fixture = Fixture(mismatch = false)
        fixture.onGenerate = {
            fixture.clock.advance(60)
            fixture.scheduler.fireDue()
        }
        val failure = runCatching {
            fixture.run(configuration().copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 50))) { report ->
                if (report.results.first().deadline != null && report.results.first().cleanupState == VoiceSpeechBenchmarkCleanupState.PENDING) error("OBSERVER_FAILURE")
            }
        }.exceptionOrNull()
        assertEquals("OBSERVER_FAILURE", failure?.message)
        assertEquals(1, fixture.engines.size)
        assertEquals(1, fixture.engines.values.single().cancellations)
        assertTrue(fixture.engines.values.single().closed)
        assertFalse(fixture.runner.nativeCleanupPending)
    }

    @Test fun stabilityProducesTwentyWarmObservationsForEachIndependentPercentile() {
        val fixture = Fixture(mismatch = false)
        val report = fixture.run(configuration(listOf(VoiceSpeechBackend.CPU)).copy(mode = VoiceSpeechBenchmarkMode.STABILITY))
        val cpu = report.results.single()
        assertEquals(63, cpu.samples.size)
        for (sample in VoiceSpeechBenchmarkFixture.entries) {
            val statistics = cpu.fixtureStatistics(sample)
            assertEquals(20, statistics.warmSampleCount)
            val expected = cpu.samples.filter { it.fixture == sample && it.warm }.map { it.generationMs }.sorted()[18]
            assertEquals(expected, statistics.p95WarmMs)
            val missing = cpu.copy(samples = cpu.samples.filterNot { it.fixture == sample && it.repeat == 20 })
            assertNull(missing.fixtureStatistics(sample).p95WarmMs)
        }
        assertEquals(VoiceSpeechBackend.CPU, report.recommendation())
    }

    @Test fun otherModelsCannotEnterNativeEquivalenceOrRunTheNativeBenchmarkByMistake() {
        val config = configuration(listOf(VoiceSpeechBackend.CPU)).copy(engine = VoiceSpeechBenchmarkEngine.PHONON, backends = listOf(VoiceSpeechBackend.PHONON_CPU))
        val report = VoiceSpeechBenchmarkReport(1, config, state = VoiceSpeechBenchmarkRunState.COMPLETE)
        assertTrue(report.results.isEmpty())
        assertTrue(report.qualifiedResults().isEmpty())
        assertNull(report.recommendation())
        assertEquals(VoiceSpeechAudioReferenceStatus.UNOBSERVED, report.audioReference(VoiceSpeechBenchmarkBackendResult(VoiceSpeechBackend.CPU)).comparison.status)
        var opened = false
        val failure = runCatching {
            VoiceSpeechBenchmarkRunner(create = {
                opened = true
                error("Must not open")
            }).run(report) {}
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(opened)
    }

    @Test fun unmeasuredNativeAudioZerosRemainUnknownInFailureObservation() {
        val evidence = VoiceSpeechExecutionEvidence(VoiceSpeechBackend.QNN_GPU, "qnn-gpu", "a".repeat(64), "runtime", "device", VoiceSpeechStage.VALIDATING, observedAtMillis = 1, audioRms = 0.0, audioPeak = 0.0, clippedSamples = 0, nonSilentSamples = 0, audioMeasurementAvailable = false, validationReason = "AUDIO_TOO_LONG", outputFrames = 900_000)
        val unmeasured = VoiceSpeechBenchmarkFailureObservation.from(evidence)
        assertNull(unmeasured.audioRms)
        assertNull(unmeasured.audioPeak)
        assertNull(unmeasured.nonSilentSamples)
        assertEquals(false, unmeasured.audioMeasurementAvailable)
        assertEquals("AUDIO_TOO_LONG", unmeasured.validationReason)
        assertEquals(900_000L, unmeasured.outputFrames)
        val measured = VoiceSpeechBenchmarkFailureObservation.from(evidence.copy(audioMeasurementAvailable = true, audioFinite = true, validationReason = "SILENT_PCM"))
        assertEquals(0.0, measured.audioRms!!, 0.0)
        assertEquals(0L, measured.nonSilentSamples)
        assertNotNull(measured.audioFinite)
    }

    private class Clock {
        private val value = AtomicLong()
        fun nanos() = value.get()
        fun advance(ms: Long) {
            value.addAndGet(ms * 1_000_000)
        }
    }

    private class Scheduler(private val clock: Clock) : VoiceSpeechBenchmarkScheduler {
        private data class Task(val due: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable = Task(clock.nanos() + delayMs * 1_000_000, task).also { tasks += it }.let { scheduled -> AutoCloseable { scheduled.cancelled = true } }
        fun fireDue() = tasks.toList().filter { !it.cancelled && it.due <= clock.nanos() }.forEach { it.run() }
        fun fireAllIncludingCancelled() = tasks.toList().forEach { it.run() }
    }

    private class Fixture(private val mismatch: Boolean = true) {
        val clock = Clock()
        val scheduler = Scheduler(clock)
        val engines = linkedMapOf<VoiceSpeechBackend, Engine>()
        var onGenerate: (Engine) -> Unit = {}
        var onCreate: (VoiceSpeechBackend) -> Unit = {}
        var failClose = false
        var preparationCancellations = 0
        lateinit var runner: VoiceSpeechBenchmarkRunner

        fun run(config: VoiceSpeechBenchmarkConfiguration = configuration(), publish: (VoiceSpeechBenchmarkReport) -> Unit = {}): VoiceSpeechBenchmarkReport {
            runner = VoiceSpeechBenchmarkRunner(
                create = { backend ->
                    onCreate(backend)
                    Engine(backend, this, if (mismatch && backend == VoiceSpeechBackend.QNN_GPU) 40 else 100).also { engines[backend] = it }
                },
                cancelPreparation = { preparationCancellations++ },
                thermalStatus = { 0 },
                nanoTime = clock::nanos,
                wallTime = { 1000 },
                watchdogFactory = { limits, progress, stop -> VoiceSpeechBenchmarkWatchdog(limits, clock::nanos, progress, stop, scheduler) }
            )
            return runner.run(VoiceSpeechBenchmarkReport(1, config), publish)
        }
    }

    private class Engine(val backend: VoiceSpeechBackend, private val fixture: Fixture, private val units: Int) : VoicePhraseEngine {
        var calls = 0
        var cancellations = 0
        var evidenceReads = 0
        var closed = false
        private var active = false
        override val executionProgress get() = VoiceSpeechProgress(VoiceSpeechStage.SYNTHESIS, 1, 1, 0, 0, cancellations > 0, active)
        override val executionEvidence: VoiceSpeechExecutionEvidence
            get() {
                evidenceReads++
                return VoiceSpeechExecutionEvidence(backend, VoiceSpeechBenchmarkBackendResult.provider(backend), "a".repeat(64), "runtime", "device", VoiceSpeechStage.READY, acceleratorKernels = if (backend == VoiceSpeechBackend.CPU) 0 else 40, cpuKernels = 50, tokenCount = 3, verifiedAudio = true, observedAtMillis = 1, profileAvailable = true)
            }
        override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
            active = true
            calls++
            try {
                fixture.onGenerate(this)
                fixture.clock.advance(10)
                return VoicePhraseAudio(FloatArray(units * 600) { .1f }, longArrayOf(units - 2L, 1, 1), intArrayOf(1, 2))
            } finally {
                active = false
            }
        }
        override fun resetCancellation() = Unit
        override fun cancel() {
            cancellations++
        }
        override fun close() {
            assertFalse("A watchdog must not close an active native call", active)
            fixture.clock.advance(10)
            if (fixture.failClose) error("CLOSE_NOT_CONFIRMED")
            closed = true
        }
    }

    private companion object {
        fun configuration(backends: List<VoiceSpeechBackend> = VoiceSpeechBenchmarkConfiguration.LOCAL_BACKENDS) = VoiceSpeechBenchmarkConfiguration(
            VoiceSpeechBenchmarkMode.QUICK, backends, KokoroVoice.HEART, 2, 1.1f, "a".repeat(64), "runtime", 149, 36, "b".repeat(64)
        )
    }
}
