package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceFailoverRecoveryOutputTest {
    @Test fun nextUtteranceRebuildsTheModelAfterCpuPreparationFails() = nextUtterance(cancelReplacement = false)

    @Test fun nextUtteranceRebuildsTheModelAfterCpuReplacementIsCancelled() = nextUtterance(cancelReplacement = true)

    private fun nextUtterance(cancelReplacement: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java)))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val directory = Files.createTempDirectory("voice-recovery-output").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read")
                .joinToString("\n") { "$it HH" }
        )
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } just Runs
        every { models.modelDirectory() } returns directory
        val replacementEntered = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val rejectedReplacement = Engine()
        val nextGenerated = CountDownLatch(1)
        val nextCpu = Engine { nextGenerated.countDown() }
        val gpu = Engine(reject = true)
        val retired = VoiceFailoverPhraseEngine(gpu, true) {
            replacementEntered.countDown()
            if (!cancelReplacement) error("CPU preparation failed")
            check(releaseReplacement.await(5, TimeUnit.SECONDS))
            rejectedReplacement
        }
        val loads = AtomicInteger()
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "voice-fallback-recovery-fixture"
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine =
                if (loads.incrementAndGet() == 1) retired else VoiceFailoverPhraseEngine(nextCpu, false) { error("Unexpected retry") }
        }
        val output = KokoroVoiceOutput(
            context,
            models,
            { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() },
            { 1f }
        )
        val firstFailure = CountDownLatch(1)
        try {
            output.configureEngine(factory)
            output.speakWithFailure("Hello.", KokoroVoice.HEART, {}, { _, _ -> firstFailure.countDown() })
            assertTrue("First phrase did not reach accelerator recovery", replacementEntered.await(5, TimeUnit.SECONDS))
            if (cancelReplacement) {
                output.stop("user_interrupt")
                releaseReplacement.countDown()
                assertTrue("Cancelled replacement was not closed", rejectedReplacement.closed.await(5, TimeUnit.SECONDS))
                assertTrue(rejectedReplacement.phrases.isEmpty())
            } else {
                awaitMain(firstFailure)
            }
            assertFalse(retired.canReuse)

            // Exercise the real output's model-key cache and factory decision for a new reply.
            output.speakWithFailure("Read.", KokoroVoice.HEART, {}, { _, _ -> })
            assertTrue("Next utterance reused the closed accelerator", nextGenerated.await(5, TimeUnit.SECONDS))
            assertEquals(2, loads.get())
            assertEquals(1, gpu.phrases.size)
            assertTrue(gpu.phrases.single().contains("hello", ignoreCase = true))
            assertEquals(1, nextCpu.phrases.size)
            assertTrue(nextCpu.phrases.single().contains("read", ignoreCase = true))
            assertFalse(nextCpu.phrases.single().contains("hello", ignoreCase = true))
            assertFalse(output.synthesisFeedback.value.warmAtRequest)
            assertFalse(output.synthesisFeedback.value.engineReused)
        } finally {
            releaseReplacement.countDown()
            output.close()
            directory.deleteRecursively()
        }
    }

    private fun awaitMain(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        assertEquals("The first playback did not report its failure", 0L, latch.count)
    }

    private class Engine(private val reject: Boolean = false, private val generated: () -> Unit = {}) : VoicePhraseEngine {
        val phrases = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
            check(closed.count != 0L) { "Kokoro is closed" }
            phrases += text
            if (reject) error("Graph rejected")
            generated()
            // The regression verifies the synthesis dispatch and cache, without real speaker I/O.
            throw CancellationException("Fixture phrase observed")
        }
        override fun resetCancellation() {
            check(closed.count != 0L) { "Kokoro is closed" }
        }
        override fun cancel() = Unit
        override fun close() {
            closed.countDown()
        }
    }
}
