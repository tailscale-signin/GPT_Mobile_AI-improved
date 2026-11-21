package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.media.AudioAttributes
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoiceReplyPlayback
import dev.chungjungsoo.gptmobile.data.voice.createKokoroVoiceOutput
import dev.melo.kokoro.TimedKokoro
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Playback qualification for a device with the verified Kokoro pack already installed. */
class KokoroInstalledPlaybackInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun installedModelReturnsWordSpansWithThePcmAtEachSpeed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        assumeTrue("Install the Kokoro model to run native timing qualification", store.isInstalled())
        check(store.acquireUse())
        try {
            val directory = store.modelDirectory()
            TimedKokoro(
                File(directory, KokoroModelPack.MODEL).path,
                File(directory, KokoroModelPack.VOICES).path,
                File(directory, KokoroModelPack.TOKENS).path,
                File(directory, KokoroModelPack.US_LEXICON).path,
                2
            ).use { model ->
                val normal = model.generate("hello world.", KokoroVoice.HEART.speakerId, 1f)
                val fast = model.generate("hello world.", KokoroVoice.HEART.speakerId, 1.25f)
                for (audio in listOf(normal, fast)) {
                    assertArrayEquals(intArrayOf(1, 6, 7, 13), audio.wordTokens)
                    assertEquals(15, audio.durations.size)
                    assertTrue(audio.durations.all { it > 0 })
                    assertEquals(audio.samples.size.toLong(), audio.durations.sum() * 600)
                    assertTrue(audio.samples.all(Float::isFinite) && audio.samples.any { it != 0f })
                }
                assertTrue(fast.samples.size < normal.samples.size)
            }
        } finally {
            store.releaseUse()
        }
    }

    @Test fun installedKokoroStreamsAnUnknownWordAfterPlaybackStartsAndHighlightsTheNextWord() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        assumeTrue("Install the Kokoro model to run native playback qualification", models.isInstalled())
        val complete = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val output = createKokoroVoiceOutput(context, models, { attributes }, { 1f })
        val followUpRanges = mutableListOf<IntRange>()
        try {
            instrumentation.runOnMainSync {
                lateinit var reply: VoiceReplyPlayback
                output.bindPlaybackStarted {
                    reply.enqueue("Rughepagr here.")
                    reply.finish()
                }
                output.bindSpokenRange { segment, _, range ->
                    if (segment == 1 && !range.isEmpty()) followUpRanges += range
                }
                reply = requireNotNull(
                    output.openReply(KokoroVoice.HEART, complete::countDown) { message, _ ->
                        failure.set(message)
                        complete.countDown()
                    }
                )
                reply.enqueue("Hello.")
            }
            assertTrue("Kokoro delegate did not produce or report real Android playback", complete.await(45, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertEquals(listOf(0..8, 10..13), followUpRanges)
        } finally {
            instrumentation.runOnMainSync { output.close() }
        }
    }
}
