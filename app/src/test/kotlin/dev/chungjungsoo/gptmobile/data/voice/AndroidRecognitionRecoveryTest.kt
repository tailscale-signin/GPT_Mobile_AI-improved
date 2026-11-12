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
import org.mockito.Mockito.times
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
        var qualifications = 0
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
        override fun checkSupport(recognizer: SpeechRecognizer, intent: Intent, onDevice: Boolean, result: (RecognitionQualification) -> Unit) {
            qualifications++
            result(RecognitionQualification(RecognitionSupportStatus.SUPPORTED))
        }
        fun listener(): RecognitionListener {
            val captor = ArgumentCaptor.forClass(RecognitionListener::class.java)
            verify(engine, org.mockito.Mockito.atLeastOnce()).setRecognitionListener(captor.capture())
            return captor.value
        }
    }

    private class Listener : VoiceAudio.Listener {
        val failures = mutableListOf<String>()
        val codes = mutableListOf<String>()
        val causes = mutableListOf<Throwable?>()
        val repairs = mutableListOf<VoiceRecoveryAction>()
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
        override fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) {
            repairs += action
            failure(message, retryMillis)
        }
        override fun failure(message: String, code: String, retryMillis: Long, retryable: Boolean, recoveryAction: VoiceRecoveryAction?, cause: Throwable?) {
            codes += code
            causes += cause
            super.failure(message, code, retryMillis, retryable, recoveryAction, cause)
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
        assertEquals(listOf("VOICE_RECOGNIZER_RATE_LIMITED"), listener.codes)
        assertEquals(listOf(VoiceRecoveryAction.SPEECH_SERVICE), listener.repairs)
        assertEquals(15000L, first.retryAfterMillis())
        first.listen(listener)
        assertEquals(1, service.creations)
        first.close()
        val secondService = Service()
        val second = audio(secondService, gate)
        try {
            second.listen(listener)
            assertEquals(0, secondService.creations)
            now = 15000
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
            assertTrue(listener.failures.single().contains("request or connection limit"))
            assertEquals(0, listener.retryable)
            assertEquals(15000L, audio.retryAfterMillis())
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
            assertEquals(listOf("VOICE_RECOGNIZER_UNAVAILABLE"), listener.codes)
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
            assertEquals(listOf("VOICE_RECOGNIZER_UNAVAILABLE"), listener.codes)
            assertTrue(listener.causes.single() is IllegalStateException)
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
        assertEquals(listOf("VOICE_MICROPHONE_PERMISSION_REQUIRED"), listener.codes)
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
            assertEquals(0, listener.retryable)
            assertTrue(listener.failures.single().contains("Automatic retries are paused"))
            assertEquals(15000L, audio.retryAfterMillis())
            verify(service.engine).cancel()
            verify(service.engine).destroy()
            // A retired binder cannot extend the cooldown or trigger another retry.
            platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            assertEquals(0, listener.retryable)
            assertEquals(1, listener.failures.size)
            assertEquals(15000L, audio.retryAfterMillis())
        } finally {
            audio.close()
        }
    }

    @Test fun networkFailureKeepsItsDetailedCodeAndExistingAutomaticRetryEligibility() {
        val service = Service()
        val audio = audio(service, RecognitionStartGate { 0L })
        val listener = Listener()
        try {
            audio.listen(listener)
            service.listener().onReadyForSpeech(Bundle())
            service.listener().onError(SpeechRecognizer.ERROR_NETWORK)
            assertEquals(listOf("VOICE_RECOGNIZER_NETWORK_FAILED"), listener.codes)
            assertEquals(1, listener.retryable)
            assertTrue(listener.repairs.isEmpty())
            assertEquals(500L, audio.retryAfterMillis())
        } finally {
            audio.close()
        }
    }

    @Test fun startupDeadlineKeepsItsDetailedTimeoutCodeAndRequiresServiceRepair() {
        val service = Service()
        val audio = audio(service, RecognitionStartGate { 0L })
        val listener = Listener()
        try {
            audio.listen(listener)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertEquals(listOf("VOICE_RECOGNIZER_TIMED_OUT"), listener.codes)
            assertEquals(listOf(VoiceRecoveryAction.SPEECH_SERVICE), listener.repairs)
            assertEquals(0, listener.retryable)
            service.listener().onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            assertEquals(1, listener.codes.size)
        } finally {
            audio.close()
        }
    }

    @Test fun completedTurnsReuseOneQualifiedRecognizerAndIgnoreTheOldListener() {
        var now = 0L
        val service = Service()
        val audio = audio(service, RecognitionStartGate { now })
        val first = Listener()
        val second = Listener()
        try {
            audio.listen(first)
            audio.listen(first)
            val old = service.listener()
            old.onReadyForSpeech(Bundle())
            old.onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("First turn")) })
            // VoiceSession calls stop() after final and again before the next turn.
            repeat(3) { audio.stop() }
            verify(service.engine, never()).cancel()
            verify(service.engine, never()).destroy()
            now = 500
            audio.listen(second)
            assertEquals(1, service.creations)
            assertEquals(1, service.qualifications)
            verify(service.engine, times(2)).startListening(any(Intent::class.java))
            old.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            old.onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Stale result")) })
            assertEquals("First turn", first.final)
            assertTrue(first.failures.isEmpty())
            assertTrue(second.failures.isEmpty())
            assertEquals("", second.final)
            service.listener().onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Second turn")) })
            assertEquals("Second turn", second.final)
            audio.releaseRecognition()
            verify(service.engine, times(1)).cancel()
            verify(service.engine, times(1)).destroy()
        } finally {
            audio.close()
        }
        verify(service.engine, times(1)).destroy()
    }

    @Test fun requestLimitNeverAutoRestartsAContinuousVoiceSessionBeforeOrAfterReady() {
        for (ready in listOf(false, true)) {
            var now = 0L
            val service = Service()
            val audio = audio(service, RecognitionStartGate { now })
            val session = VoiceSession(audio, { true }, {}, { now }).apply { autoContinue = true }
            try {
                session.access(enabled = true, permission = true, foreground = true)
                session.resume()
                val platform = service.listener()
                if (ready) platform.onReadyForSpeech(Bundle())
                platform.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
                assertEquals(VoicePhase.ERROR, session.state.phase)
                assertEquals(VoiceRecoveryAction.SPEECH_SERVICE, session.state.recoveryAction)
                assertEquals(15000L, session.state.retryAtMillis)
                now = 20000
                session.tick()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                verify(service.engine, times(1)).startListening(any(Intent::class.java))
                assertEquals(VoicePhase.ERROR, session.state.phase)
                session.resume()
                verify(service.engine, times(2)).startListening(any(Intent::class.java))
            } finally {
                session.close()
            }
        }
    }

    @Test fun changingLanguageRequalifiesAndChangingPrivacyModeRecreatesTheRecognizer() {
        var now = 0L
        val service = Service()
        val audio = audio(service, RecognitionStartGate { now }).apply { languageTag = "en-US" }
        try {
            fun finish() = service.listener().onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Complete")) })
            audio.listen(Listener())
            finish()
            now = 500
            audio.languageTag = "fr-CA"
            audio.listen(Listener())
            assertEquals(1, service.creations)
            assertEquals(2, service.qualifications)
            finish()
            now = 1000
            audio.allowOnlineSpeech = true
            audio.listen(Listener())
            assertEquals(2, service.creations)
            assertEquals(3, service.qualifications)
            assertEquals(listOf(true, true, false), service.modes)
            verify(service.engine, times(1)).destroy()
        } finally {
            audio.close()
        }
        verify(service.engine, times(2)).destroy()
    }

    @Test fun localMicrophoneOwnerBlocksSystemRecognitionUntilCaptureIsReleased() {
        var now = 0L
        val service = Service()
        val audio = audio(service, RecognitionStartGate { now })
        assertTrue(VoiceMicrophoneLease.acquire("local-test"))
        try {
            val blocked = Listener()
            audio.listen(blocked)
            assertEquals(0, service.creations)
            assertTrue(blocked.failures.single().contains("Another voice session"))
            VoiceMicrophoneLease.release("local-test")
            now = 500
            audio.listen(Listener())
            assertEquals(1, service.creations)
            assertFalse(VoiceMicrophoneLease.acquire("another-local-test"))
            audio.stop()
            assertTrue(VoiceMicrophoneLease.acquire("another-local-test"))
        } finally {
            audio.close()
            VoiceMicrophoneLease.release("local-test")
            VoiceMicrophoneLease.release("another-local-test")
        }
    }

    @Test fun serviceUnavailableReleasesTheCommonMicrophoneLease() {
        val service = Service().apply { available = false }
        val audio = audio(service, RecognitionStartGate { 0L })
        try {
            audio.listen(Listener())
            assertTrue(VoiceMicrophoneLease.acquire("recovered-local-test"))
        } finally {
            audio.close()
            VoiceMicrophoneLease.release("recovered-local-test")
        }
    }
}
