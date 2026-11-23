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

/** Actual output workers with deterministic AudioTrack presentation; no device speed claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [KokoroPlaybackObservationTest.ImmediatePcmTrack::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KokoroStreamingOutputTest {
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
                release.countDown()
                awaitMain(cleared)
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
            override fun create(directory: File, lexicon: String, threads: Int) = engine
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

    private fun awaitMainCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun awaitMain(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
    }
}
