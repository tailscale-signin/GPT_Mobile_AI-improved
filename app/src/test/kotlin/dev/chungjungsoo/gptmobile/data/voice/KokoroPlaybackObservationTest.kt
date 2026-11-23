package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual output/executor teardown with model preparation held; no AudioTrack playback is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class KokoroPlaybackObservationTest {
    @Test fun cancelledPreparationMustNotInventObservedRouteOrClock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java)))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val directory = Files.createTempDirectory("playback-lifetime-probe").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" }
        )
        val released = AtomicInteger()
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } answers {
            released.incrementAndGet()
            Unit
        }
        every { models.modelDirectory() } returns directory
        val preparing = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val generations = AtomicInteger()
        val completions = AtomicInteger()
        val failures = AtomicInteger()
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "bounded-playback-lifetime-probe"
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                preparing.countDown()
                check(releasePreparation.await(5, TimeUnit.SECONDS))
                return object : VoicePhraseEngine {
                    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                        generations.incrementAndGet()
                        error("A cancelled owner must not reach generation")
                    }
                    override fun resetCancellation() = Unit
                    override fun cancel() = Unit
                    override fun close() {
                        retired.countDown()
                    }
                }
            }
        }
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, null)
        val output = KokoroVoiceOutput(
            context,
            models,
            { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() },
            { 1f },
            measurements = measurement
        )
        try {
            output.configureEngine(factory)
            output.speakWithFailure("Hello voice.", KokoroVoice.HEART, { completions.incrementAndGet() }, { _, _ -> failures.incrementAndGet() })
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            measurement.cancelRequested()
            output.stop("speech_benchmark_cancelled")
            val frozen = measurement.result(VoiceSpeechPlaybackState.CANCELLED, null)
            output.close()
            assertFalse("The actual worker still owns preparation", output.awaitClosed(1))
            assertEquals(0, released.get())
            releasePreparation.countDown()
            assertTrue("Model retirement is performed behind its worker", retired.await(5, TimeUnit.SECONDS))
            assertTrue(output.awaitClosed(1_000))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, released.get())
            assertEquals(0, generations.get())
            assertEquals(0, completions.get())
            assertEquals(0, failures.get())
            assertEquals(frozen, measurement.result(VoiceSpeechPlaybackState.CANCELLED, null))
            assertNull(frozen.firstPcmMs)
            assertNull(frozen.firstQueuedPcmMs)
            assertNull(frozen.firstPresentedAudioMs)
            assertEquals("No route or clock was observed before preparation retired", listOf<String?>(null, null), listOf(frozen.route, frozen.clockBasis))
            assertEquals(0, frozen.chunks)
            assertTrue(frozen.cancellationMs != null)
        } finally {
            releasePreparation.countDown()
            output.close()
            output.awaitClosed(1_000)
            directory.deleteRecursively()
        }
    }
}
