package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertSame
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
class KokoroConversationPrivacyTest {
    @Test fun synthesisWorkerUsesThePolicyCapturedBeforeModelPreparation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java)))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val directory = Files.createTempDirectory("voice-conversation-policy").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText(
            listOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read").joinToString("\n") { "$it HH" }
        )
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.releaseUse() } just Runs
        every { models.modelDirectory() } returns directory
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val generated = CountDownLatch(1)
        val observed = AtomicReference<ConversationPrivacyContext?>()
        val original = ConversationPrivacyContext(7, true)
        var current = original
        val factory = object : VoicePhraseEngineFactory {
            override val identity = "conversation-privacy-fixture"
            override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine {
                preparing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return object : VoicePhraseEngine {
                    override fun generate(text: String, speaker: Int, speed: Float): VoicePhraseAudio {
                        observed.set(ConversationPrivacyContext.capture())
                        generated.countDown()
                        throw CancellationException("Fixture observed synthesis without speaker output")
                    }
                    override fun resetCancellation() = Unit
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
        }
        val output = KokoroVoiceOutput(
            context,
            models,
            { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() },
            { 1f },
            privacy = { current }
        )
        try {
            output.configureEngine(factory)
            output.speakWithFailure("Hello voice.", KokoroVoice.HEART, {}, { _, _ -> })
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            current = ConversationPrivacyContext(8, false)
            release.countDown()
            assertTrue(generated.await(5, TimeUnit.SECONDS))
            assertSame(original, observed.get())
        } finally {
            release.countDown()
            output.close()
            directory.deleteRecursively()
        }
    }
}
