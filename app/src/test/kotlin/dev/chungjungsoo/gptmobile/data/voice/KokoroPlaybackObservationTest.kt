package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioTrack

/** Actual output workers and ownership; hardware audio is either unstarted or an explicit deterministic boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class KokoroPlaybackObservationTest {
    @Test
    @Config(shadows = [ImmediatePcmTrack::class])
    fun residentEngineStartsFreshHardwareMeasurementsAndFixedCaptionsForEachReply() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))
        val preferences = context.getSharedPreferences("voice_synthesis_calibration_v2", Context.MODE_PRIVATE)
        preferences.edit().putInt("caption:speaker", 500).putInt("caption:bluetooth", -500).commit()
        val directory = Files.createTempDirectory("playback-resident-probe").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" }
        )
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } returns Unit
        every { models.modelDirectory() } returns directory
        val engines = AtomicInteger()
        val writes = AtomicInteger()
        val plays = AtomicInteger()
        val completions = AtomicInteger()
        val snapshots = AtomicInteger()
        val writeOwners = CopyOnWriteArrayList<Int>()
        val playOwners = CopyOnWriteArrayList<Int>()
        val reuseObservations = CopyOnWriteArrayList<Boolean>()
        val offsets = CopyOnWriteArrayList<Pair<String, Int>>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        val observer = object : VoicePlaybackMeasurementObserver {
            override fun forPlayback(): VoicePlaybackMeasurementObserver {
                val owner = snapshots.getAndIncrement()
                return object : VoicePlaybackMeasurementObserver {
                    override fun firstAudioTrackWrite() {
                        writes.incrementAndGet()
                        writeOwners += owner
                    }
                    override fun trackPlayRequested() {
                        plays.incrementAndGet()
                        playOwners += owner
                    }
                    override fun engineReady(reused: Boolean) {
                        reuseObservations += reused
                    }
                    override fun calibration(route: String, offsetMs: Int, routeEpoch: Long) {
                        offsets += route to offsetMs
                    }
                }
            }
        }
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "resident-measurement-probe"
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                assertEquals(1, threads)
                engines.incrementAndGet()
                return object : VoicePhraseEngine {
                    override fun generate(text: String, speaker: Int, speed: Float) = VoicePhraseAudio(FloatArray(1_800) { .01f }, longArrayOf(1, 1, 1), intArrayOf(1, 2))
                    override fun resetCancellation() = Unit
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { 1f }, measurements = observer)
        try {
            output.configureEngine(factory)
            output.configureThreads(1)
            output.configureFixedCaptionOffset(0)
            for ((index, type) in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).withIndex()) {
                shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                ShadowAudioTrack.setRoutedDevice(AudioDeviceInfoBuilder.newBuilder().setType(type).build())
                val finished = CountDownLatch(1)
                output.speakWithFailure("hello.", KokoroVoice.HEART, {
                    completions.incrementAndGet()
                    finished.countDown()
                }, { failure, _ ->
                    failures += failure
                    finished.countDown()
                })
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (finished.count > 0 && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    finished.await(10, TimeUnit.MILLISECONDS)
                }
                assertEquals("The actual playback must finish", 0, finished.count.toInt())
                assertTrue("Unexpected playback failure: ${failures.map { it.code }}", failures.isEmpty())
                assertEquals(index + 1, writes.get())
                assertEquals(index + 1, plays.get())
            }
            assertEquals(1, engines.get())
            assertEquals(2, completions.get())
            assertEquals(2, snapshots.get())
            assertEquals(listOf(0, 1), writeOwners)
            assertEquals(listOf(0, 1), playOwners)
            assertEquals(listOf(false, true), reuseObservations)
            assertEquals(listOf("speaker" to 0, "bluetooth" to 0), offsets)
            assertEquals(500, preferences.getInt("caption:speaker", 0))
            assertEquals(-500, preferences.getInt("caption:bluetooth", 0))
        } finally {
            output.close()
            assertTrue(output.awaitClosed(1_000))
            ShadowAudioTrack.resetTest()
            preferences.edit().remove("caption:speaker").remove("caption:bluetooth").commit()
            directory.deleteRecursively()
        }
    }

    /** Accepts PCM synchronously to exercise real queue/reply ownership, not physical sound or latency. */
    @Implements(AudioTrack::class)
    class ImmediatePcmTrack : ShadowAudioTrack() {
        private var frames = 0

        @Implementation fun write(samples: FloatArray, offset: Int, size: Int, mode: Int): Int {
            require(offset >= 0 && size > 0 && offset <= samples.size - size)
            frames += size
            return size
        }

        @Implementation override fun getPlaybackHeadPosition(): Int = frames

        @Implementation fun getBufferCapacityInFrames(): Int = 2_400

        @Implementation fun setBufferSizeInFrames(size: Int): Int = size

        @Implementation fun setStartThresholdInFrames(size: Int): Int = size

        @Implementation fun getUnderrunCount(): Int = 0

        @Implementation fun getTimestamp(timestamp: AudioTimestamp): Boolean = false
    }

    @Test fun explicitSingleThreadBenchmarkReachesTheEngineWithoutChangingSavedCalibration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java)))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val preferences = context.getSharedPreferences("voice_synthesis_calibration_v2", Context.MODE_PRIVATE)
        preferences.edit().putInt("caption:speaker", 500).putInt("caption:bluetooth", -500).commit()
        val directory = Files.createTempDirectory("playback-configuration-probe").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" }
        )
        val requested = AtomicInteger()
        val preparing = CountDownLatch(1)
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } returns Unit
        every { models.modelDirectory() } returns directory
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "playback-configuration-probe"
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                requested.set(threads)
                preparing.countDown()
                error("Stop this configuration probe before hardware playback")
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { 1f })
        try {
            output.configureEngine(factory)
            output.configureThreads(1)
            output.configureFixedCaptionOffset(0)
            output.speakWithFailure("Hello voice.", KokoroVoice.HEART, {}, { _, _ -> })
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            assertEquals(1, requested.get())
            output.close()
            assertTrue(output.awaitClosed(1_000))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(500, preferences.getInt("caption:speaker", 0))
            assertEquals(-500, preferences.getInt("caption:bluetooth", 0))
        } finally {
            output.close()
            output.awaitClosed(1_000)
            preferences.edit().remove("caption:speaker").remove("caption:bluetooth").commit()
            directory.deleteRecursively()
        }
    }

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
