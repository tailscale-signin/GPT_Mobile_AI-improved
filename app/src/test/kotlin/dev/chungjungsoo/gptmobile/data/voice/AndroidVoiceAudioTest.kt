package dev.chungjungsoo.gptmobile.data.voice

import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.time.Duration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
    @Test fun diagnosticTimingUsesRealTtsCallbacksAndRejectsCancelledUtterances() {
        var time = 10L
        val trace = VoiceDiagnosticTrace({ time }, { 1000 + time }, { true }, {})
        val probe = trace.advance(1, reset = true)
        probe.responseText()
        time = 30
        probe.queued()
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, listener ->
            initialized = listener
            engine
        }
        audio.allowOnlineSpeech = true
        audio.bindDiagnostics(probe)
        try {
            audio.speak("Measured words.", {}, { error(it) })
            time = 130
            requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            val callbacks = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
            verify(engine).setOnUtteranceProgressListener(callbacks.capture())
            val utterance = ArgumentCaptor.forClass(String::class.java)
            verify(engine).speak(eq<CharSequence>("Measured words."), eq(TextToSpeech.QUEUE_ADD), isNull(), utterance.capture())
            time = 200
            callbacks.value.onBeginSynthesis(utterance.value, 24000, 2, 1)
            time = 400
            callbacks.value.onStart(utterance.value)
            assertEquals(100L, trace.incidentFields()["voice_warmup_ms"])
            assertEquals(170L, trace.incidentFields()["voice_queue_to_synthesis_ms"])
            assertEquals(200L, trace.incidentFields()["voice_synthesis_to_first_audio_ms"])
            assertEquals(390L, trace.incidentFields()["voice_response_to_first_audio_ms"])
            assertEquals("UTTERANCE_START", trace.incidentFields()["voice_first_audio_basis"])
            audio.stop()
            val stopped = trace.incidentFields()
            time = 900
            callbacks.value.onStart(utterance.value)
            callbacks.value.onBeginSynthesis(utterance.value, 24000, 2, 1)
            assertEquals(stopped, trace.incidentFields())
        } finally {
            audio.close()
            trace.close()
        }
    }

    @Test fun speechConfigurationRunsOnWorkerAndIsReusedUntilSettingsChange() {
        val work = java.util.ArrayDeque<Runnable>()
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val threads = mutableListOf<Thread>()
        org.mockito.Mockito.`when`(engine.setLanguage(org.mockito.ArgumentMatchers.any(Locale::class.java))).thenAnswer {
            threads += Thread.currentThread()
            TextToSpeech.LANG_AVAILABLE
        }
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { work.add(it) }) { _, callback ->
            initialized = callback
            engine
        }
        fun drain() {
            val worker = Thread { while (work.isNotEmpty()) work.removeFirst().run() }
            worker.start()
            worker.join(3000)
            assertTrue("speech worker must finish", !worker.isAlive)
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("First chunk", {}, { error(it) })
            requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(threads.isEmpty())
            drain()
            audio.speak("Second chunk", {}, { error(it) })
            drain()
            assertEquals(1, threads.size)
            assertTrue(threads.none { it == Looper.getMainLooper().thread })
            audio.speechRate = 1.5f
            audio.speak("Changed configuration", {}, { error(it) })
            drain()
            assertEquals(2, threads.size)
        } finally {
            audio.close()
            drain()
        }
    }

    @Test fun cancellingDuringConfigurationNeverStartsStaleAudio() {
        val work = java.util.ArrayDeque<Runnable>()
        val configured = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        org.mockito.Mockito.`when`(engine.setLanguage(org.mockito.ArgumentMatchers.any(Locale::class.java))).thenAnswer {
            configured.countDown()
            release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            TextToSpeech.LANG_AVAILABLE
        }
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { work.add(it) }) { _, callback ->
            initialized = callback
            engine
        }
        audio.allowOnlineSpeech = true
        audio.speak("Must not play", {}, failures::add)
        requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        val task = work.removeFirst()
        val worker = Thread { task.run() }
        worker.start()
        try {
            assertTrue(configured.await(3, java.util.concurrent.TimeUnit.SECONDS))
            audio.stop()
            release.countDown()
            worker.join(3000)
            assertTrue(!worker.isAlive)
            shadowOf(Looper.getMainLooper()).idle()
            verify(engine, org.mockito.Mockito.never()).speak(org.mockito.ArgumentMatchers.any(CharSequence::class.java), eq(TextToSpeech.QUEUE_ADD), isNull(), anyString())
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            audio.close()
            while (work.isNotEmpty()) work.removeFirst().run()
        }
    }

    @Test fun completedSpeechRetiresTimeoutAndDuplicateCallbacks() {
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val failures = mutableListOf<String>()
        var completions = 0
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, listener ->
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
            verify(engine, org.mockito.Mockito.never()).stop()
        } finally {
            audio.close()
        }
        verify(engine, org.mockito.Mockito.never()).stop()
    }

    @Test fun unrelatedUtteranceCallbacksCannotChangePlaybackOrLeakTheirIdentifier() {
        val context = RuntimeEnvironment.getApplication()
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val records = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var completions = 0
        val audio = AndroidVoiceAudio(context, java.util.concurrent.Executor { it.run() }, { _, callback ->
            initialized = callback
            engine
        }, AndroidRecognitionService(context), RecognitionStartGate { 0L }) { _, message, _ -> records += message }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("Current reply", { completions++ }, failures::add)
            requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            val listener = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
            verify(engine).setOnUtteranceProgressListener(listener.capture())
            listener.value.onStart("private-other-utterance")
            listener.value.onDone("private-other-utterance")
            @Suppress("DEPRECATION")
            listener.value.onError("private-other-utterance")
            main.idle()
            assertTrue(records.none { it.contains("private-other-utterance") })
            assertEquals(0, completions)
            assertTrue(failures.isEmpty())
            verify(engine, org.mockito.Mockito.never()).shutdown()
            val utterance = ArgumentCaptor.forClass(String::class.java)
            verify(engine).speak(eq<CharSequence>("Current reply"), eq(TextToSpeech.QUEUE_ADD), isNull(), utterance.capture())
            listener.value.onDone(utterance.value)
            main.idle()
            assertEquals(1, completions)
        } finally {
            audio.close()
        }
    }

    @Test fun completedCallbackCannotIdleLaterPlayback() {
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        var completions = 0
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            initialized = callback
            engine
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("First reply", { completions++ }, { error(it) })
            requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            val listener = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
            verify(engine).setOnUtteranceProgressListener(listener.capture())
            val utterance = ArgumentCaptor.forClass(String::class.java)
            verify(engine).speak(eq<CharSequence>("First reply"), eq(TextToSpeech.QUEUE_ADD), isNull(), utterance.capture())
            listener.value.onDone(utterance.value)
            main.idle()
            verify(engine, org.mockito.Mockito.never()).stop()

            audio.speak("Next reply", { completions++ }, { error(it) })
            listener.value.onDone(utterance.value)
            main.idle()
            assertEquals(1, completions)
            audio.stop()
            // The stale completion must not mark the current reply idle.
            verify(engine, org.mockito.Mockito.times(1)).stop()
        } finally {
            audio.close()
        }
    }

    @Test fun failedInitializationReleasesEngineAndNextReplyCanSpeak() {
        val callbacks = mutableListOf<TextToSpeech.OnInitListener>()
        val engines = mutableListOf<TextToSpeech>()
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, listener ->
            callbacks += listener
            mock(TextToSpeech::class.java).also { engines += it }
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("First reply", {}, failures::add)
            callbacks[0].onInit(TextToSpeech.ERROR)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, failures.size)
            // ERROR means the platform never bound this object. Calling shutdown here creates
            // a false "not bound to TTS engine" incident and releases no service.
            verify(engines[0], org.mockito.Mockito.never()).shutdown()

            audio.speak("Next reply", {}, failures::add)
            assertEquals(2, engines.size)
            // A duplicate callback from the failed engine cannot consume the new reply.
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
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, listener ->
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

    @Test fun engineConstructionFailureRetiresTimeoutAndAudioFocusBeforeRetry() {
        val context = RuntimeEnvironment.getApplication()
        val audioManager = shadowOf(requireNotNull(context.getSystemService(AudioManager::class.java)))
        val main = shadowOf(Looper.getMainLooper())
        var attempts = 0
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val failures = mutableListOf<String>()
        var completions = 0
        val audio = AndroidVoiceAudio(context, java.util.concurrent.Executor { it.run() }) { _, callback ->
            attempts++
            if (attempts == 1) throw IllegalStateException("Speech service unavailable")
            initialized = callback
            engine
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("Failed reply", { completions++ }, failures::add)
            assertEquals(1, attempts)
            assertEquals(1, failures.size)
            val failedRequest = requireNotNull(audioManager.lastAudioFocusRequest.audioFocusRequest)
            assertSame(failedRequest, audioManager.lastAbandonedAudioFocusRequest)
            // No user action should be needed to prevent a second timeout error.
            main.idleFor(Duration.ofSeconds(11))
            assertEquals(1, failures.size)

            audio.speak("Recovered reply", { completions++ }, failures::add)
            assertEquals(2, attempts)
            val recoveredRequest = requireNotNull(audioManager.lastAudioFocusRequest.audioFocusRequest)
            assertNotSame(failedRequest, recoveredRequest)
            assertSame(failedRequest, audioManager.lastAbandonedAudioFocusRequest)
            requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
            main.idle()
            val listener = ArgumentCaptor.forClass(UtteranceProgressListener::class.java)
            verify(engine).setOnUtteranceProgressListener(listener.capture())
            val utterance = ArgumentCaptor.forClass(String::class.java)
            verify(engine).speak(eq<CharSequence>("Recovered reply"), eq(TextToSpeech.QUEUE_ADD), isNull(), utterance.capture())
            listener.value.onStart(utterance.value)
            main.idle()
            listener.value.onDone(utterance.value)
            main.idleFor(Duration.ofSeconds(11))
            assertEquals(1, completions)
            assertEquals(1, failures.size)
            assertSame(recoveredRequest, audioManager.lastAbandonedAudioFocusRequest)
        } finally {
            audio.close()
        }
    }

    @Test fun stoppingOrClosingDuringBindingNeverCallsStopOnAnUnboundEngine() {
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            initialized = callback
            engine
        }
        audio.speak("Pending reply", {}, failures::add)
        repeat(4) { audio.stop() }
        audio.close()
        audio.close()
        requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        verify(engine, org.mockito.Mockito.never()).stop()
        verify(engine, org.mockito.Mockito.times(1)).shutdown()
        verify(engine, org.mockito.Mockito.never()).speak(org.mockito.ArgumentMatchers.any(CharSequence::class.java), eq(TextToSpeech.QUEUE_ADD), isNull(), anyString())
        assertTrue(failures.isEmpty())
    }

    @Test fun preparingVoicesAndClosingAnIdleReadyEngineDoesNotStopIt() {
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            initialized = callback
            engine
        }
        audio.prepareVoices()
        requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(3) { audio.stop() }
        audio.close()
        verify(engine, org.mockito.Mockito.never()).stop()
        verify(engine, org.mockito.Mockito.times(1)).shutdown()
    }

    @Test fun disconnectedPlaybackRetiresItsEngineBeforeTheNextReply() {
        val callbacks = mutableListOf<TextToSpeech.OnInitListener>()
        val engines = mutableListOf<TextToSpeech>()
        val failures = mutableListOf<String>()
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { it.run() }) { _, callback ->
            callbacks += callback
            mock(TextToSpeech::class.java).also { engine ->
                engines += engine
                if (engines.size == 1) {
                    org.mockito.Mockito.`when`(engine.speak(org.mockito.ArgumentMatchers.any(CharSequence::class.java), eq(TextToSpeech.QUEUE_ADD), isNull(), anyString())).thenReturn(TextToSpeech.ERROR)
                }
            }
        }
        audio.allowOnlineSpeech = true
        try {
            audio.speak("Disconnected reply", {}, failures::add)
            callbacks[0].onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, failures.size)
            verify(engines[0]).shutdown()
            verify(engines[0], org.mockito.Mockito.never()).stop()

            audio.speak("Recovered reply", {}, failures::add)
            assertEquals(2, engines.size)
            callbacks[1].onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            verify(engines[1]).speak(eq<CharSequence>("Recovered reply"), eq(TextToSpeech.QUEUE_ADD), isNull(), anyString())
            assertEquals(1, failures.size)
        } finally {
            audio.close()
        }
    }

    @Test fun repeatedStopCancelsQueuedSpeechOnlyOnce() {
        val work = java.util.ArrayDeque<Runnable>()
        fun drain() {
            while (work.isNotEmpty()) work.removeFirst().run()
        }
        var initialized: TextToSpeech.OnInitListener? = null
        val engine = mock(TextToSpeech::class.java)
        val audio = AndroidVoiceAudio(RuntimeEnvironment.getApplication(), java.util.concurrent.Executor { work.add(it) }) { _, callback ->
            initialized = callback
            engine
        }
        audio.allowOnlineSpeech = true
        audio.speak("Speaking reply", {}, {})
        requireNotNull(initialized).onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        drain()
        repeat(4) { audio.stop() }
        drain()
        audio.close()
        drain()
        verify(engine, org.mockito.Mockito.times(1)).stop()
        verify(engine, org.mockito.Mockito.times(1)).shutdown()
    }
}
