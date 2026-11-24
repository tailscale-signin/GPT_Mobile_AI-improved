package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowAudioTrack

/** Actual benchmark and production-output owners, with explicit fake SDK and AudioTrack boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [VoiceSpeechEnginePlaybackBenchmarkTest.CountingPcmTrack::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceSpeechEnginePlaybackBenchmarkTest {
    @Test fun allSdkSelectionsReachTheirFactoryAndTheControlledProductionInputWithoutNativeAssets() {
        val context = context()
        VoiceSpeechBenchmarkEngine.entries.filter { it != VoiceSpeechBenchmarkEngine.NATIVE_KOKORO }.forEach { selected ->
            selected.supportedBackends.forEach { backend ->
                val models = mockk<LocalVoiceModelStore>()
                val inputs = CopyOnWriteArrayList<String>()
                val opens = AtomicInteger()
                val closes = AtomicInteger()
                val configuration = configuration(selected, backend)
                val benchmark = VoiceSpeechPlaybackBenchmark(context, models, configuration, { 0 }) { requested ->
                    assertEquals(backend, requested)
                    factory(backend) {
                        opens.incrementAndGet()
                        object : TestEngine() {
                            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                                inputs += text
                                error("Selected SDK boundary reached")
                            }
                            override fun close() {
                                closes.incrementAndGet()
                            }
                        }
                    }
                }
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val operation = worker.submit<List<VoiceSpeechPlaybackResult>> { runBlocking { benchmark.measureSeries(backend) } }
                    val result = await(operation).single()
                    assertEquals(1, opens.get())
                    assertEquals(1, closes.get())
                    assertTrue(inputs.isNotEmpty())
                    assertTrue(VoiceSpeechBenchmarkFixture.LONG.text.startsWith(inputs.first().trim()))
                    assertEquals(VoiceSpeechPlaybackProtocol.plans(configuration).single().manifest.hash, result.fixtureHash)
                    assertEquals(VoiceSpeechPlaybackState.FAILED, result.state)
                    assertEquals("RETIRED", result.cleanupState)
                    assertEquals(requestedSpeechDelegate(backend), result.requestedDelegate)
                    assertNull(result.effectiveProvider)
                    assertFalse(result.executionVerified)
                    verify { models wasNot Called }
                } finally {
                    benchmark.cancel()
                    worker.shutdownNow()
                }
            }
        }
    }

    @Test fun incrementalPcmReachesActualAudioTrackWhileStopRetainsTheProducerAndPrivateContext() {
        val context = context()
        CountingPcmTrack.written.set(0)
        val models = mockk<LocalVoiceModelStore>()
        val privateContext = ConversationPrivacyContext(63, true)
        val observedPrivacy = AtomicBoolean()
        val entered = CountDownLatch(1)
        val cancellation = CountDownLatch(1)
        val releaseProducer = CountDownLatch(1)
        val producerReturned = AtomicBoolean()
        val closed = AtomicBoolean()
        val backend = VoiceSpeechBackend.PHONON_CPU
        val selected = factory(backend) {
            object : TestEngine() {
                override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                    observedPrivacy.set(ConversationPrivacyContext.capture() == privateContext)
                    try {
                        assertTrue(emit(VoicePcmChunk(FloatArray(4_800) { .1f })))
                        entered.countDown()
                        check(releaseProducer.await(5, TimeUnit.SECONDS))
                    } finally {
                        producerReturned.set(true)
                    }
                }
                override fun cancel() = cancellation.countDown()
                override fun close() {
                    assertTrue(producerReturned.get())
                    closed.set(true)
                }
            }
        }
        val benchmark = ConversationPrivacyContext.withCaptured(privateContext) {
            VoiceSpeechPlaybackBenchmark(context, models, configuration(VoiceSpeechBenchmarkEngine.PHONON, backend), { 0 }) { selected }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<List<VoiceSpeechPlaybackResult>> { runBlocking { benchmark.measureSeries(backend) } }
            assertTrue(pumpUntil { entered.count == 0L && CountingPcmTrack.written.get() > 0 })
            assertTrue(observedPrivacy.get())
            assertFalse(producerReturned.get())
            benchmark.cancel()
            assertTrue(pumpUntil { cancellation.count == 0L })
            assertFalse(closed.get())
            assertFalse(operation.isDone)
            releaseProducer.countDown()
            val result = await(operation).single()
            assertEquals(VoiceSpeechPlaybackState.CANCELLED, result.state)
            assertEquals("RETIRED", result.cleanupState)
            assertEquals(false, result.streamedAudioValidated)
            assertNotNull(result.firstPcmMs)
            assertTrue(result.chunks > 0)
            assertNull(result.effectiveProvider)
            assertNull(result.effectiveThreads)
            assertEquals("NOT_EXPOSED_BY_SDK", result.observedProviderStatus)
            assertTrue(closed.get())
            verify { models wasNot Called }
        } finally {
            releaseProducer.countDown()
            benchmark.cancel()
            worker.shutdownNow()
            ShadowAudioTrack.resetTest()
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext<Context>().also {
        shadowOf(requireNotNull(it.getSystemService(AudioManager::class.java))).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    private fun configuration(engine: VoiceSpeechBenchmarkEngine, backend: VoiceSpeechBackend): VoiceSpeechBenchmarkConfiguration {
        val model = MediaPipeSpeechCatalog.model(checkNotNull(engine.mediaPipeEngine()))
        return VoiceSpeechBenchmarkConfiguration(
            VoiceSpeechBenchmarkMode.QUICK, listOf(backend), KokoroVoice.HEART, 2, 1f,
            model.sha256, MediaPipeSpeechCatalog.RUNTIME_ID, 149, 36, "test-only-device",
            playback = true, engine = engine, engineVoiceId = model.defaultVoice
        )
    }

    private fun factory(backend: VoiceSpeechBackend, create: () -> VoicePhraseEngine) = object : VoicePhraseEngineFactory {
        override val selectedBackend = backend
        override val requiresKokoroAssets = false
        override val identity = "sdk-benchmark-actual-output-${backend.name}"
        override fun create(directory: File, lexicon: String, threads: Int) = create()
    }

    private abstract class TestEngine : VoicePhraseEngine {
        override val supportsStreaming = true
        override val executionProvider = "mediapipe-test-delegate-requested"
        override fun resetCancellation() = Unit
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private fun <T> await(operation: Future<T>): T {
        assertTrue("Benchmark did not retire", pumpUntil { operation.isDone })
        return operation.get(1, TimeUnit.SECONDS)
    }

    private fun pumpUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    @Implements(AudioTrack::class)
    class CountingPcmTrack : ShadowAudioTrack() {
        private var frames = 0

        @Implementation fun write(samples: FloatArray, offset: Int, size: Int, mode: Int): Int {
            frames += size
            written.addAndGet(size)
            return size
        }

        @Implementation override fun getPlaybackHeadPosition(): Int = frames

        @Implementation fun getBufferCapacityInFrames(): Int = 2_400

        @Implementation fun setBufferSizeInFrames(size: Int): Int = size

        @Implementation fun setStartThresholdInFrames(size: Int): Int = size

        @Implementation fun getUnderrunCount(): Int = 0

        @Implementation fun getTimestamp(timestamp: AudioTimestamp): Boolean = false

        companion object {
            val written = AtomicInteger()
        }
    }
}
