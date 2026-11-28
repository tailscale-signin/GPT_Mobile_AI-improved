package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModelCatalog
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceRecognitionPreferenceTest {
    @Test
    fun everyPublishedRouteRestoresItsOwnModelComputeAndThreadChoice() {
        for (pack in VoiceRecognitionModelCatalog.packs) {
            val language = if (pack.model == VoiceRecognitionModel.ZIPFORMER_FR) "fr-CA" else "en-GB"
            for (threads in listOf(0, 1, 8)) {
                val restored = readVoicePreferences(
                    mapOf(
                        "language" to language,
                        "recognition_backend" to VoiceRecognitionBackend.LOCAL_MOONSHINE.name,
                        "recognition_model" to pack.model.id,
                        "recognition_compute" to pack.compute.name,
                        "recognition_threads" to threads
                    )
                )
                assertEquals(VoiceRecognitionConfiguration(pack.model, pack.compute, threads), restored.recognitionConfiguration)
                assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
                assertEquals(language, restored.language)
            }
        }
    }

    @Test
    fun unsupportedProcessorFallsBackWithinTheSameModelWithoutChangingOtherPreferences() {
        val restored = readVoicePreferences(
            mapOf(
                "language" to "fr-FR",
                "recognition_model" to VoiceRecognitionModel.ZIPFORMER_FR.id,
                "recognition_compute" to VoiceRecognitionCompute.NPU.name,
                "recognition_threads" to 4,
                "synthesis_language" to "pt-BR",
                "captions" to false
            )
        )
        assertEquals(VoiceRecognitionConfiguration(VoiceRecognitionModel.ZIPFORMER_FR, VoiceRecognitionCompute.CPU, 4), restored.recognitionConfiguration)
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
        assertEquals("fr-FR", restored.language)
        assertEquals("pt-BR", restored.synthesis.language)
        assertFalse(restored.captions)
    }

    @Test
    fun unknownModelAndMalformedThreadValuesRecoverIndependently() {
        for (threadValue in listOf<Any>(-1, 9, "4", 4L)) {
            val restored = readVoicePreferences(
                mapOf(
                    "language" to "en-CA",
                    "recognition_model" to "removed-model",
                    "recognition_compute" to VoiceRecognitionCompute.GPU.name,
                    "recognition_threads" to threadValue,
                    "review" to true
                )
            )
            assertEquals(VoiceRecognitionConfiguration(), restored.recognitionConfiguration)
            assertEquals("en-CA", restored.language)
            assertEquals(true, restored.review)
        }
    }

    @Test
    fun modelLanguageSupportAndExplicitAndroidChoiceSurviveRestore() {
        val local = mapOf(
            "language" to "pt-PT",
            "recognition_model" to VoiceRecognitionModel.NEMOTRON_3_5.id,
            "recognition_backend" to VoiceRecognitionBackend.LOCAL_MOONSHINE.name
        )
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, readVoicePreferences(local).recognitionBackend)
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, readVoicePreferences(local + ("recognition_model" to VoiceRecognitionModel.MOONSHINE_SMALL.id)).recognitionBackend)
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, readVoicePreferences(local + ("recognition_backend" to VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE.name)).recognitionBackend)
    }

    @Test
    fun savedAcceleratorSelectionRoundTripsThroughTheActualPreferenceWriter() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences("voice-recognition-selection-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = VoicePreferenceStore({ preferences }, backgroundScope)
        runCurrent()
        val requested = requireNotNull(store.preferences.value).copy(
            language = "fr-CA",
            recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE,
            recognitionModel = VoiceRecognitionModel.NEMOTRON_3_5,
            recognitionCompute = VoiceRecognitionCompute.GPU,
            recognitionThreads = 3,
            synthesis = VoiceSynthesisPreferences("pt-BR"),
            translationMode = true,
            translationPair = VoiceTranslationPair(baseVoiceId = "eponine", sourceLanguage = "fr-FR"),
            captions = false
        )
        store.save(requested)
        runCurrent()
        assertNull(store.saveError.value)
        assertEquals("nemotron-3.5", preferences.getString("recognition_model", null))
        assertEquals("GPU", preferences.getString("recognition_compute", null))
        assertEquals(3, preferences.getInt("recognition_threads", -1))
        assertEquals("eponine", preferences.getString("translation_base_voice", null))
        assertEquals("estelle", preferences.getString("translation_target_voice", null))
        assertEquals("fr-FR", preferences.getString("translation_source_language", null))
        val reloaded = VoicePreferenceStore({ preferences }, backgroundScope)
        runCurrent()
        val restored = requireNotNull(reloaded.preferences.value)
        assertEquals(requested.recognitionConfiguration, restored.recognitionConfiguration)
        assertEquals(requested.recognitionBackend, restored.recognitionBackend)
        assertEquals(requested.language, restored.language)
        assertEquals(requested.synthesis, restored.synthesis)
        assertEquals(requested.translationPair, restored.translationPair)
        assertEquals(requested.translationMode, restored.translationMode)
        assertFalse(restored.captions)
    }

    @Test
    fun explicitFrenchTranslationKeepsLocalZipformerAfterPreferenceReload() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferences = context.getSharedPreferences("voice-local-translation-selection-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = VoicePreferenceStore({ preferences }, backgroundScope)
        runCurrent()
        val requested = requireNotNull(store.preferences.value).copy(
            language = "en-US",
            recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE,
            recognitionModel = VoiceRecognitionModel.ZIPFORMER_FR,
            recognitionCompute = VoiceRecognitionCompute.CPU,
            recognitionThreads = 4,
            translationMode = true,
            translationPair = VoiceTranslationPair(sourceLanguage = "fr-FR")
        )
        store.save(requested)
        runCurrent()
        val reloaded = VoicePreferenceStore({ preferences }, backgroundScope)
        runCurrent()
        val restored = requireNotNull(reloaded.preferences.value)
        assertEquals("en-US", restored.language)
        assertEquals(requested.recognitionConfiguration, restored.recognitionConfiguration)
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
        assertEquals(requested.translationPair, restored.translationPair)
        assertEquals(requested.translationMode, restored.translationMode)
        assertNull(store.saveError.value)
        assertNull(reloaded.saveError.value)
    }
}
