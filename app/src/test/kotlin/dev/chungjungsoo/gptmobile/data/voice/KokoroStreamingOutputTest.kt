package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioTrack

/** Actual output workers with deterministic AudioTrack presentation; no device speed claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [KokoroPlaybackObservationTest.ImmediatePcmTrack::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KokoroStreamingOutputTest {
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

    private fun awaitMain(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
    }
}
