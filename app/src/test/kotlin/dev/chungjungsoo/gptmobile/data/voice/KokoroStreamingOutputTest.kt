package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowSystemClock

/** Actual output workers with deterministic AudioTrack presentation; no device speed claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [KokoroPlaybackObservationTest.ImmediatePcmTrack::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KokoroStreamingOutputTest {
    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun aFullyVoicedOpenInputPlaysBeforeTheProviderSendsMoreText() {
        val presented = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reserves = CopyOnWriteArrayList<Pair<Long, String>>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val inputs = CopyOnWriteArrayList<String>()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override val supportsIncrementalInput = true
            override fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                inputs += requireNotNull(nextText())
                ShadowSystemClock.advanceBy(1_800, TimeUnit.MILLISECONDS)
                check(emit(VoicePcmChunk(FloatArray(1_200) { .1f }, wordCues = listOf(VoiceWordCue(0, 5, 0, 1_200)))))
                inputs += requireNotNull(nextText())
                check(emit(VoicePcmChunk(FloatArray(2_400) { .2f }, wordCues = listOf(VoiceWordCue(7, 11, 1_200, 3_600)))))
                check(nextText() == null)
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun reserveCompleted(phase: String, elapsedMs: Long, targetMs: Long, acceptedMs: Long, exitReason: String) {
                if (phase == "startup") reserves += targetMs to exitReason
            }
            override fun firstPresented(clockBasis: String, route: String) {
                presented.countDown()
            }
        }
        withStreamingOutput(false, VoicePauseDuration.NATURAL, engine, observer) { output ->
            output.configurePacing(VoicePauseDuration.NATURAL, VoicePerformanceMode.AUTO)
            val reply = requireNotNull(
                output.openReplyWithFailure(KokoroVoice.HEART, { finished.countDown() }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
            )
            try {
                reply.enqueue("hello. ")
                awaitMain(presented)
                assertEquals(0L, presented.count)
                assertEquals(listOf(100L to "producer_idle"), reserves)
                assertEquals(1L, finished.count)
                reply.enqueue("word")
            } finally {
                reply.finish()
            }
            awaitMain(finished, advancePlaybackClock = true)
            assertEquals(0L, finished.count)
            assertTrue(failures.isEmpty())
            assertEquals(listOf("hello. ", "word"), inputs)
            assertEquals(3_600, PausePcmTrack.writes.sumOf { it.size })
        }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun audioTrackIsPreparedDuringModelLoadAndWaitsForValidatedAudio() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val trackReady = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val played = AtomicBoolean()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                emit(VoicePcmChunk(FloatArray(2_400) { .1f }))
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun preparationPhase(stage: String, elapsedMs: Long) {
                if (stage == "AUDIO_TRACK_SETUP") trackReady.countDown()
            }
            override fun trackPlayRequested() {
                played.set(true)
            }
        }
        withStreamingOutput(false, VoicePauseDuration.SHORT, engine, observer, beforeCreate = {
            loading.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }) { output ->
            try {
                output.speakWithFailure("Hello.", KokoroVoice.HEART, { finished.countDown() }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
                awaitMain(loading)
                assertEquals(0L, loading.count)
                awaitMain(trackReady)
                assertEquals("AudioTrack setup should complete with native model loading still blocked", 0L, trackReady.count)
                assertTrue(PausePcmTrack.writes.isEmpty())
                assertFalse(played.get())
            } finally {
                release.countDown()
            }
            awaitMain(finished, advancePlaybackClock = true)
            assertEquals(0L, finished.count)
            assertTrue("Playback failures: ${failures.map { it.code }}", failures.isEmpty())
            assertEquals(2_400, PausePcmTrack.writes.sumOf { it.size })
            assertTrue(played.get())
        }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun answerHandoffReusesAnOpeningGraphAndUserStopStillCancelsIt() {
        for (userStops in listOf(false, true)) {
            val loading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val loads = AtomicInteger()
            val cancelledDuringLoad = AtomicBoolean()
            val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
            val words = CopyOnWriteArrayList<String>()
            val engine = object : VoicePhraseEngine {
                override val supportsStreaming = true
                override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                    words += text
                    emit(VoicePcmChunk(FloatArray(2_400) { .1f }))
                }
                override fun resetCancellation() = Unit
                override fun cancel() = Unit
                override fun close() = Unit
            }
            withStreamingOutput(false, VoicePauseDuration.SHORT, engine, beforeCreate = {
                loads.incrementAndGet()
                loading.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }, onCancelPending = {
                if (loading.count == 0L && release.count > 0L) cancelledDuringLoad.set(true)
            }) { output ->
                try {
                    output.speakWithFailure("Hello.", KokoroVoice.HEART, { error("The retired cue completed") }, { failure, _ -> failures += failure })
                    awaitMain(loading)
                    assertEquals(0L, loading.count)
                    output.stop("answer_ready")
                    output.speakWithFailure("Voice.", KokoroVoice.HEART, { finished.countDown() }, { failure, _ ->
                        failures += failure
                        finished.countDown()
                    })
                    assertFalse(cancelledDuringLoad.get())
                    if (userStops) {
                        output.stop("user_stop")
                        assertTrue(cancelledDuringLoad.get())
                    }
                } finally {
                    release.countDown()
                }
                if (userStops) {
                    output.close()
                    assertTrue(output.awaitClosed(5_000))
                    assertTrue(words.isEmpty())
                } else {
                    awaitMain(finished, advancePlaybackClock = true)
                    assertEquals(0L, finished.count)
                    assertEquals(listOf("Voice."), words.toList())
                    assertEquals(1, loads.get())
                }
                assertTrue(failures.isEmpty())
            }
        }
    }

    @Test fun incrementalReplyPlaysBeforeFinalTextAndKeepsOriginalSegmentCaptions() {
        val firstPresented = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val inputs = CopyOnWriteArrayList<String>()
        val prepared = CopyOnWriteArrayList<Pair<Int, IntRange>>()
        val streams = AtomicInteger()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override val supportsIncrementalInput = true
            override fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                streams.incrementAndGet()
                var bytes = 0
                var frames = 0L
                while (true) {
                    val text = nextText() ?: break
                    inputs += text
                    val word = Regex("[a-z]+").find(text)!!
                    assertTrue(emit(VoicePcmChunk(FloatArray(12_000) { .1f }, wordCues = listOf(VoiceWordCue(bytes + word.range.first, bytes + word.range.last + 1, frames, frames + 12_000)))))
                    bytes += text.toByteArray(Charsets.UTF_8).size
                    frames += 12_000
                }
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun firstPresented(clockBasis: String, route: String) {
                firstPresented.countDown()
            }
            override fun captionsPrepared(segment: Int, ranges: List<IntRange>) {
                prepared += ranges.map { segment to it }
            }
        }
        withStreamingOutput(false, VoicePauseDuration.NATURAL, engine, observer) { output ->
            output.bindSpokenRange { _, _, _ -> }
            val reply = requireNotNull(
                output.openReplyWithFailure(KokoroVoice.HEART, { finished.countDown() }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
            )
            reply.enqueue("hello.")
            awaitMain(firstPresented)
            assertEquals(0L, firstPresented.count)
            assertEquals(1L, finished.count)
            reply.enqueue("word.")
            reply.finish()
            awaitMain(finished)
            assertTrue("Unexpected failure: ${failures.map { it.code }}", failures.isEmpty())
            assertEquals(1, streams.get())
            assertEquals(listOf("hello.", " word."), inputs)
            assertEquals(listOf(0 to (0..4), 1 to (0..3)), prepared)
        }
    }

    /** Dictionary prewarming borrows assets briefly before native model preparation takes its own lease. */
    private class MappedAssetLeases(models: LocalVoiceModelStore) {
        private val held = AtomicInteger()
        private val mapped = AtomicBoolean()
        private val releasedWhileMapped = AtomicBoolean()

        init {
            every { models.acquireUse() } answers {
                held.incrementAndGet()
                true
            }
            every { models.releaseUse() } answers {
                if (held.decrementAndGet() <= 0 && mapped.get()) releasedWhileMapped.set(true)
            }
        }

        fun markNativeOpen() {
            assertEquals("Native preparation must hold one independent model lease", 1, held.get())
            mapped.set(true)
        }

        fun assertNativeAssetsRetained() {
            assertFalse("Mapped assets were released before native retirement", releasedWhileMapped.get())
            assertEquals("Only the quarantined native owner should retain a lease", 1, held.get())
        }
    }

    @Test fun failedNativeDestructionRetainsTheModelLeaseAndBlocksReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("failed-speech-retirement").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" })
        val models = mockk<LocalVoiceModelStore>()
        every { models.isInstalled() } returns true
        val leases = MappedAssetLeases(models)
        every { models.modelDirectory() } returns directory
        val created = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val creations = AtomicInteger()
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "failed-retirement-model"
            override val selectedBackend = VoiceSpeechBackend.GATEWAY_CUDA
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                leases.markNativeOpen()
                creations.incrementAndGet()
                created.countDown()
                return object : VoicePhraseEngine {
                    override fun resetCancellation() = Unit
                    override fun cancel() = Unit
                    override fun close() {
                        closed.countDown()
                        error("Native handle did not retire")
                    }
                }
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().build() }, { 1f })
        try {
            output.configureEngine(factory)
            output.prewarm(KokoroVoice.HEART)
            assertTrue(created.await(3, TimeUnit.SECONDS))
            output.releaseWarmModel()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            output.prewarm(KokoroVoice.HEART)
            output.close()
            assertFalse(output.awaitClosed(1_000))
            assertEquals(1, creations.get())
            leases.assertNativeAssetsRetained()
        } finally {
            output.close()
            directory.deleteRecursively()
        }
    }

    @Test fun failedPreparationDestructionAlsoKeepsTheOuterModelLease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("failed-speech-preparation").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" })
        val models = mockk<LocalVoiceModelStore>()
        every { models.isInstalled() } returns true
        val leases = MappedAssetLeases(models)
        every { models.modelDirectory() } returns directory
        val failed = CountDownLatch(1)
        val pending = AtomicBoolean()
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "failed-preparation-retirement"
            override val selectedBackend = VoiceSpeechBackend.GATEWAY_CUDA
            override val nativeCleanupPending: Boolean get() = pending.get()
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                leases.markNativeOpen()
                pending.set(true)
                failed.countDown()
                error("Native task initialization and cleanup failed")
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().build() }, { 1f })
        try {
            output.configureEngine(factory)
            output.prewarm(KokoroVoice.HEART)
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            output.close()
            assertFalse(output.awaitClosed(1_000))
            leases.assertNativeAssetsRetained()
        } finally {
            output.close()
            directory.deleteRecursively()
        }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun gatewayRefillsLearnDeliveryCadenceBeforeTheFirstStreamCompletes() {
        assertLiveDeliveryReserve(requiresKokoroAssets = true)
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun mediaPipeRefillsLearnDeliveryCadenceBeforeTheFirstStreamCompletes() {
        assertLiveDeliveryReserve(requiresKokoroAssets = false)
    }

    @Test
    @Config(shadows = [BackpressurePcmTrack::class])
    fun fullPlaybackQueueWaitDoesNotBecomeProducerDeliveryCost() {
        assertBackpressuredStream(cancel = false)
    }

    @Test
    @Config(shadows = [BackpressurePcmTrack::class])
    fun cancellationRetiresAStreamBlockedByTheFullPlaybackQueue() {
        assertBackpressuredStream(cancel = true)
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun gatewayPauseModesLimitActualQueuedPcmAcrossCallbacksAndSegments() {
        VoicePauseDuration.entries.forEach { assertStreamingPause(requiresKokoroAssets = true, it) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun mediaPipePauseModesLimitActualQueuedPcmAcrossCallbacksAndSegments() {
        VoicePauseDuration.entries.forEach { assertStreamingPause(requiresKokoroAssets = false, it) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun gatewayCaptionStartsWhileTheProducerStillOwnsTheRestOfTheWord() {
        VoicePauseDuration.entries.forEach { assertCaptionBeforeWordCompletes(requiresKokoroAssets = true, it) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun mediaPipeCaptionStartsWhileTheProducerStillOwnsTheRestOfTheWord() {
        VoicePauseDuration.entries.forEach { assertCaptionBeforeWordCompletes(requiresKokoroAssets = false, it) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun gatewayFullyTrimmedTailClearsCaptionDuringRefillBeforeProducerCompletes() {
        listOf(VoicePauseDuration.SHORT, VoicePauseDuration.MINIMAL).forEach { assertTrimmedTailDuringRefill(true, it, pauseForFocus = false) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun mediaPipeFullyTrimmedTailClearsCaptionDuringRefillBeforeProducerCompletes() {
        listOf(VoicePauseDuration.SHORT, VoicePauseDuration.MINIMAL).forEach { assertTrimmedTailDuringRefill(false, it, pauseForFocus = false) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun gatewayFocusPauseDefersFullyTrimmedCaptionClearUntilGain() {
        listOf(VoicePauseDuration.SHORT, VoicePauseDuration.MINIMAL).forEach { assertTrimmedTailDuringRefill(true, it, pauseForFocus = true) }
    }

    @Test
    @Config(shadows = [PausePcmTrack::class])
    fun mediaPipeFocusPauseDefersFullyTrimmedCaptionClearUntilGain() {
        listOf(VoicePauseDuration.SHORT, VoicePauseDuration.MINIMAL).forEach { assertTrimmedTailDuringRefill(false, it, pauseForFocus = true) }
    }

    @Test fun normalizedGatewayExpansionPlaysWithCaptionObserversDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java))).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val directory = Files.createTempDirectory("streaming-caption-disabled").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read", "forty", "two").joinToString("\n") { "$it HH" }
        )
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } returns Unit
        every { models.modelDirectory() } returns directory
        val inputs = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val finished = CountDownLatch(1)
        val completed = AtomicBoolean()
        val presented = AtomicBoolean()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                inputs += text
                emit(
                    VoicePcmChunk(
                        FloatArray(1_800) { .1f },
                        wordCues = listOf(
                            VoiceWordCue(0, 5, 0, 900, VoiceWordCueProvenance.NATIVE_DURATION),
                            VoiceWordCue(6, 9, 900, 1_800, VoiceWordCueProvenance.NATIVE_DURATION)
                        )
                    )
                )
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "gateway-caption-disabled-test"
            override val selectedBackend = VoiceSpeechBackend.GATEWAY_CUDA
            override fun create(directory: File, lexicon: String, threads: Int) = engine
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun firstPresented(clockBasis: String, route: String) {
                presented.set(true)
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { 1f }, measurements = observer)
        try {
            output.configureEngine(factory)
            // Neither caption observer is bound. The source has three characters, while
            // the second word's normalized cue ends at byte nine in "forty two.".
            output.speakWithFailure("42.", KokoroVoice.HEART, {
                completed.set(true)
                finished.countDown()
            }, { failure, _ ->
                failures += failure
                finished.countDown()
            })
            awaitMain(finished)
            assertEquals(listOf("forty two."), inputs)
            assertTrue("Unexpected playback failure: ${failures.map { it.code }}", failures.isEmpty())
            assertTrue(completed.get())
            assertTrue(presented.get())
        } finally {
            output.close()
            output.awaitClosed(1_000)
            ShadowAudioTrack.resetTest()
            directory.deleteRecursively()
        }
    }

    @Test fun firstChunkReachesAudioTrackBeforeSynthesisFinishesAndCloseWaitsForRetirement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java))).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val models = mockk<LocalVoiceModelStore>()
        val presented = CountDownLatch(1)
        val releaseProducer = CountDownLatch(1)
        val returned = AtomicBoolean()
        val closedEarly = AtomicBoolean()
        val closed = AtomicBoolean()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val privateContext = ConversationPrivacyContext(42, true)
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override val executionProvider = "mediapipe-phonon-cpu-requested"
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                try {
                    assertEquals(privateContext, ConversationPrivacyContext.capture())
                    emit(VoicePcmChunk(FloatArray(4_800) { .1f }))
                    check(releaseProducer.await(5, TimeUnit.SECONDS))
                    emit(VoicePcmChunk(FloatArray(2_400) { .1f }))
                } finally {
                    returned.set(true)
                }
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() {
                closedEarly.set(!returned.get())
                closed.set(true)
            }
        }
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "streaming-output-lifetime-test"
            override val requiresKokoroAssets = false
            override fun create(directory: File, lexicon: String, threads: Int) = engine
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun firstPresented(clockBasis: String, route: String) {
                presented.countDown()
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { 1f }, privacy = { privateContext }, measurements = observer)
        try {
            output.configureEngine(factory)
            output.speakWithFailure("hello.", KokoroVoice.HEART, {}, { failure, _ -> failures += failure })
            awaitMain(presented)
            assertTrue("The first PCM must be presented while the producer still owns its stream", presented.count == 0L)
            assertFalse(returned.get())
            assertTrue(failures.isEmpty())
            verify { models wasNot Called }
            output.close()
            assertFalse(output.awaitClosed(25))
            assertFalse(closed.get())
            releaseProducer.countDown()
            assertTrue(output.awaitClosed(1_000))
            assertTrue(closed.get())
            assertFalse(closedEarly.get())
        } finally {
            releaseProducer.countDown()
            output.close()
            output.awaitClosed(1_000)
            ShadowAudioTrack.resetTest()
        }
    }

    @Test fun cancelledQueuedWarmupCannotBorrowTheNextRequestsIdenticalModelKeyOrPrivacy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateOwner = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val created = CountDownLatch(1)
        val creations = AtomicInteger()
        val captured = AtomicReference<ConversationPrivacyContext?>()
        val privacy = AtomicReference(ConversationPrivacyContext(1, false))
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "queued-warmup-generation-test"
            override val requiresKokoroAssets = false
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                creations.incrementAndGet()
                captured.set(ConversationPrivacyContext.capture())
                created.countDown()
                return object : VoicePhraseEngine {
                    override fun resetCancellation() = Unit
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        }
        val models = mockk<LocalVoiceModelStore>()
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { 1f }, privacy = { privacy.get() })
        try {
            output.configureEngine(factory)
            gateOwner.submit {
                VoiceNativeEngineGate.exclusive {
                    locked.countDown()
                    releaseGate.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            output.prewarm(KokoroVoice.HEART)
            output.cancelPrewarm()
            val nextPrivacy = ConversationPrivacyContext(2, true)
            privacy.set(nextPrivacy)
            output.prewarm(KokoroVoice.HEART)
            releaseGate.countDown()
            assertTrue(created.await(5, TimeUnit.SECONDS))
            assertEquals(nextPrivacy, captured.get())
            output.cancelPrewarm()
            output.prewarm(KokoroVoice.HEART)
            output.close()
            assertTrue(output.awaitClosed(1_000))
            assertEquals(1, creations.get())
            verify { models wasNot Called }
        } finally {
            releaseGate.countDown()
            output.close()
            output.awaitClosed(1_000)
            gateOwner.shutdownNow()
        }
    }

    private fun assertLiveDeliveryReserve(requiresKokoroAssets: Boolean) {
        val releaseSecond = CountDownLatch(1)
        val releaseThird = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val returned = AtomicBoolean()
        val completed = AtomicBoolean()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val reserves = CopyOnWriteArrayList<Pair<String, Long>>()
        val production = CopyOnWriteArrayList<Long>()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                try {
                    ShadowSystemClock.advanceBy(300, TimeUnit.MILLISECONDS)
                    emit(VoicePcmChunk(FloatArray(24_000) { .1f }))
                    check(releaseSecond.await(5, TimeUnit.SECONDS))
                    ShadowSystemClock.advanceBy(500, TimeUnit.MILLISECONDS)
                    emit(VoicePcmChunk(FloatArray(24_000) { .1f }))
                    check(releaseThird.await(5, TimeUnit.SECONDS))
                    ShadowSystemClock.advanceBy(700, TimeUnit.MILLISECONDS)
                    emit(VoicePcmChunk(FloatArray(48_000) { .1f }))
                    check(releaseFinish.await(5, TimeUnit.SECONDS))
                } finally {
                    returned.set(true)
                }
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) {
                production += synthesisMs
            }
            override fun reserveCompleted(phase: String, elapsedMs: Long, targetMs: Long, acceptedMs: Long, exitReason: String) {
                reserves += phase to targetMs
            }
        }
        withStreamingOutput(requiresKokoroAssets, VoicePauseDuration.NATURAL, engine, observer) { output ->
            try {
                output.speakWithFailure("hello.", KokoroVoice.HEART, {
                    completed.set(true)
                    finished.countDown()
                }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
                assertTrue(awaitMainCondition { output.synthesisFeedback.value.bufferState == "refilling" && PausePcmTrack.writes.sumOf { it.size } == 24_000 })
                releaseSecond.countDown()
                assertTrue(awaitMainCondition { output.synthesisFeedback.value.bufferState == "refilling" && PausePcmTrack.writes.sumOf { it.size } == 48_000 })
                releaseThird.countDown()
                assertTrue(awaitMainCondition { output.synthesisFeedback.value.bufferState == "refilling" && PausePcmTrack.writes.sumOf { it.size } == 96_000 })
                assertFalse("The stream must still be open for every observed reserve", returned.get())
                assertFalse(completed.get())
                assertTrue(failures.isEmpty())
                assertEquals(listOf(300L, 500L, 700L), production)
                assertEquals(listOf("startup" to 315L, "refill" to 725L, "refill" to 1_135L), reserves)
                assertEquals(4_000L, output.synthesisFeedback.value.generatedAudioMs)
                assertEquals(null, output.synthesisFeedback.value.productionRatio)
                assertEquals(null, output.synthesisFeedback.value.predictedNextSynthesisMs)
            } finally {
                releaseSecond.countDown()
                releaseThird.countDown()
                releaseFinish.countDown()
            }
            awaitMain(finished)
            assertTrue(completed.get())
            assertTrue(failures.isEmpty())
        }
    }

    private fun assertBackpressuredStream(cancel: Boolean) {
        val secondEntered = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        val releaseThird = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val completed = AtomicBoolean()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val production = CopyOnWriteArrayList<Long>()
        val reserves = CopyOnWriteArrayList<Pair<String, Long>>()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                try {
                    ShadowSystemClock.advanceBy(50, TimeUnit.MILLISECONDS)
                    emit(VoicePcmChunk(FloatArray(144_000) { .1f }))
                    ShadowSystemClock.advanceBy(25, TimeUnit.MILLISECONDS)
                    secondEntered.countDown()
                    emit(VoicePcmChunk(FloatArray(24_000) { .1f }))
                    secondReturned.countDown()
                    check(releaseThird.await(5, TimeUnit.SECONDS))
                    ShadowSystemClock.advanceBy(80, TimeUnit.MILLISECONDS)
                    emit(VoicePcmChunk(FloatArray(24_000) { .1f }))
                    check(releaseFinish.await(5, TimeUnit.SECONDS))
                } finally {
                    retired.countDown()
                }
            }
            override fun resetCancellation() = Unit
            override fun cancel() {
                cancelled.set(true)
            }
            override fun close() = Unit
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) {
                production += synthesisMs
            }
            override fun reserveCompleted(phase: String, elapsedMs: Long, targetMs: Long, acceptedMs: Long, exitReason: String) {
                reserves += phase to targetMs
            }
        }
        BackpressurePcmTrack.allowWrites.set(false)
        BackpressurePcmTrack.blockedWrites.set(0)
        try {
            withStreamingOutput(false, VoicePauseDuration.NATURAL, engine, observer) { output ->
                try {
                    output.speakWithFailure("hello.", KokoroVoice.HEART, {
                        completed.set(true)
                        finished.countDown()
                    }, { failure, _ ->
                        failures += failure
                        finished.countDown()
                    })
                    assertTrue(awaitMainCondition { secondEntered.count == 0L && production.size == 2 && BackpressurePcmTrack.blockedWrites.get() >= 3 })
                    assertEquals("The second callback must be blocked by the actual bounded queue", 1L, secondReturned.count)
                    assertTrue(PausePcmTrack.writes.isEmpty())
                    if (cancel) {
                        output.stop("user")
                        awaitMain(retired)
                        assertEquals(0L, retired.count)
                        assertTrue(cancelled.get())
                        assertFalse(completed.get())
                        assertTrue(failures.isEmpty())
                    } else {
                        ShadowSystemClock.advanceBy(4_000, TimeUnit.MILLISECONDS)
                        BackpressurePcmTrack.allowWrites.set(true)
                        assertTrue(awaitMainCondition { secondReturned.count == 0L && output.synthesisFeedback.value.bufferState == "refilling" && PausePcmTrack.writes.sumOf { it.size } == 168_000 })
                        releaseThird.countDown()
                        assertTrue(awaitMainCondition { output.synthesisFeedback.value.bufferState == "refilling" && PausePcmTrack.writes.sumOf { it.size } == 192_000 })
                        assertEquals(1L, retired.count)
                        assertEquals(listOf(50L, 25L, 80L), production)
                        assertEquals("Four seconds blocked in the queue must not enlarge the refill reserve", "refill" to 284L, reserves.last())
                        assertEquals(155L, output.synthesisFeedback.value.synthesisMs)
                        assertFalse(completed.get())
                        assertTrue(failures.isEmpty())
                    }
                } finally {
                    releaseThird.countDown()
                    releaseFinish.countDown()
                    BackpressurePcmTrack.allowWrites.set(true)
                }
                if (!cancel) {
                    awaitMain(finished)
                    assertTrue(completed.get())
                    assertTrue(failures.isEmpty())
                }
            }
        } finally {
            BackpressurePcmTrack.allowWrites.set(true)
        }
    }

    private fun assertStreamingPause(requiresKokoroAssets: Boolean, pause: VoicePauseDuration) {
        val inputs = CopyOnWriteArrayList<String>()
        val observations = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val finished = CountDownLatch(1)
        val completed = AtomicBoolean()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                inputs += text
                val cueEnd = if (text == "hello.") 7_400L else 8_400L
                when (text) {
                    "hello." -> emit(VoicePcmChunk(FloatArray(2_400) { .1f }, wordCues = listOf(VoiceWordCue(0, 5, 0, cueEnd))))
                    "voice." -> {
                        emit(VoicePcmChunk(FloatArray(1_000), wordCues = listOf(VoiceWordCue(0, 5, 1_000, cueEnd))))
                        emit(VoicePcmChunk(FloatArray(2_400) { -.2f }))
                    }
                    "hush." -> {
                        emit(VoicePcmChunk(FloatArray(3_000) { .0002f }, wordCues = listOf(VoiceWordCue(0, 4, 0, 3_000))))
                        return
                    }
                    else -> error("Unexpected phrase")
                }
                emit(VoicePcmChunk(FloatArray(4_000)))
                emit(VoicePcmChunk(FloatArray(1_000)))
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun pcmReady(synthesisMs: Long, audioFrames: Int, sampleRate: Int, evidence: VoiceSpeechExecutionEvidence?) {
                assertEquals(VOICE_PCM_SAMPLE_RATE, sampleRate)
                observations += audioFrames
            }
        }
        withStreamingOutput(requiresKokoroAssets, pause, engine, observer) { output ->
            output.bindTimedSpokenRange { _, _, _, _ -> }
            val reply = requireNotNull(
                output.openReplyWithFailure(KokoroVoice.HEART, {
                    completed.set(true)
                    finished.countDown()
                }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
            )
            reply.enqueue("hello.")
            reply.enqueue("voice.")
            reply.enqueue("hush.")
            reply.finish()
            awaitMain(finished)
            assertTrue("Unexpected playback failure: ${failures.map { it.code }}", failures.isEmpty())
            assertTrue(completed.get())
            assertEquals(listOf("hello.", "voice.", "hush."), inputs)
            val quietFrames = pause.maxQuietMs?.let { VOICE_PCM_SAMPLE_RATE * it / 1_000 } ?: 5_000
            val leading = if (pause == VoicePauseDuration.NATURAL) FloatArray(1_000) else FloatArray(0)
            val final = if (pause == VoicePauseDuration.NATURAL) FloatArray(3_000) { .0002f } else FloatArray(0)
            val expected = FloatArray(2_400) { .1f } + FloatArray(quietFrames) + leading + FloatArray(2_400) { -.2f } + FloatArray(quietFrames) + final
            val presented = PausePcmTrack.writes.flatMap { it.asList() }.toFloatArray()
            assertArrayEquals(expected, presented, 0f)
            assertEquals(expected.size, observations.sum())
            assertEquals(
                if (pause == VoicePauseDuration.NATURAL) listOf(2_400, 4_000, 1_000, 1_000, 2_400, 4_000, 1_000, 3_000) else listOf(2_400, quietFrames, 2_400, quietFrames),
                observations
            )
            assertEquals(if (pause == VoicePauseDuration.NATURAL) 125L else 0L, output.synthesisFeedback.value.generatedAudioMs)
        }
    }

    private fun assertCaptionBeforeWordCompletes(requiresKokoroAssets: Boolean, pause: VoicePauseDuration) {
        val caption = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val timings = CopyOnWriteArrayList<Pair<IntRange, VoiceCaptionTiming>>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val returned = AtomicBoolean()
        val completed = AtomicBoolean()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                try {
                    emit(VoicePcmChunk(FloatArray(4_800) { .1f }, wordCues = listOf(VoiceWordCue(0, 5, 0, 7_200))))
                    check(release.await(5, TimeUnit.SECONDS))
                    emit(VoicePcmChunk(FloatArray(2_400) { .1f }))
                    check(releaseFinish.await(5, TimeUnit.SECONDS))
                } finally {
                    returned.set(true)
                }
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        withStreamingOutput(requiresKokoroAssets, pause, engine) { output ->
            output.bindTimedSpokenRange { _, _, range, timing ->
                timings += range to timing
                if (!range.isEmpty()) {
                    caption.countDown()
                } else {
                    cleared.countDown()
                }
            }
            try {
                output.speakWithFailure("hello.", KokoroVoice.HEART, {
                    completed.set(true)
                    finished.countDown()
                }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
                awaitMain(caption)
                assertEquals("The first audible word must have a caption before its future end arrives", 0L, caption.count)
                assertFalse(returned.get())
                assertTrue(failures.isEmpty())
                val first = timings.first()
                assertEquals(0..4, first.first)
                assertEquals(0L, first.second.wordStartFrame)
                assertEquals(if (pause == VoicePauseDuration.NATURAL) 7_200L else 4_800L, first.second.wordEndFrame)
                assertTrue(first.second.presentedFrame > 0)
                // The first caption may be delivered after the first 2400-frame queue
                // slice, before the worker writes the rest of this known 4800-frame chunk.
                assertTrue(PausePcmTrack.writes.sumOf { it.size } in 1..4_800)
                assertTrue(
                    "The first chunk must drain while the producer holds the tail",
                    awaitMainCondition {
                        output.synthesisFeedback.value.bufferState == "refilling" && PausePcmTrack.writes.sumOf { it.size } == 4_800
                    }
                )
                release.countDown()
                awaitMain(cleared, advancePlaybackClock = true)
                assertEquals(0L, cleared.count)
                assertFalse(returned.get())
                val final = timings.first { it.first.isEmpty() }.second
                assertEquals(7_200L, final.wordStartFrame)
                assertEquals(7_200L, final.wordEndFrame)
                assertEquals(7_200, PausePcmTrack.writes.sumOf { it.size })
                assertEquals(300L, output.synthesisFeedback.value.generatedAudioMs)
                assertTrue(timings.filter { !it.first.isEmpty() }.all { it.first == 0..4 && it.second.wordStartFrame == 0L })
                assertTrue(timings.dropWhile { !it.first.isEmpty() }.all { it.first.isEmpty() })
            } finally {
                release.countDown()
                releaseFinish.countDown()
            }
            awaitMain(finished)
            assertTrue(completed.get())
            assertTrue(failures.isEmpty())
        }
    }

    private fun assertTrimmedTailDuringRefill(requiresKokoroAssets: Boolean, pause: VoicePauseDuration, pauseForFocus: Boolean) {
        val quietFrames = VOICE_PCM_SAMPLE_RATE * requireNotNull(pause.maxQuietMs) / 1_000
        val retainedFrames = 2_400 + quietFrames
        val provenance = if (requiresKokoroAssets) VoiceWordCueProvenance.NATIVE_DURATION else VoiceWordCueProvenance.ESTIMATED
        val releaseTail = CountDownLatch(1)
        val tailAccepted = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val timings = CopyOnWriteArrayList<Pair<IntRange, VoiceCaptionTiming>>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val returned = AtomicBoolean()
        val completed = AtomicBoolean()
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                try {
                    emit(
                        VoicePcmChunk(
                            FloatArray(2_400) { .1f } + FloatArray(quietFrames),
                            wordCues = listOf(VoiceWordCue(0, 5, 0, retainedFrames + 1_000L, provenance))
                        )
                    )
                    check(releaseTail.await(10, TimeUnit.SECONDS))
                    emit(VoicePcmChunk(FloatArray(1_000)))
                    tailAccepted.countDown()
                    check(releaseFinish.await(10, TimeUnit.SECONDS))
                } finally {
                    returned.set(true)
                }
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        withStreamingOutput(requiresKokoroAssets, pause, engine) { output ->
            output.bindTimedSpokenRange { _, _, range, timing ->
                timings += range to timing
                if (range.isEmpty()) cleared.countDown()
            }
            try {
                output.speakWithFailure("hello.", KokoroVoice.HEART, {
                    completed.set(true)
                    finished.countDown()
                }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
                assertTrue(
                    "The actual output worker must drain all retained PCM and enter refill pause",
                    awaitMainCondition {
                        output.synthesisFeedback.value.bufferState == "refilling" &&
                            PausePcmTrack.writes.sumOf { it.size } == retainedFrames &&
                            timings.isNotEmpty()
                    }
                )
                assertEquals(1L, cleared.count)
                assertEquals(0..4, timings.last().first)
                assertFalse(returned.get())
                val context = ApplicationProvider.getApplicationContext<Context>()
                val focus = if (pauseForFocus) {
                    requireNotNull(shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java))).lastAudioFocusRequest.listener)
                } else {
                    null
                }
                focus?.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                releaseTail.countDown()
                awaitMain(tailAccepted)
                assertEquals(0L, tailAccepted.count)
                if (focus != null) {
                    val polls = PausePcmTrack.headReads.get()
                    assertTrue(awaitMainCondition { PausePcmTrack.headReads.get() >= polls + 3 })
                    assertEquals("Explicit focus pause must retain the current word", 1L, cleared.count)
                    assertEquals(0..4, timings.last().first)
                    focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                }
                awaitMain(cleared)
                assertEquals("Zero retained PCM must finalize the word while the producer remains open", 0L, cleared.count)
                assertFalse(returned.get())
                assertFalse(completed.get())
                assertTrue(failures.isEmpty())
                val final = timings.first { it.first.isEmpty() }.second
                assertEquals(retainedFrames.toLong(), final.wordStartFrame)
                assertEquals(retainedFrames.toLong(), final.wordEndFrame)
                assertEquals(retainedFrames.toLong(), final.presentedFrame)
                assertEquals(provenance, final.cueProvenance)
                assertFalse(final.playing)
                assertEquals(retainedFrames, PausePcmTrack.writes.sumOf { it.size })
                assertEquals(retainedFrames * 1_000L / VOICE_PCM_SAMPLE_RATE, output.synthesisFeedback.value.generatedAudioMs)
                val polls = PausePcmTrack.headReads.get()
                assertTrue(awaitMainCondition { PausePcmTrack.headReads.get() >= polls + 3 })
                assertEquals(1, timings.count { it.first.isEmpty() })
            } finally {
                releaseTail.countDown()
                releaseFinish.countDown()
            }
            awaitMain(finished)
            assertTrue(completed.get())
            assertTrue(failures.isEmpty())
        }
    }

    private fun withStreamingOutput(
        requiresKokoroAssets: Boolean,
        pause: VoicePauseDuration,
        engine: VoicePhraseEngine,
        observer: VoicePlaybackMeasurementObserver? = null,
        beforeCreate: () -> Unit = {},
        onCancelPending: () -> Unit = {},
        run: (KokoroVoiceOutput) -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java))).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val directory = Files.createTempDirectory("streaming-pause-test").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read", "hush").joinToString("\n") { "$it HH" }
        )
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } returns Unit
        every { models.modelDirectory() } returns directory
        val needsAssets = requiresKokoroAssets
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "streaming-pause-test-$requiresKokoroAssets"
            override val requiresKokoroAssets = needsAssets
            override val selectedBackend = if (needsAssets) VoiceSpeechBackend.GATEWAY_CUDA else VoiceSpeechBackend.PHONON_CPU
            override fun cancelPending() = onCancelPending()
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                beforeCreate()
                return engine
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { 1f }, measurements = observer)
        PausePcmTrack.writes.clear()
        PausePcmTrack.headReads.set(0)
        try {
            output.configureEngine(factory)
            output.configurePacing(pause, VoicePerformanceMode.FAST)
            run(output)
            if (!requiresKokoroAssets) verify { models wasNot Called }
        } finally {
            output.close()
            output.awaitClosed(1_000)
            ShadowAudioTrack.resetTest()
            PausePcmTrack.writes.clear()
            PausePcmTrack.headReads.set(0)
            directory.deleteRecursively()
        }
    }

    /** Captures accepted writes from the actual output queue; it does not model physical audio. */
    @Implements(AudioTrack::class)
    class PausePcmTrack : ShadowAudioTrack() {
        private var frames = 0

        @Implementation fun write(samples: FloatArray, offset: Int, size: Int, mode: Int): Int {
            require(offset >= 0 && size > 0 && offset <= samples.size - size)
            writes += samples.copyOfRange(offset, offset + size)
            frames += size
            return size
        }

        @Implementation override fun getPlaybackHeadPosition(): Int {
            headReads.incrementAndGet()
            return frames
        }

        @Implementation fun getBufferCapacityInFrames(): Int = 2_400

        @Implementation fun setBufferSizeInFrames(size: Int): Int = size

        @Implementation fun setStartThresholdInFrames(size: Int): Int = size

        @Implementation fun getUnderrunCount(): Int = 0

        @Implementation fun getTimestamp(timestamp: AudioTimestamp): Boolean = false

        companion object {
            val writes = CopyOnWriteArrayList<FloatArray>()
            val headReads = AtomicInteger()
        }
    }

    /** A full software queue can block its producer while the actual writer gets zero capacity. */
    @Implements(AudioTrack::class)
    class BackpressurePcmTrack : ShadowAudioTrack() {
        private var frames = 0

        @Implementation fun write(samples: FloatArray, offset: Int, size: Int, mode: Int): Int {
            require(offset >= 0 && size > 0 && offset <= samples.size - size)
            if (!allowWrites.get()) {
                blockedWrites.incrementAndGet()
                return 0
            }
            PausePcmTrack.writes += samples.copyOfRange(offset, offset + size)
            frames += size
            return size
        }

        @Implementation override fun getPlaybackHeadPosition(): Int = frames

        @Implementation fun getBufferCapacityInFrames(): Int = 2_400

        @Implementation fun setBufferSizeInFrames(size: Int): Int = size

        @Implementation fun setStartThresholdInFrames(size: Int): Int = size

        @Implementation fun getUnderrunCount(): Int = 0

        @Implementation fun getTimestamp(timestamp: AudioTimestamp): Boolean = false

        companion object {
            val allowWrites = AtomicBoolean(true)
            val blockedWrites = AtomicInteger()
        }
    }

    private fun awaitMainCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun awaitMain(latch: CountDownLatch, advancePlaybackClock: Boolean = false) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            // The playback worker measures its refill deadline on Android's clock. A paused looper keeps that clock still.
            if (advancePlaybackClock) ShadowSystemClock.advanceBy(10, TimeUnit.MILLISECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
    }
}
