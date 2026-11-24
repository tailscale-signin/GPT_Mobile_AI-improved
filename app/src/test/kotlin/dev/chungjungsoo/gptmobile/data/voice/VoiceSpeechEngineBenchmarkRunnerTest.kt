package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechEngineBenchmarkRunnerTest {
    @Test fun everySdkEngineUsesTheSelectedFactoryAndRetainsAllFixedInputWithoutInventedPlacement() {
        VoiceSpeechBenchmarkEngine.entries.filter { it != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO }.forEach { selected ->
            val time = AtomicLong()
            val opened = mutableListOf<VoiceSpeechBackend>()
            val streams = mutableListOf<String>()
            val closes = AtomicInteger()
            val privacy = ConversationPrivacyContext(91, true)
            val runner = VoiceSpeechEngineBenchmarkRunner(
                factory = { backend ->
                    opened += backend
                    factory(backend) {
                        object : TestEngine() {
                            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                                assertEquals(privacy, ConversationPrivacyContext.capture())
                                streams += text
                                time.addAndGet(5_000_000)
                                assertTrue(emit(VoicePcmChunk(FloatArray(240) { .1f })))
                                time.addAndGet(20_000_000)
                                assertTrue(emit(VoicePcmChunk(FloatArray(240) { .2f })))
                                time.addAndGet(7_000_000)
                            }
                            override fun close() {
                                closes.incrementAndGet()
                            }
                        }
                    }
                },
                directory = File("unused-sdk-directory"),
                thermalStatus = { 0 },
                nanoTime = time::get,
                scheduler = QuietScheduler
            )
            val report = ConversationPrivacyContext.withCaptured(privacy) { runner.run(report(selected)) {} }
            assertEquals(selected.supportedBackends, opened)
            assertEquals(opened.size, closes.get())
            assertTrue(report.results.isEmpty())
            assertNull(report.recommendation())
            assertTrue(report.qualifiedResults().isEmpty())
            assertFalse(runner.nativeCleanupPending)
            report.engineResults.forEach { result ->
                assertEquals(VoiceSpeechBenchmarkBackendState.COMPLETE, result.state)
                assertEquals("RETIRED", result.cleanupState)
                assertNull(result.observedProvider)
                assertEquals("NOT_EXPOSED_BY_SDK", result.providerEvidenceStatus)
                assertEquals(9, result.samples.size)
                result.samples.forEach { sample ->
                    val source = VoiceSpeechWorkload.phrases(sample.fixture).joinToString(" ")
                    assertEquals(VoiceSpeechWorkload.digest(source), sample.inputSha256)
                    assertEquals(source.toByteArray().size, sample.inputBytes)
                    assertTrue(sample.validatedAudio)
                    assertEquals(2 * sample.streamCalls, sample.pcmChunks)
                    assertEquals(5L, sample.firstPcmMs)
                    assertTrue(checkNotNull(sample.lastPcmMs) < sample.generationMs)
                    assertEquals(20L, sample.maximumChunkGapMs)
                    assertEquals(480L * sample.streamCalls, sample.audioFrames)
                }
            }
            val expected = opened.flatMap {
                VoiceSpeechBenchmarkFixture.entries.flatMap { fixture -> List(3) { VoiceSpeechWorkload.phrases(fixture).joinToString(" ").trim() } }
            }.joinToString(" ").replace(Regex("\\s+"), " ")
            assertEquals(expected, streams.joinToString(" ").trim().replace(Regex("\\s+"), " "))
        }
    }

    @Test fun cancellationRetainsPartialPcmAndNeverDestroysTheBlockedProducer() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean()
        val closed = AtomicBoolean()
        val engine = object : TestEngine() {
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                try {
                    emit(VoicePcmChunk(FloatArray(240) { .1f }))
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                } finally {
                    returned.set(true)
                }
            }
            override fun cancel() = cancelled.countDown()
            override fun close() {
                assertTrue(returned.get())
                closed.set(true)
            }
        }
        val runner = VoiceSpeechEngineBenchmarkRunner({ factory(it) { engine } }, File("unused"), thermalStatus = { 0 })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<VoiceSpeechBenchmarkReport> { runner.run(report()) {} }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            runner.cancel()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            assertFalse(closed.get())
            assertFalse(operation.isDone)
            release.countDown()
            val result = operation.get(3, TimeUnit.SECONDS)
            assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, result.state)
            val row = result.engineResults.single()
            assertEquals(1, row.partialSample?.pcmChunks)
            assertEquals(240L, row.partialSample?.audioFrames)
            assertEquals(false, row.partialSample?.validatedAudio)
            assertEquals("RETIRED", row.cleanupState)
            assertTrue(closed.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun chunkDeadlinePreservesTheFirstCallbackAndClosesAfterTheCallReturns() {
        val time = AtomicLong()
        val closed = AtomicBoolean()
        val cancels = AtomicInteger()
        val runner = VoiceSpeechEngineBenchmarkRunner(
            { backend ->
                factory(backend) {
                    object : TestEngine() {
                        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                            time.addAndGet(1_000_000)
                            emit(VoicePcmChunk(floatArrayOf(.1f)))
                            time.addAndGet(40_000_000)
                        }
                        override fun cancel() {
                            cancels.incrementAndGet()
                        }
                        override fun close() {
                            closed.set(true)
                        }
                    }
                }
            },
            File("unused"),
            thermalStatus = { 0 },
            nanoTime = time::get,
            scheduler = QuietScheduler
        )
        val initial = report().let { it.copy(configuration = it.configuration.copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 30))) }
        val row = runner.run(initial) {}.engineResults.single()
        assertEquals("DEADLINE", row.failureCode)
        assertEquals("CHUNK", row.timeoutScope)
        assertEquals(30L, row.timeoutLimitMs)
        assertEquals(41L, row.timeoutElapsedMs)
        assertEquals(1, row.partialSample?.pcmChunks)
        assertEquals(1, cancels.get())
        assertTrue(closed.get())
    }

    @Test fun recordedDeadlineStopsTheOwnerAndSurvivesRetirementBeforeItsCallbackCanRun() {
        listOf(false, true).forEach { linkageFailure ->
            val time = AtomicLong()
            val scheduler = ManualScheduler(time)
            val streamEntered = CountDownLatch(1)
            val releaseStream = CountDownLatch(1)
            val deadlineRecorded = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val streamReturned = AtomicBoolean()
            val streams = AtomicInteger()
            val cancels = AtomicInteger()
            val closes = AtomicInteger()
            val notices = AtomicInteger()
            val snapshots = CopyOnWriteArrayList<VoiceSpeechBenchmarkReport>()
            val engine = object : TestEngine() {
                override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                    streams.incrementAndGet()
                    try {
                        emit(VoicePcmChunk(FloatArray(240) { .1f }))
                        streamEntered.countDown()
                        check(releaseStream.await(3, TimeUnit.SECONDS))
                        if (linkageFailure) throw LinkageError("Fixture native call failed before the next owner checkpoint")
                    } finally {
                        streamReturned.set(true)
                    }
                }
                override fun cancel() {
                    cancels.incrementAndGet()
                }
                override fun close() {
                    assertTrue(streamReturned.get())
                    closes.incrementAndGet()
                }
            }
            val runner = VoiceSpeechEngineBenchmarkRunner(
                { factory(it) { engine } },
                File("unused"),
                thermalStatus = { 0 },
                nanoTime = time::get,
                onStopping = { notices.incrementAndGet() },
                watchdogFactory = { limits, notify ->
                    VoiceSpeechBenchmarkWatchdog(
                        limits,
                        time::get,
                        onTimeout = { observed ->
                            // The actual watchdog has already recorded the deadline and
                            // released its lock; hold only delivery to the runner callback.
                            deadlineRecorded.countDown()
                            check(releaseCallback.await(10, TimeUnit.SECONDS))
                            notify(observed)
                        },
                        scheduler = scheduler
                    )
                }
            )
            val initial = report().let { it.copy(configuration = it.configuration.copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 30))) }
            val owner = Executors.newSingleThreadExecutor()
            val timer = Executors.newSingleThreadExecutor()
            try {
                val operation = owner.submit<VoiceSpeechBenchmarkReport> { runner.run(initial) { snapshots += it } }
                assertTrue(streamEntered.await(3, TimeUnit.SECONDS))
                val callback = timer.submit { scheduler.advanceMs(31) }
                assertTrue(deadlineRecorded.await(3, TimeUnit.SECONDS))
                assertFalse(callback.isDone)
                assertEquals(0, cancels.get())
                assertEquals(0, closes.get())
                releaseStream.countDown()
                val row = operation.get(3, TimeUnit.SECONDS).engineResults.single()
                assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, row.state)
                assertEquals("DEADLINE", row.failureCode)
                assertEquals("CHUNK", row.timeoutScope)
                assertEquals(30L, row.timeoutLimitMs)
                assertEquals(31L, row.timeoutElapsedMs)
                assertEquals("RETIRED", row.cleanupState)
                assertTrue(row.samples.isEmpty())
                assertEquals(1, row.partialSample?.pcmChunks)
                assertEquals(240L, row.partialSample?.audioFrames)
                assertEquals(false, row.partialSample?.validatedAudio)
                assertEquals(1, streams.get())
                assertEquals(1, cancels.get())
                assertEquals(1, closes.get())
                assertEquals(0, notices.get())
                assertFalse(runner.nativeCleanupPending)
                val published = snapshots.toList()
                releaseCallback.countDown()
                callback.get(3, TimeUnit.SECONDS)
                assertEquals(published, snapshots.toList())
                assertEquals(1, cancels.get())
                assertEquals(1, closes.get())
                assertEquals(0, notices.get())
            } finally {
                releaseStream.countDown()
                releaseCallback.countDown()
                owner.shutdownNow()
                timer.shutdownNow()
            }
        }
    }

    @Test fun throwingRetirementObserverCannotSkipCloseOrFailedCloseQuarantine() {
        listOf(false, true).forEach { closeFails ->
            val closes = AtomicInteger()
            val opens = AtomicInteger()
            val observerFailure = IllegalStateException("Test observer stopped")
            val runner = VoiceSpeechEngineBenchmarkRunner({ backend ->
                factory(backend) {
                    opens.incrementAndGet()
                    object : TestEngine() {
                        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                            emit(VoicePcmChunk(floatArrayOf(.1f)))
                        }
                        override fun close() {
                            closes.incrementAndGet()
                            if (closeFails) error("Native destructor failed")
                        }
                    }
                }
            }, File("unused"), thermalStatus = { 0 })
            val failure = runCatching {
                runner.run(report()) { snapshot ->
                    if (snapshot.engineResults.single().cleanupState == "PENDING") throw observerFailure
                }
            }.exceptionOrNull()
            assertSame(observerFailure, failure)
            assertEquals(1, closes.get())
            assertEquals(closeFails, runner.nativeCleanupPending)
            if (closeFails) {
                assertTrue(runCatching { runner.run(report()) {} }.isFailure)
                assertEquals(1, opens.get())
            }
        }
    }

    @Test fun blockedDeadlinePublishesImmutableEvidenceBeforeReturnAndSurvivesAThrowingObserver() {
        listOf(false, true).forEach { observerThrows ->
            val time = AtomicLong()
            val scheduler = ManualScheduler(time)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closes = AtomicInteger()
            val cancels = AtomicInteger()
            val notices = AtomicInteger()
            val snapshots = CopyOnWriteArrayList<VoiceSpeechBenchmarkReport>()
            val observerFailure = IllegalStateException("Deadline observer stopped")
            val engine = object : TestEngine() {
                override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                    emit(VoicePcmChunk(floatArrayOf(.1f)))
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                override fun cancel() {
                    cancels.incrementAndGet()
                }
                override fun close() {
                    closes.incrementAndGet()
                }
            }
            val runner = VoiceSpeechEngineBenchmarkRunner(
                { factory(it) { engine } },
                File("unused"),
                thermalStatus = { 0 },
                nanoTime = time::get,
                onStopping = { notices.incrementAndGet() },
                scheduler = scheduler
            )
            val initial = report().let { it.copy(configuration = it.configuration.copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 30))) }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val operation = worker.submit<Result<VoiceSpeechBenchmarkReport>> {
                    runCatching {
                        runner.run(initial) { snapshot ->
                            snapshots += snapshot
                            if (observerThrows && snapshot.engineResults.single().timeoutScope != null) throw observerFailure
                        }
                    }
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                scheduler.advanceMs(31)
                val pending = snapshots.last().engineResults.single()
                assertEquals("CHUNK", pending.timeoutScope)
                assertEquals(30L, pending.timeoutLimitMs)
                assertEquals(31L, pending.timeoutElapsedMs)
                assertEquals("PENDING", pending.cleanupState)
                assertNull(pending.partialSample)
                assertEquals(1, cancels.get())
                assertEquals(1, notices.get())
                assertEquals(0, closes.get())
                assertFalse(operation.isDone)
                release.countDown()
                val completed = operation.get(3, TimeUnit.SECONDS)
                if (observerThrows) {
                    assertSame(observerFailure, completed.exceptionOrNull())
                } else {
                    val row = completed.getOrThrow().engineResults.single()
                    assertEquals("DEADLINE", row.failureCode)
                    assertEquals(1, row.partialSample?.pcmChunks)
                    assertEquals("RETIRED", row.cleanupState)
                }
                assertEquals(1, cancels.get())
                assertEquals(1, closes.get())
                assertFalse(runner.nativeCleanupPending)
                assertEquals("PENDING", pending.cleanupState)
            } finally {
                release.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test fun explicitStopSuppressesLaterDeadlineAndRequestsNativeCancellationOnlyOnce() {
        val time = AtomicLong()
        val scheduler = ManualScheduler(time)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger()
        val notices = AtomicInteger()
        val runner = VoiceSpeechEngineBenchmarkRunner(
            { backend ->
                factory(backend) {
                    object : TestEngine() {
                        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                        }
                        override fun cancel() {
                            cancels.incrementAndGet()
                        }
                    }
                }
            },
            File("unused"),
            thermalStatus = { 0 },
            nanoTime = time::get,
            onStopping = { notices.incrementAndGet() },
            scheduler = scheduler
        )
        val initial = report().let { it.copy(configuration = it.configuration.copy(deadlines = VoiceSpeechBenchmarkDeadlines(chunkMs = 30))) }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<VoiceSpeechBenchmarkReport> { runner.run(initial) {} }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            runner.cancel()
            runner.cancel()
            scheduler.advanceMs(31)
            assertEquals(1, cancels.get())
            assertEquals(0, notices.get())
            release.countDown()
            val result = operation.get(3, TimeUnit.SECONDS)
            assertEquals(VoiceSpeechBenchmarkRunState.CANCELLED, result.state)
            assertEquals("CANCELLED", result.engineResults.single().failureCode)
            assertNull(result.engineResults.single().timeoutScope)
            assertEquals(1, cancels.get())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun failedPreparationRetirementPreventsTheNextDelegateFromStarting() {
        val opens = AtomicInteger()
        val selected = VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO
        val runner = VoiceSpeechEngineBenchmarkRunner(
            { backend ->
                object : VoicePhraseEngineFactory {
                    override val selectedBackend = backend
                    override val identity = "retirement-failure-test"
                    override val requiresKokoroAssets = false
                    override var nativeCleanupPending = false
                    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                        opens.incrementAndGet()
                        nativeCleanupPending = true
                        error("Native task destructor failed")
                    }
                }
            },
            File("unused"),
            thermalStatus = { 0 }
        )
        val result = runner.run(report(selected)) {}
        assertTrue(runner.nativeCleanupPending)
        assertEquals(1, opens.get())
        assertEquals(VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING, result.issue)
        assertEquals("FAILED", result.engineResults.first().cleanupState)
        assertEquals("NATIVE_RETIREMENT_FAILED", result.engineResults.first().failureCode)
        assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, result.engineResults.last().state)
    }

    @Test fun failedEngineCloseKeepsOwnershipQuarantinedAndDoesNotStartAnotherBackend() {
        val opens = AtomicInteger()
        val runner = VoiceSpeechEngineBenchmarkRunner(
            { backend ->
                factory(backend) {
                    opens.incrementAndGet()
                    object : TestEngine() {
                        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                            emit(VoicePcmChunk(floatArrayOf(.1f)))
                        }
                        override fun close(): Unit = error("Native destruction failed")
                    }
                }
            },
            File("unused"),
            thermalStatus = { 0 }
        )
        val result = runner.run(report(VoiceSpeechBenchmarkEngine.MEDIAPIPE_KOKORO)) {}
        assertEquals(1, opens.get())
        assertTrue(runner.nativeCleanupPending)
        assertEquals(VoiceSpeechBenchmarkIssue.NATIVE_CLEANUP_PENDING, result.issue)
        assertEquals(VoiceSpeechBenchmarkBackendState.NOT_RUN, result.engineResults.last().state)
    }

    @Test fun missingModelNeverCreatesAnEngineOrStartsADownload() {
        val runner = VoiceSpeechEngineBenchmarkRunner(
            { backend ->
                object : VoicePhraseEngineFactory {
                    override val selectedBackend = backend
                    override val identity = "missing-model"
                    override val requiresKokoroAssets = false
                    override fun isReady() = false
                    override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Uninstalled models cannot be opened")
                }
            },
            File("unused"),
            thermalStatus = { 0 }
        )
        val row = runner.run(report()) {}.engineResults.single()
        assertEquals(VoiceSpeechBenchmarkBackendState.UNSUPPORTED, row.state)
        assertEquals("MODEL_UNAVAILABLE", row.failureCode)
        assertTrue(row.samples.isEmpty())
    }

    @Test fun silentOrNonFinitePcmFailsWithoutBecomingACompleteSample() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f).forEach { invalid ->
            val runner = VoiceSpeechEngineBenchmarkRunner({ backend ->
                factory(backend) {
                    object : TestEngine() {
                        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                            emit(VoicePcmChunk(floatArrayOf(invalid)))
                        }
                    }
                }
            }, File("unused"), thermalStatus = { 0 })
            val row = runner.run(report()) {}.engineResults.single()
            assertEquals(VoiceSpeechBenchmarkBackendState.FAILED, row.state)
            assertEquals("OUTPUT_INVALID", row.failureCode)
            assertTrue(row.samples.isEmpty())
        }
    }

    @Test fun stabilityPercentilesNeedTwentySuccessfulWarmSamplesOfTheSameFixture() {
        val time = AtomicLong()
        val runner = VoiceSpeechEngineBenchmarkRunner(
            { backend ->
                factory(backend) {
                    object : TestEngine() {
                        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                            time.addAndGet(1_000_000)
                            emit(VoicePcmChunk(floatArrayOf(.1f)))
                        }
                    }
                }
            },
            File("unused"),
            thermalStatus = { 0 },
            nanoTime = time::get,
            scheduler = QuietScheduler
        )
        val initial = report().let { it.copy(configuration = it.configuration.copy(mode = VoiceSpeechBenchmarkMode.STABILITY)) }
        val result = runner.run(initial) {}.engineResults.single()
        val stats = result.fixtureStatistics(VoiceSpeechBenchmarkFixture.SHORT)
        assertEquals(20, stats.warmSampleCount)
        assertEquals(1L, stats.p95WarmMs)
        assertNull(result.copy(samples = result.samples.filterNot { it.fixture == VoiceSpeechBenchmarkFixture.SHORT && it.repeat == 20 }).fixtureStatistics(VoiceSpeechBenchmarkFixture.SHORT).p95WarmMs)
    }

    private fun report(engine: VoiceSpeechBenchmarkEngine = VoiceSpeechBenchmarkEngine.PHONON): VoiceSpeechBenchmarkReport {
        val model = MediaPipeSpeechCatalog.model(checkNotNull(engine.mediaPipeEngine()))
        return VoiceSpeechBenchmarkReport(
            1,
            VoiceSpeechBenchmarkConfiguration(
                VoiceSpeechBenchmarkMode.QUICK, engine.supportedBackends, KokoroVoice.HEART, 2, 1f,
                model.sha256, MediaPipeSpeechCatalog.RUNTIME_ID, 149, 36, "local-test-device",
                engine = engine, engineVoiceId = model.defaultVoice
            )
        )
    }

    private fun factory(backend: VoiceSpeechBackend, create: () -> VoicePhraseEngine) = object : VoicePhraseEngineFactory {
        override val selectedBackend = backend
        override val identity = "sdk-test-${backend.name}"
        override val requiresKokoroAssets = false
        override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
            assertEquals("", lexicon)
            return create()
        }
    }

    private abstract class TestEngine : VoicePhraseEngine {
        override val supportsStreaming = true
        override fun resetCancellation() = Unit
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private object QuietScheduler : VoiceSpeechBenchmarkScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable = AutoCloseable { }
    }

    private class ManualScheduler(private val time: AtomicLong) : VoiceSpeechBenchmarkScheduler {
        private data class Scheduled(val due: Long, val task: () -> Unit, val cancelled: AtomicBoolean = AtomicBoolean())
        private val scheduled = CopyOnWriteArrayList<Scheduled>()
        override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable {
            val entry = Scheduled(time.get() + delayMs * 1_000_000, task)
            scheduled += entry
            return AutoCloseable { entry.cancelled.set(true) }
        }
        fun advanceMs(ms: Long) {
            time.addAndGet(ms * 1_000_000)
            scheduled.filter { it.due <= time.get() && !it.cancelled.get() }.forEach { it.task() }
        }
    }
}
