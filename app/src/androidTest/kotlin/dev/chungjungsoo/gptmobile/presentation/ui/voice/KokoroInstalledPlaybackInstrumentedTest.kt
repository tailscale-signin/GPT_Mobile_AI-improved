package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.media.AudioAttributes
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.createKokoroVoiceOutput
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Playback qualification for a device with the verified Kokoro pack already installed. */
class KokoroInstalledPlaybackInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun installedKokoroModelPlaysAudioThroughTheAndroidOutputBackend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        assumeTrue("Install the Kokoro model to run native playback qualification", models.isInstalled())
        val complete = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val output = createKokoroVoiceOutput(context, models, { attributes }, { 1f })
        try {
            output.speak("Kokoro real device playback check.", KokoroVoice.HEART, { complete.countDown() }, { message, _ ->
                failure.set(message)
                complete.countDown()
            })
            assertTrue("Kokoro delegate did not produce or report real Android playback", complete.await(45, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
        } finally {
            output.close()
        }
    }
}
