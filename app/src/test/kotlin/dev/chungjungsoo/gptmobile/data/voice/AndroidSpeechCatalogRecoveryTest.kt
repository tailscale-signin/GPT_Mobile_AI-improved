package dev.chungjungsoo.gptmobile.data.voice

import android.os.Looper
import android.speech.tts.TextToSpeech
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidSpeechCatalogRecoveryTest {
    @Test fun synchronousInitializationCallbackIsHandledAfterTheEngineIsPublished() {
        val engine = mock(TextToSpeech::class.java)
        `when`(engine.voices).thenReturn(emptySet())
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            callback.onInit(TextToSpeech.SUCCESS)
            engine
        }
        try {
            audio.prepareVoices()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SpeechVoiceCatalogState.EMPTY, audio.voiceCatalogState.value)
            verify(engine, times(1)).voices
            verify(engine, never()).shutdown()
        } finally {
            audio.close()
        }
        verify(engine, times(1)).shutdown()
    }

    @Test fun catalogOnlyInitializationTimeoutRetiresOnceAndExplicitRecheckCanRecover() {
        val callbacks = mutableListOf<TextToSpeech.OnInitListener>()
        val first = mock(TextToSpeech::class.java)
        val second = mock(TextToSpeech::class.java)
        `when`(second.voices).thenReturn(emptySet())
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            callbacks += callback
            if (callbacks.size == 1) first else second
        }
        val main = shadowOf(Looper.getMainLooper())
        try {
            audio.prepareVoices()
            assertEquals(SpeechVoiceCatalogState.CHECKING, audio.voiceCatalogState.value)
            main.idleFor(Duration.ofSeconds(11))
            assertEquals(SpeechVoiceCatalogState.UNAVAILABLE, audio.voiceCatalogState.value)
            assertEquals(1, callbacks.size)
            verify(first, never()).shutdown()
            verify(first, never()).stop()

            audio.prepareVoices()
            assertEquals(2, callbacks.size)
            callbacks[0].onInit(TextToSpeech.SUCCESS)
            main.idle()
            verify(first, times(1)).shutdown()
            assertEquals(SpeechVoiceCatalogState.CHECKING, audio.voiceCatalogState.value)
            verify(first, never()).voices
            callbacks[1].onInit(TextToSpeech.SUCCESS)
            main.idleFor(Duration.ofSeconds(11))
            assertEquals(SpeechVoiceCatalogState.EMPTY, audio.voiceCatalogState.value)
            verify(second, never()).shutdown()
        } finally {
            audio.close()
        }
        verify(first, times(1)).shutdown()
        verify(second, times(1)).shutdown()
    }

    @Test fun duplicateInitializationCallbacksCannotRepeatVoiceQueriesOrRetireAReadyEngine() {
        var callback: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        `when`(engine.voices).thenReturn(emptySet())
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, initialized ->
            callback = initialized
            engine
        }
        try {
            audio.prepareVoices()
            requireNotNull(callback).onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            requireNotNull(callback).onInit(TextToSpeech.SUCCESS)
            requireNotNull(callback).onInit(TextToSpeech.ERROR)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SpeechVoiceCatalogState.EMPTY, audio.voiceCatalogState.value)
            verify(engine, times(1)).voices
            verify(engine, never()).shutdown()
        } finally {
            audio.close()
        }
    }

    @Test fun nullVoiceQueryForgetsTheDisconnectedEngineAndExplicitRecheckInitializesAnotherGeneration() {
        val callbacks = mutableListOf<TextToSpeech.OnInitListener>()
        val first = mock(TextToSpeech::class.java)
        val second = mock(TextToSpeech::class.java)
        `when`(first.voices).thenReturn(null)
        `when`(second.voices).thenReturn(emptySet())
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            callbacks += callback
            if (callbacks.size == 1) first else second
        }
        audio.prepareVoices()
        callbacks[0].onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SpeechVoiceCatalogState.UNAVAILABLE, audio.voiceCatalogState.value)
        verify(first, never()).shutdown()
        audio.prepareVoices()
        assertEquals(2, callbacks.size)
        callbacks[1].onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SpeechVoiceCatalogState.EMPTY, audio.voiceCatalogState.value)
        callbacks[0].onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SpeechVoiceCatalogState.EMPTY, audio.voiceCatalogState.value)
        verify(second, never()).shutdown()
        audio.close()
        audio.close()
        verify(first, never()).shutdown()
        verify(second, times(1)).shutdown()
        verify(first, never()).stop()
        verify(second, never()).stop()
    }

    @Test fun aValidEmptyVoiceCatalogRemainsAnInitializedEngine() {
        var callback: TextToSpeech.OnInitListener? = null
        var constructions = 0
        val engine = mock(TextToSpeech::class.java)
        `when`(engine.voices).thenReturn(emptySet())
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, initialized ->
            constructions++
            callback = initialized
            engine
        }
        try {
            audio.prepareVoices()
            requireNotNull(callback).onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SpeechVoiceCatalogState.EMPTY, audio.voiceCatalogState.value)
            assertTrue(audio.voiceChoices.value.isEmpty())
            audio.prepareVoices()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, constructions)
            verify(engine, never()).shutdown()
        } finally {
            audio.close()
        }
    }

    @Test fun failedVoiceBinderQueryCannotDispatchSpeechOrBeMisreportedAsMissingVoiceData() {
        var callback: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        `when`(engine.voices).thenReturn(null)
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, initialized ->
            callback = initialized
            engine
        }.apply { allowOnlineSpeech = true }
        try {
            audio.speak("Keep this answer in captions", {}, failures::add)
            requireNotNull(callback).onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, failures.size)
            assertTrue(failures.single().contains("disconnected"))
            assertEquals(SpeechVoiceCatalogState.UNAVAILABLE, audio.voiceCatalogState.value)
            verify(engine, never()).speak(any(CharSequence::class.java), eq(TextToSpeech.QUEUE_ADD), isNull(), anyString())
            verify(engine, never()).shutdown()
        } finally {
            audio.close()
        }
        verify(engine, never()).shutdown()
    }

    @Test fun aThrownVoiceQueryHasTheSameBoundedRecoveryAsAnUnboundResult() {
        var callback: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        `when`(engine.voices).thenThrow(IllegalStateException("Binder unavailable"))
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, initialized ->
            callback = initialized
            engine
        }
        audio.prepareVoices()
        requireNotNull(callback).onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SpeechVoiceCatalogState.UNAVAILABLE, audio.voiceCatalogState.value)
        verify(engine, never()).shutdown()
        audio.close()
        verify(engine, never()).shutdown()
    }
}
