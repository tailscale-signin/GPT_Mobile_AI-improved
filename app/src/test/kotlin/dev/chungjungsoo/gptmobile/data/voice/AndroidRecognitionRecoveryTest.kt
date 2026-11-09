package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidRecognitionRecoveryTest {
    private class Service : RecognitionService {
        val engine: SpeechRecognizer = mock(SpeechRecognizer::class.java)
        var available = true
        var throwOnAvailability = false
        var creations = 0
        val modes = mutableListOf<Boolean>()
        override fun available(onDevice: Boolean): Boolean {
            modes += onDevice
            if (throwOnAvailability) throw IllegalStateException("Service manager unavailable")
            return available
        }
        override fun create(onDevice: Boolean): SpeechRecognizer {
            creations++
            return engine
        }
        fun listener(): RecognitionListener {
            val captor = ArgumentCaptor.forClass(RecognitionListener::class.java)
            verify(engine).setRecognitionListener(captor.capture())
            return captor.value
        }
    }

    private class Listener : VoiceAudio.Listener {
        val failures = mutableListOf<String>()
        var retryable = 0
        var ready = false
        var final = ""
        override fun ready() {
            ready = true
        }
        override fun level(value: Float) = Unit
        override fun partial(text: String) = Unit
        override fun final(text: String) {
            final = text
        }
        override fun error(message: String) {
            failures += message
        }
        override fun failure(message: String, retryMillis: Long) {
            failures += message
        }
        override fun retryableFailure(message: String, retryMillis: Long) {
            retryable++
        }
    }

    private fun audio(service: Service, gate: RecognitionStartGate): AndroidVoiceAudio {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        return AndroidVoiceAudio(context, Executor { it.run() }, { _, _ -> mock(TextToSpeech::class.java) }, service, gate)
    }

    @Test fun bindingErrorTenDoesNotScheduleAutomaticRetryAndSurvivesSessionRecreation() {
        var now = 0L
        val gate = RecognitionStartGate { now }
        val service = Service()
        val first = audio(service, gate)
        val listener = Listener()
        first.listen(listener)
        service.listener().onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
        assertEquals(0, listener.retryable)
        assertTrue(listener.failures.single().contains("continue typing"))
        assertEquals(5000L, first.retryAfterMillis())
        first.listen(listener)
        assertEquals(1, service.creations)
        first.close()
        val secondService = Service()
        val second = audio(secondService, gate)
        try {
            second.listen(listener)
            assertEquals(0, secondService.creations)
            now = 5000
            second.listen(Listener())
            assertEquals(1, secondService.creations)
        } finally {
            second.close()
        }
    }

    @Test fun preReadyErrorTenRetainsItsRateLimitMeaningAndOneEngineLifetime() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = Service()
        val records = mutableListOf<Pair<String, String>>()
        val audio = AndroidVoiceAudio(context, Executor { it.run() }, { _, _ -> mock(TextToSpeech::class.java) }, service, RecognitionStartGate { 0L }) { tag, message, _ -> records += tag to message }
        val listener = Listener()
        audio.languageTag = "en-US\nprivate-setting"
        try {
            audio.listen(listener)
            val platform = service.listener()
            platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            audio.stop()
            val failure = records.single { it.first == "VoiceRecognition" }.second
            assertTrue(failure.contains("recognition_error=10"))
            assertTrue(failure.contains("errorCode=SPEECH_RATE_LIMITED"))
            assertTrue(failure.contains("failedStage=service_start"))
            assertTrue(failure.contains("locale=und"))
            assertFalse(records.any { it.second.contains("private-setting") })
            assertEquals(1, records.count { it.second.startsWith("recognizer_created") })
            assertEquals(1, records.count { it.second.startsWith("recognizer_retired") })
            assertTrue(records.single { it.second.startsWith("recognizer_created") }.second.contains("activeOwnedEngines=1"))
            assertTrue(records.single { it.second.startsWith("recognizer_retired") }.second.contains("activeOwnedEngines=0"))
            assertTrue(listener.failures.single().contains("request limit"))
            assertEquals(0, listener.retryable)
            assertEquals(5000L, audio.retryAfterMillis())
            verify(service.engine).destroy()
        } finally {
            audio.close()
        }
    }

    @Test fun anUnavailableOfflineRecognizerNeverBindsAnOnlineService() {
        val service = Service().apply { available = false }
        val audio = audio(service, RecognitionStartGate { 0L })
        val listener = Listener()
        try {
            audio.listen(listener)
            assertEquals(listOf(true), service.modes)
            assertEquals(0, service.creations)
            assertTrue(listener.failures.single().contains("On device speech is unavailable"))
            assertEquals(0, listener.retryable)
        } finally {
            audio.close()
        }
    }

    @Test fun serviceInspectionExceptionReleasesTheAttemptAndLeavesChatUsable() {
        val service = Service().apply { throwOnAvailability = true }
        val audio = audio(service, RecognitionStartGate { 0L })
        val listener = Listener()
        try {
            audio.listen(listener)
            assertEquals(0, service.creations)
            assertEquals(1, listener.failures.size)
            assertTrue(listener.failures.single().contains("continue typing"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(1, listener.failures.size)
        } finally {
            audio.close()
        }
    }

    @Test fun readyRecognitionStillReturnsResultsAndFencesCallbacksAfterClose() {
        val service = Service()
        val audio = audio(service, RecognitionStartGate { 0L })
        val listener = Listener()
        audio.listen(listener)
        val platform = service.listener()
        platform.onReadyForSpeech(Bundle())
        assertTrue(listener.ready)
        platform.onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Read the repository")) })
        assertEquals("Read the repository", listener.final)
        audio.close()
        platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
        assertTrue(listener.failures.isEmpty())
    }

    @Test fun missingPermissionAndClosedLifecycleNeverStartRecognition() {
        val service = Service()
        val audio = audio(service, RecognitionStartGate { 0L })
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val listener = Listener()
        audio.listen(listener)
        assertEquals(0, service.creations)
        assertFalse(listener.failures.isEmpty())
        audio.close()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        audio.listen(listener)
        verify(service.engine, never()).startListening(any(Intent::class.java))
    }

    @Test fun errorTenAfterReadyUsesRateCooldownAndClosesTheOldRecognizer() {
        val service = Service()
        val audio = audio(service, RecognitionStartGate { 0L })
        val listener = Listener()
        try {
            audio.listen(listener)
            val platform = service.listener()
            platform.onReadyForSpeech(Bundle())
            platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            assertEquals(1, listener.retryable)
            assertTrue(listener.failures.isEmpty())
            assertEquals(1000L, audio.retryAfterMillis())
            verify(service.engine).cancel()
            verify(service.engine).destroy()
            // A retired binder cannot extend the cooldown or trigger another retry.
            platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            assertEquals(1, listener.retryable)
            assertEquals(1000L, audio.retryAfterMillis())
        } finally {
            audio.close()
        }
    }
}
