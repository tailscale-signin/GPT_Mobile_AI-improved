package dev.chungjungsoo.gptmobile.data.voice

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.time.Duration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidVoiceAudioTest {
    @Test fun completedSpeechRetiresTimeoutAndDuplicateCallbacks() {
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val failures = mutableListOf<String>()
        var completions = 0
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication()) { _, listener ->
            initialized = listener
            engine
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("Finished sentence.", { completions++ }, failures::add)
            requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            val listener = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
            verify(engine).setOnUtteranceProgressListener(listener.capture())
            val utterance = ArgumentCaptor.forClass(String::class.java)
            verify(engine).speak(eq<CharSequence>("Finished sentence."), eq(TextToSpeech.QUEUE_ADD), isNull(), utterance.capture())
            listener.value.onStart(utterance.value)
            main.idle()
            listener.value.onDone(utterance.value)
            main.idle()
            listener.value.onDone(utterance.value)
            main.idleFor(Duration.ofMinutes(10))
            assertEquals(1, completions)
            assertTrue(failures.isEmpty())
        } finally {
            audio.close()
        }
    }

    @Test fun failedInitializationReleasesEngineAndNextReplyCanSpeak() {
        val callbacks = mutableListOf<TextToSpeech.OnInitListener>()
        val engines = mutableListOf<TextToSpeech>()
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication()) { _, listener ->
            callbacks += listener
            mock(TextToSpeech::class.java).also { engines += it }
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("First reply", {}, failures::add)
            callbacks[0].onInit(TextToSpeech.ERROR)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, failures.size)
            verify(engines[0]).shutdown()

            audio.speak("Next reply", {}, failures::add)
            assertEquals(2, engines.size)
            // A late callback from the released engine cannot consume the new reply.
            callbacks[0].onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            callbacks[1].onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            verify(engines[1]).setLanguage(Locale.getDefault())
            verify(engines[1]).speak(eq<CharSequence>("Next reply"), eq(TextToSpeech.QUEUE_ADD), isNull(), anyString())
            assertEquals(1, failures.size)
        } finally {
            audio.close()
        }
    }

    @Test fun cancelledInitializationFailureDoesNotNotifyOrBlockTheNextReply() {
        val callbacks = mutableListOf<TextToSpeech.OnInitListener>()
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication()) { _, listener ->
            callbacks += listener
            mock(TextToSpeech::class.java)
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("Cancelled reply", {}, failures::add)
            audio.stop()
            callbacks[0].onInit(TextToSpeech.ERROR)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(failures.isEmpty())
            audio.speak("Fresh reply", {}, failures::add)
            assertEquals(2, callbacks.size)
        } finally {
            audio.close()
        }
    }

    @Test fun engineConstructionFailureIsReportedAndCanBeRetried() {
        var attempts = 0
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication()) { _, _ ->
            attempts++
            throw IllegalStateException("Speech service unavailable")
        }
        try {
            repeat(2) { audio.speak("Reply", {}, failures::add) }
            assertEquals(2, attempts)
            assertEquals(2, failures.size)
        } finally {
            audio.close()
        }
    }
}
