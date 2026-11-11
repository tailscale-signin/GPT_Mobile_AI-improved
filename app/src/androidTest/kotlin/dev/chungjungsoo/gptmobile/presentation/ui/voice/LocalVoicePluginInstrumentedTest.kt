package dev.chungjungsoo.gptmobile.presentation.ui.voice

import ai.moonshine.voice.JNI
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginAudio
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionInput
import dev.chungjungsoo.gptmobile.data.voice.createKokoroVoiceOutput
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Packaged JNI/UI qualification; does not claim microphone or speech-model inference execution. */
class LocalVoicePluginInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun packagedNativeSpeechRuntimeMatchesItsJavaApi() {
        JNI.ensureLibraryLoaded()
        assertEquals(JNI.MOONSHINE_HEADER_VERSION, JNI.moonshineGetVersion())
    }

    @Test fun kokoroDelegateFallsBackToRealAndroidTextToSpeechWhenModelIsMissing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        val initialized = AtomicReference(false)
        val fallbackDone = CountDownLatch(1)
        val fallbackText = AtomicReference("")
        val fallbackError = AtomicReference<String?>(null)
        val completion = AtomicReference<(() -> Unit)?>(null)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        var focusRequested = false
        val tts = TextToSpeech(context) { status ->
            initialized.set(status == TextToSpeech.SUCCESS)
            ready.countDown()
        }
        try {
            assumeTrue("Android TextToSpeech initialization timed out", ready.await(10, TimeUnit.SECONDS))
            assumeTrue("The Android device has no available English TTS voice", initialized.get())
            assumeTrue("The Android device cannot synthesize US English", tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE)
            tts.language = Locale.US
            focusRequested = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            assumeTrue("The device did not grant audio focus for the playback check", focusRequested)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    completion.get()?.invoke()
                    fallbackDone.countDown()
                }
                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) {
                    fallbackError.set("Android TTS playback failed")
                    fallbackDone.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    fallbackError.set("Android TTS playback failed: $errorCode")
                    fallbackDone.countDown()
                }
            })

            val system = object : VoiceAudio {
                override fun listen(listener: VoiceAudio.Listener) = Unit
                override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = error("Unexpected system speech route")
                override fun stop() = Unit
                override fun close() = Unit
            }
            val recognition = object : VoiceRecognitionInput {
                override var languageTag: String = "en-US"
                override fun listen(listener: VoiceAudio.Listener) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            val models = KokoroModelPack.store(File(context.cacheDir, "kokoro-output-fallback-check"))
            assumeTrue("The fallback check requires a model-free installation", !models.isInstalled())
            val kokoro: KokoroOutput = createKokoroVoiceOutput(context, models, { attributes }, { 1f })
            val plugin = VoicePluginAudio(
                system,
                recognition,
                kokoro,
                offlineSpeech = { text, done, error ->
                    fallbackText.set(text)
                    completion.set(done)
                    val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
                    if (result == TextToSpeech.ERROR) {
                        error("Android TTS rejected the fallback utterance")
                        fallbackDone.countDown()
                    }
                },
                releaseSystemRecognition = {}
            )
            plugin.kokoroVoice = KokoroVoice.HEART
            val spoken = "Kokoro fallback playback check."
            plugin.speak(spoken, { fallbackDone.countDown() }, { fallbackError.set(it); fallbackDone.countDown() })
            assertTrue("The selected Kokoro delegate never completed its Android fallback", fallbackDone.await(30, TimeUnit.SECONDS))
            assertEquals(spoken, fallbackText.get())
            assertEquals(null, fallbackError.get())
            plugin.close()
        } finally {
            if (focusRequested) audioManager.abandonAudioFocusRequest(focusRequest)
            tts.stop()
            tts.shutdown()
        }
    }

    @Test fun installedKokoroModelPlaysAudioThroughTheAndroidOutputBackend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models: LocalVoiceModelStore = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"))
        assumeTrue("Install the Kokoro model on the Android 16 device to run native playback qualification", models.isInstalled())
        val complete = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val output = createKokoroVoiceOutput(context, models, { attributes }, { 1f })
        try {
            output.speak("Kokoro real device playback check.", KokoroVoice.HEART, { complete.countDown() }, { message, _ -> failure.set(message); complete.countDown() })
            assertTrue("Kokoro delegate did not produce or report real Android playback", complete.await(45, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
        } finally {
            output.close()
        }
    }

    @Test fun modelInstallRequiresExplicitAction() {
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                LocalVoiceSetupCard(LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED), { downloads++ }, {}, {})
            }
        }
        compose.onNodeWithTag("local_voice_setup").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, downloads) }
        compose.onNodeWithTag("download_local_voice").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test fun downloadCancellationAndModelRemovalHaveSeparateActions() {
        val state = mutableStateOf(LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING))
        var cancelled = 0
        var removed = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalVoiceSetupCard(state.value, {}, { cancelled++ }, { removed++ }, showRemove = true)
                }
            }
        }
        compose.onNodeWithText("Cancel download").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(0, removed)
            state.value = LocalVoiceModelState(LocalVoiceModelStatus.READY)
        }
        compose.onNodeWithText("Installed · Recognition works offline").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("download_local_voice").assertCountEquals(0)
        compose.onNodeWithText("Remove speech model").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }
}
