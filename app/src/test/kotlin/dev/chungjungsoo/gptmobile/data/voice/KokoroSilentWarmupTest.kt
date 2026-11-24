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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

/** Actual synthesis/output workers with fake native inference; no device latency claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [KokoroPlaybackObservationTest.ImmediatePcmTrack::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KokoroSilentWarmupTest {
    @Test fun cpuWarmupIsSilentAndReusesOnlyTheExactVoiceAndRate() {
        withOutput { fixture ->
            val output = fixture.output
            output.prewarm(KokoroVoice.HEART)
            awaitSynthesis(output)
            output.prewarm(KokoroVoice.HEART)
            output.cancelPrewarm()
            output.prewarm(KokoroVoice.HEART)
            awaitSynthesis(output)
            assertEquals(listOf(KokoroVoice.HEART.speakerId to 1f), fixture.warmCalls)

            fixture.rate = 1.25f
            output.prewarm(KokoroVoice.HEART)
            awaitSynthesis(output)
            output.prewarm(KokoroVoice.ADAM)
            awaitSynthesis(output)
            assertEquals(
                listOf(KokoroVoice.HEART.speakerId to 1f, KokoroVoice.HEART.speakerId to 1.25f, KokoroVoice.ADAM.speakerId to 1.25f),
                fixture.warmCalls
            )
            assertEquals(1, fixture.creations.get())
            assertEquals(0, fixture.liveCalls.get())
            assertEquals(0, fixture.audioWrites.get())
            assertEquals(0, fixture.observedPcm.get())
            assertEquals("idle", output.synthesisFeedback.value.bufferState)
        }
    }

    @Test fun actualReplyCancelsAnActiveWarmupAndWaitsForItsNativeReturn() {
        withOutput { fixture ->
            val entered = CountDownLatch(1)
            val released = CountDownLatch(1)
            val returned = AtomicBoolean()
            fixture.warm = {
                entered.countDown()
                try {
                    check(released.await(5, TimeUnit.SECONDS))
                    check(!fixture.cancelled.get()) { "Synthetic native inference cancelled" }
                    true
                } finally {
                    returned.set(true)
                }
            }
            fixture.cancel = { released.countDown() }
            fixture.generate = {
                check(returned.get()) { "Live inference overlapped the old native owner" }
                check(!fixture.cancelled.get()) { "Live inference did not reset its cancellation" }
                audio()
            }
            fixture.output.prewarm(KokoroVoice.HEART)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val finished = CountDownLatch(1)
            try {
                fixture.speak(finished)
                awaitMain(finished)
                assertTrue(returned.get())
                assertTrue(fixture.failures.isEmpty())
                assertEquals(1, fixture.creations.get())
                assertEquals(1, fixture.liveCalls.get())
                assertEquals(1, fixture.completions.get())
                assertEquals(1, fixture.audioWrites.get())
            } finally {
                released.countDown()
            }
        }
    }

    @Test fun liveReplySkipsAQueuedWarmupBeforeNativeGateAdmission() {
        val gateOwner = Executors.newSingleThreadExecutor()
        val locked = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        try {
            gateOwner.submit {
                VoiceNativeEngineGate.exclusive {
                    locked.countDown()
                    check(releaseGate.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            withOutput { fixture ->
                fixture.output.prewarm(KokoroVoice.HEART)
                val finished = CountDownLatch(1)
                fixture.speak(finished)
                releaseGate.countDown()
                awaitMain(finished)
                assertTrue(fixture.failures.isEmpty())
                assertTrue(fixture.warmCalls.isEmpty())
                assertEquals(1, fixture.creations.get())
                assertEquals(1, fixture.completions.get())
            }
        } finally {
            releaseGate.countDown()
            gateOwner.shutdownNow()
        }
    }

    @Test fun completedWarmupDeadlineCannotCancelTheSubsequentLiveReply() {
        withOutput { fixture ->
            val entered = CountDownLatch(1)
            val releaseLive = CountDownLatch(1)
            fixture.generate = {
                entered.countDown()
                check(releaseLive.await(5, TimeUnit.SECONDS))
                check(!fixture.cancelled.get()) { "A stale warmup deadline cancelled live speech" }
                audio()
            }
            fixture.output.prewarm(KokoroVoice.HEART)
            awaitSynthesis(fixture.output)
            val finished = CountDownLatch(1)
            fixture.speak(finished)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VoiceSilentWarmup.CANCELLATION_DEADLINE_MS + 1))
                assertFalse(fixture.cancelled.get())
            } finally {
                releaseLive.countDown()
            }
            awaitMain(finished)
            assertTrue(fixture.failures.isEmpty())
            assertEquals(1, fixture.completions.get())
        }
    }

    @Test fun optionalWarmupFailureLeavesRealSpeechToItsNormalValidatedPath() {
        withOutput { fixture ->
            fixture.warm = { throw IllegalStateException("Synthetic warmup failure") }
            fixture.output.prewarm(KokoroVoice.HEART)
            awaitSynthesis(fixture.output)
            val finished = CountDownLatch(1)
            fixture.speak(finished)
            awaitMain(finished)
            assertTrue(fixture.failures.isEmpty())
            assertEquals(1, fixture.warmCalls.size)
            assertEquals(1, fixture.liveCalls.get())
            assertEquals(1, fixture.completions.get())
            assertEquals(1, fixture.audioWrites.get())
        }
    }

    @Test fun nonCpuSelectionsNeverReceiveASilentInference() {
        VoiceSpeechBackend.entries.filter { it != VoiceSpeechBackend.CPU }.forEach { backend ->
            withOutput(backend) { fixture ->
                fixture.output.prewarm(KokoroVoice.HEART)
                awaitSynthesis(fixture.output)
                assertEquals(backend.name, 1, fixture.creations.get())
                assertEquals(backend.name, 0, fixture.warmCalls.size)
                assertEquals(backend.name, 0, fixture.liveCalls.get())
                assertEquals(backend.name, 0, fixture.audioWrites.get())
            }
        }
    }

    private class Fixture(val context: Context, val directory: File, backend: VoiceSpeechBackend) {
        var rate = 1f
        val creations = AtomicInteger()
        val liveCalls = AtomicInteger()
        val completions = AtomicInteger()
        val audioWrites = AtomicInteger()
        val observedPcm = AtomicInteger()
        val cancelled = AtomicBoolean()
        val warmCalls = CopyOnWriteArrayList<Pair<Int, Float>>()
        val failures = CopyOnWriteArrayList<VoicePlaybackFailure>()
        var warm: () -> Boolean = { true }
        var cancel: () -> Unit = {}
        var generate: () -> VoicePhraseAudio = { audio() }
        val models = mockk<LocalVoiceModelStore>().also {
            every { it.isInstalled() } returns true
            every { it.acquireUse() } returns true
            every { it.releaseUse() } returns Unit
            every { it.modelDirectory() } returns directory
        }
        private val factory = object : VoicePhraseEngineFactory {
            override val identity = "silent-warmup-fixture-${backend.name}"
            override val selectedBackend = backend
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                creations.incrementAndGet()
                val native = object : VoicePhraseEngine {
                    override fun warmFirstInference(speaker: Int, speed: Float): Boolean {
                        warmCalls += speaker to speed
                        return warm()
                    }
                    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                        check(!cancelled.get())
                        liveCalls.incrementAndGet()
                        return this@Fixture.generate()
                    }
                    override fun resetCancellation() {
                        cancelled.set(false)
                    }
                    override fun cancel() {
                        cancelled.set(true)
                        this@Fixture.cancel()
                    }
                    override fun close() = Unit
                }
                return VoiceFailoverPhraseEngine(native, false) { error("Silent preparation cannot invoke accelerator fallback") }
            }
        }
        private val observer = object : VoicePlaybackMeasurementObserver {
            override fun firstAudioTrackWrite() {
                audioWrites.incrementAndGet()
            }
            override fun pcmQueued() {
                observedPcm.incrementAndGet()
            }
        }
        val output = KokoroVoiceOutput(context, models, { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build() }, { rate }, measurements = observer).also {
            it.configureEngine(factory)
            it.configureFixedCaptionOffset(0)
        }

        fun speak(finished: CountDownLatch) {
            output.speakProjectedWithFailure("hello.", KokoroVoice.HEART, {
                completions.incrementAndGet()
                finished.countDown()
            }, { error, _ ->
                failures += error
                finished.countDown()
            })
        }
    }

    private fun withOutput(backend: VoiceSpeechBackend = VoiceSpeechBackend.CPU, test: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java))).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val directory = Files.createTempDirectory("silent-warmup-test").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" }
        )
        val fixture = Fixture(context, directory, backend)
        try {
            test(fixture)
        } finally {
            fixture.output.close()
            assertTrue(fixture.output.awaitClosed(1_000))
            ShadowAudioTrack.resetTest()
            directory.deleteRecursively()
        }
    }

    /** Barrier on the real synthesis worker, so assertions include warmup's finally block. */
    private fun awaitSynthesis(output: KokoroVoiceOutput) {
        val field = KokoroVoiceOutput::class.java.getDeclaredField("synthesis").apply { isAccessible = true }
        (field.get(output) as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun awaitMain(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        assertEquals("The actual output workers must finish", 0L, latch.count)
    }

    companion object {
        private fun audio() = VoicePhraseAudio(FloatArray(1_800) { .01f }, longArrayOf(1, 1, 1), intArrayOf(1, 2))
    }
}
