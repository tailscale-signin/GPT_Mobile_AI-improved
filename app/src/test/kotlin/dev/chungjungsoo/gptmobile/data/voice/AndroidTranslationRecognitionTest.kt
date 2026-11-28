package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
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
class AndroidTranslationRecognitionTest {
    private val pair = listOf("en-US", "fr-FR")

    private class Service : RecognitionService {
        val engine: SpeechRecognizer = mock(SpeechRecognizer::class.java)
        val modes = mutableListOf<Boolean>()
        val intents = mutableListOf<Intent>()
        var status = RecognitionSupportStatus.SUPPORTED
        var deferred = false
        lateinit var answer: () -> Unit
        override fun available(onDevice: Boolean): Boolean {
            modes += onDevice
            return true
        }
        override fun create(onDevice: Boolean) = engine
        override fun checkSupport(recognizer: SpeechRecognizer, intent: Intent, onDevice: Boolean, result: (RecognitionQualification) -> Unit) {
            intents += intent
            answer = { result(RecognitionQualification(status)) }
            if (!deferred) answer()
        }
        fun listener(): RecognitionListener {
            val listener = ArgumentCaptor.forClass(RecognitionListener::class.java)
            verify(engine, atLeastOnce()).setRecognitionListener(listener.capture())
            return listener.value
        }
        fun finish() {
            listener().onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("The captured words")) })
        }
    }

    private class Listener : VoiceAudio.Listener {
        val messages = mutableListOf<String>()
        val codes = mutableListOf<String>()
        val detectedLanguages = mutableListOf<String?>()
        override fun ready() = Unit
        override fun level(value: Float) = Unit
        override fun partial(text: String) = Unit
        override fun final(text: String) = Unit
        override fun detectedLanguage(languageTag: String?) {
            detectedLanguages += languageTag
        }
        override fun error(message: String) = Unit
        override fun failure(message: String, code: String, retryMillis: Long, retryable: Boolean, recoveryAction: VoiceRecoveryAction?, cause: Throwable?) {
            messages += message
            codes += code
        }
    }

    private fun audio(service: Service, now: () -> Long = { 0L }): AndroidVoiceAudio {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        return AndroidVoiceAudio(context, Executor { it.run() }, { _, _ -> mock(TextToSpeech::class.java) }, service, RecognitionStartGate(now))
    }

    private fun languageDetection(
        language: String?,
        confidence: Int = SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT,
        switchResult: Int = SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED
    ) = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, language)
        putInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, confidence)
        putInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, switchResult)
    }

    @Test fun androidDetectedLanguageRoutesTheSameShortUtteranceThroughTheRealSessionInBothDirections() {
        val translationPair = VoiceTranslationPair()
        val targets = listOf(
            VoiceTranslationTarget("en-US", "fr-FR", "estelle"),
            VoiceTranslationTarget("fr-FR", "en-US", "azelma")
        )
        for (expected in targets) {
            val service = Service()
            val audio = audio(service).apply { translationLanguages = pair }
            val submitted = mutableListOf<String>()
            val selected = mutableListOf<VoiceTranslationTarget>()
            val session = VoiceSession(audio, { text ->
                submitted += text
                true
            }, {}).apply {
                translationOnly = true
                microphoneFeedback = { true }
            }
            session.prepareDraft = { text, completed ->
                val language = session.recognizedLanguageFor(text, pair)
                val target = translationPair.resolve(language)
                completed(
                    if (target == null) {
                        VoiceDraftPreparation(rejection = "Choose a source language")
                    } else {
                        VoiceDraftPreparation(commit = { selected += target })
                    }
                )
            }
            try {
                session.access(enabled = true, permission = true, foreground = true)
                session.resume()
                val platform = service.listener()
                platform.onReadyForSpeech(Bundle())
                platform.onLanguageDetection(languageDetection(expected.sourceLanguage))
                platform.onPartialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("O")) })
                platform.onResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("OK")) })
                assertEquals(listOf(expected), selected)
                assertEquals(listOf("OK"), submitted)
                assertEquals(VoicePhase.THINKING, session.state.phase)
                assertEquals(expected.sourceLanguage, session.recognizedLanguageFor("OK", pair))
                platform.onLanguageDetection(languageDetection(expected.targetLanguage))
                assertEquals(expected.sourceLanguage, session.recognizedLanguageFor("OK", pair))
                assertEquals(listOf(expected), selected)
            } finally {
                session.close()
            }
        }
    }

    @Test fun actualDetectedLanguageReachesOnlyItsOwnCaptureAndNeverDefaultsToTheInitialLocale() {
        var now = 0L
        val service = Service()
        val audio = audio(service) { now }.apply { translationLanguages = pair }
        val first = Listener()
        try {
            audio.listen(first)
            val platform = service.listener()
            assertTrue(first.detectedLanguages.isEmpty())
            platform.onLanguageDetection(languageDetection("fr-CA", switchResult = SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED))
            assertEquals(listOf("fr-FR"), first.detectedLanguages)
            service.finish()
            platform.onLanguageDetection(languageDetection("en-US"))
            assertEquals(listOf("fr-FR"), first.detectedLanguages)
            now = 500
            val second = Listener()
            audio.listen(second)
            platform.onLanguageDetection(languageDetection("en-US"))
            assertTrue(second.detectedLanguages.isEmpty())
            service.listener().onLanguageDetection(Bundle())
            assertEquals(listOf<String?>(null), second.detectedLanguages)
        } finally {
            audio.close()
        }
    }

    @Test fun uncertainMissingOrFailedSwitchEvidenceRevokesThePreviouslyDetectedLanguage() {
        val service = Service()
        val audio = audio(service).apply { translationLanguages = pair }
        val listener = Listener()
        try {
            audio.listen(listener)
            val platform = service.listener()
            platform.onLanguageDetection(languageDetection("fr-FR", confidence = SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT))
            platform.onLanguageDetection(languageDetection("fr-FR", confidence = SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT))
            platform.onLanguageDetection(languageDetection("fr-FR"))
            platform.onLanguageDetection(languageDetection(null))
            platform.onLanguageDetection(languageDetection("fr-FR", switchResult = SpeechRecognizer.LANGUAGE_SWITCH_RESULT_FAILED))
            platform.onLanguageDetection(languageDetection("fr-FR", switchResult = SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL))
            platform.onLanguageDetection(languageDetection("fr-FR", confidence = 99))
            platform.onLanguageDetection(languageDetection("fr-FR"))
            assertEquals(listOf("fr-FR", null, "fr-FR", null, null, null, null, "fr-FR"), listener.detectedLanguages)
        } finally {
            audio.close()
        }
    }

    @Test fun mixedOrOutsidePairChunksCannotChooseTheSourceOfTheWholeUtterance() {
        for (otherLanguage in listOf("en-GB", "de-DE")) {
            val service = Service()
            val audio = audio(service).apply { translationLanguages = pair }
            val listener = Listener()
            try {
                audio.listen(listener)
                val platform = service.listener()
                platform.onLanguageDetection(languageDetection("fr-FR"))
                platform.onLanguageDetection(languageDetection(otherLanguage))
                platform.onLanguageDetection(languageDetection("fr-FR"))
                assertEquals(listOf("fr-FR", null, null), listener.detectedLanguages)
            } finally {
                audio.close()
            }
        }
    }

    @Test fun undispatchedManualAndChangedPairCallbacksCannotProvideTranslationEvidence() {
        val service = Service().apply { deferred = true }
        val audio = audio(service).apply { translationLanguages = pair }
        val listener = Listener()
        try {
            audio.listen(listener)
            val platform = service.listener()
            platform.onLanguageDetection(languageDetection("fr-FR"))
            assertTrue(listener.detectedLanguages.isEmpty())
            service.answer()
            platform.onLanguageDetection(languageDetection("fr-FR"))
            audio.translationLanguages = listOf("en-US", "es-ES")
            platform.onLanguageDetection(languageDetection("fr-FR"))
            assertEquals(listOf("fr-FR", null), listener.detectedLanguages)
        } finally {
            audio.close()
        }
        val manualService = Service()
        val manual = audio(manualService).apply { languageTag = "fr-FR" }
        val manualListener = Listener()
        try {
            manual.listen(manualListener)
            manualService.listener().onLanguageDetection(languageDetection("fr-FR"))
            assertTrue(manualListener.detectedLanguages.isEmpty())
        } finally {
            manual.close()
        }
    }

    @Test fun automaticTranslationRestrictsDetectionAndSwitchingToTheOfflinePair() {
        val service = Service()
        val audio = audio(service).apply {
            translationLanguages = pair
            allowOnlineSpeech = true
        }
        try {
            audio.listen(Listener())
            val intent = service.intents.single()
            assertEquals(listOf(true), service.modes)
            assertEquals("On device speech only", audio.processingLocation)
            assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, false))
            assertEquals(pair, intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES))
            assertEquals(pair, intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
            assertEquals(RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION, intent.getStringExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            assertEquals("en-US", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            verify(service.engine).startListening(intent)
        } finally {
            audio.close()
        }
    }

    @Test
    @Config(sdk = [33])
    fun olderAndroidRequiresExplicitSourceButKeepsManualRecognition() {
        val service = Service()
        val audio = audio(service).apply { translationLanguages = pair }
        val listener = Listener()
        try {
            audio.listen(listener)
            assertTrue(listener.messages.single().contains("Choose the source language manually"))
            assertEquals(listOf("VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE"), listener.codes)
            assertTrue(service.modes.isEmpty())
            verify(service.engine, never()).startListening(any(Intent::class.java))
            audio.translationLanguages = emptyList()
            audio.languageTag = "fr-FR"
            audio.listen(Listener())
            val intent = service.intents.single()
            assertEquals("fr-FR", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            verify(service.engine).startListening(intent)
        } finally {
            audio.close()
        }
    }

    @Test fun unverifiedDualLanguageSupportCannotStartCapture() {
        val service = Service().apply { status = RecognitionSupportStatus.UNVERIFIED }
        val audio = audio(service).apply { translationLanguages = pair }
        val listener = Listener()
        try {
            audio.listen(listener)
            assertEquals(listOf("VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE"), listener.codes)
            assertTrue(listener.messages.single().contains("Both selected offline speech languages"))
            verify(service.engine, never()).startListening(any(Intent::class.java))
        } finally {
            audio.close()
        }
    }

    @Test fun changingOnlyTheSecondLocaleOrLeavingAutomaticModeRequalifiesSupport() {
        var now = 0L
        val service = Service()
        val audio = audio(service) { now }.apply {
            languageTag = "en-US"
            translationLanguages = pair
        }
        try {
            audio.listen(Listener())
            service.finish()
            now = 500
            audio.translationLanguages = listOf("en-US", "fr-CA")
            audio.listen(Listener())
            service.finish()
            now = 1000
            audio.translationLanguages = emptyList()
            audio.listen(Listener())
            assertEquals(3, service.intents.size)
            assertEquals(listOf("en-US", "fr-CA"), service.intents[1].getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES))
            assertFalse(service.intents.last().hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
            verify(service.engine, times(3)).startListening(any(Intent::class.java))
        } finally {
            audio.close()
        }
    }

    @Test fun aLateQualificationCannotStartThePreviouslySelectedPair() {
        val service = Service().apply { deferred = true }
        val audio = audio(service).apply { translationLanguages = pair }
        val listener = Listener()
        try {
            audio.listen(listener)
            audio.translationLanguages = listOf("en-US", "fr-CA")
            service.answer()
            assertTrue(listener.messages.single().contains("Speech languages changed"))
            verify(service.engine, never()).startListening(any(Intent::class.java))
        } finally {
            audio.close()
        }
    }

    @Test fun thePlatformSupportCallbackQualifiesBothInstalledLocales() {
        val context = RuntimeEnvironment.getApplication()
        val engine = mock(SpeechRecognizer::class.java)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(pair))
        }
        val results = mutableListOf<RecognitionQualification>()
        AndroidRecognitionService(context).checkSupport(engine, intent, true, results::add)
        val callback = ArgumentCaptor.forClass(RecognitionSupportCallback::class.java)
        verify(engine).checkRecognitionSupport(eq(intent), any(Executor::class.java), callback.capture())
        val support = mock(RecognitionSupport::class.java)
        `when`(support.installedOnDeviceLanguages).thenReturn(listOf("en-US"))
        `when`(support.pendingOnDeviceLanguages).thenReturn(listOf("fr-FR"))
        `when`(support.supportedOnDeviceLanguages).thenReturn(pair)
        callback.value.onSupportResult(support)
        assertEquals(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, results.last().status)
        `when`(support.installedOnDeviceLanguages).thenReturn(pair)
        callback.value.onSupportResult(support)
        assertEquals(RecognitionSupportStatus.SUPPORTED, results.last().status)
    }
}
