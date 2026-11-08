package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Intent
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
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
class AndroidSpeechQualificationTest {
    private class Service : RecognitionService {
        val engine = mock(SpeechRecognizer::class.java)
        lateinit var intent: Intent
        lateinit var result: (RecognitionQualification) -> Unit
        val modes = mutableListOf<Boolean>()
        override fun available(onDevice: Boolean) = true
        override fun create(onDevice: Boolean): SpeechRecognizer {
            modes += onDevice
            return engine
        }
        override fun checkSupport(recognizer: SpeechRecognizer, intent: Intent, onDevice: Boolean, result: (RecognitionQualification) -> Unit) {
            assertSame(engine, recognizer)
            this.intent = intent
            this.result = result
        }
    }
    private class Listener : VoiceAudio.Listener {
        val repairs = mutableListOf<VoiceRecoveryAction>()
        override fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) {
            repairs += action
        }
        override fun ready() = Unit
        override fun level(value: Float) = Unit
        override fun partial(text: String) = Unit
        override fun final(text: String) = Unit
        override fun error(message: String) = Unit
    }
    private fun audio(service: Service): AndroidVoiceAudio {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        return AndroidVoiceAudio(context, Executor { it.run() }, { _, _ -> mock(TextToSpeech::class.java) }, service, RecognitionStartGate { 0L })
    }

    @Test fun captureWaitsForTheExactRequestedIntentAndStartsOnlyOnceAfterQualification() {
        val service = Service()
        val audio = audio(service).apply { languageTag = "fr-CA" }
        try {
            audio.listen(Listener())
            verify(service.engine, never()).startListening(any(Intent::class.java))
            assertEquals("fr-CA", service.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertTrue(service.intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, service.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            audio.languageTag = "en-US"
            service.result(RecognitionQualification(RecognitionSupportStatus.SUPPORTED))
            service.result(RecognitionQualification(RecognitionSupportStatus.SUPPORTED))
            verify(service.engine, times(1)).startListening(service.intent)
            assertEquals("fr-CA", service.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        } finally {
            audio.close()
        }
    }

    @Test fun unavailableLocaleOffersLanguageRepairWithoutRecordingOrOnlineFallback() {
        val service = Service()
        val audio = audio(service)
        val listener = Listener()
        try {
            audio.listen(listener)
            service.result(RecognitionQualification(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
            assertEquals(listOf(VoiceRecoveryAction.SPEECH_LANGUAGE), listener.repairs)
            assertEquals(listOf(true), service.modes)
            assertEquals(5000L, audio.retryAfterMillis())
            verify(service.engine, never()).startListening(any(Intent::class.java))
            verify(service.engine).destroy()
        } finally {
            audio.close()
        }
    }

    @Test fun supportRequestLimitIsServiceRecoveryAndNeverMicrophonePermission() {
        val service = Service()
        val audio = audio(service)
        val listener = Listener()
        try {
            audio.listen(listener)
            service.result(RecognitionQualification(RecognitionSupportStatus.FAILED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
            assertEquals(listOf(VoiceRecoveryAction.SPEECH_SERVICE), listener.repairs)
            assertFalse(listener.repairs.contains(VoiceRecoveryAction.MICROPHONE_PERMISSION))
            verify(service.engine, never()).startListening(any(Intent::class.java))
        } finally {
            audio.close()
        }
    }

    @Test fun cancellationAndSupportTimeoutFenceLateCallbacksWithoutMicrophoneDispatch() {
        for (timeout in listOf(false, true)) {
            val service = Service()
            val audio = audio(service)
            val listener = Listener()
            audio.listen(listener)
            if (timeout) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)) else audio.stop()
            service.result(RecognitionQualification(RecognitionSupportStatus.SUPPORTED))
            verify(service.engine, never()).startListening(any(Intent::class.java))
            assertEquals(if (timeout) listOf(VoiceRecoveryAction.SPEECH_SERVICE) else emptyList<VoiceRecoveryAction>(), listener.repairs)
            audio.close()
        }
    }

    @Test fun onlyExplicitOnlineConsentAllowsTheSystemOnlineRecognizer() {
        val service = Service()
        val audio = audio(service).apply { allowOnlineSpeech = true }
        try {
            audio.listen(Listener())
            assertEquals(listOf(false), service.modes)
            assertFalse(service.intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            service.result(RecognitionQualification(RecognitionSupportStatus.UNVERIFIED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT))
            verify(service.engine).startListening(service.intent)
        } finally {
            audio.close()
        }
    }

    @Test fun platformAdapterChecksTheIntentAndDistinguishesInstalledPendingAndOnlineLocales() {
        val context = RuntimeEnvironment.getApplication()
        val engine = mock(SpeechRecognizer::class.java)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CA")
        val results = mutableListOf<RecognitionQualification>()
        AndroidRecognitionService(context).checkSupport(engine, intent, true, results::add)
        val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
        verify(engine).checkRecognitionSupport(eq(intent), any(Executor::class.java), callback.capture())
        val support = mock(RecognitionSupport::class.java)
        `when`(support.installedOnDeviceLanguages).thenReturn(listOf("fr-CA"))
        callback.value.onSupportResult(support)
        assertEquals(RecognitionSupportStatus.SUPPORTED, results.last().status)
        callback.value.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        assertEquals(RecognitionSupportStatus.UNVERIFIED, results.last().status)
        callback.value.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, results.last().errorCode)
    }

    @Test fun languageQualificationNeverTreatsAnotherRegionOrOnlineOnlyAvailabilityAsOfflineReady() {
        assertEquals(RecognitionSupportStatus.UNSUPPORTED, qualifyRecognitionLanguage("fr-CA", true, listOf("fr-FR"), emptyList(), emptyList(), listOf("fr-CA")).status)
        assertEquals(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, qualifyRecognitionLanguage("fr-CA", true, emptyList(), listOf("fr-CA"), emptyList(), emptyList()).status)
        assertEquals(RecognitionSupportStatus.SUPPORTED, qualifyRecognitionLanguage("fr-CA", false, emptyList(), emptyList(), emptyList(), listOf("fr-ca")).status)
        assertEquals(RecognitionSupportStatus.UNSUPPORTED, qualifyRecognitionLanguage("", true, emptyList(), emptyList(), emptyList(), emptyList()).status)
    }

    @Test
    @Config(sdk = [32])
    fun olderAndroidReportsUnverifiedSupportWithoutCallingTheNewApi() {
        val results = mutableListOf<RecognitionQualification>()
        AndroidRecognitionService(RuntimeEnvironment.getApplication()).checkSupport(mock(SpeechRecognizer::class.java), Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), true, results::add)
        assertEquals(listOf(RecognitionQualification(RecognitionSupportStatus.UNVERIFIED)), results)
    }
}
