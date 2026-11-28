package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModelCatalog
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionTranslationPreferenceTest {
    @Test
    fun everyRecognitionPackRestoresBesideIndependentTranslationVoicesAndOrdinaryReplies() {
        for (pack in VoiceRecognitionModelCatalog.packs) {
            val language = if (pack.model == VoiceRecognitionModel.ZIPFORMER_FR) "fr-CA" else "en-GB"
            val restored = readVoicePreferences(
                pairedTranslation + mapOf(
                    "language" to language,
                    "recognition_backend" to VoiceRecognitionBackend.LOCAL_MOONSHINE.name,
                    "recognition_model" to pack.model.id,
                    "recognition_compute" to pack.compute.name,
                    "recognition_threads" to 3,
                    "synthesis_language" to "ja",
                    "synthesis_voices" to setOf("ja=jf_nezumi"),
                    "captions" to false
                )
            )
            assertEquals(VoiceRecognitionConfiguration(pack.model, pack.compute, 3), restored.recognitionConfiguration)
            assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
            assertEquals(language, restored.language)
            assertTrue(restored.translationMode)
            assertEquals(VoiceTranslationPair("fr-FR", "en-US", "estelle", "eponine", "fr-FR"), restored.translationPair)
            assertEquals(VoiceSynthesisPreferences("ja", mapOf("ja" to "jf_nezumi")), restored.synthesis)
            assertFalse(restored.captions)
            assertNull(restored.translationSetupNotice)
        }
    }

    @Test
    fun malformedRecognitionFieldsCannotResetTheSavedTranslationDirectionOrVoices() {
        val restored = readVoicePreferences(
            pairedTranslation + mapOf(
                "language" to "en-CA",
                "recognition_model" to "removed-model",
                "recognition_compute" to VoiceRecognitionCompute.NPU.name,
                "recognition_threads" to 99,
                "recognition_backend" to VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE.name
            )
        )
        assertEquals(VoiceRecognitionConfiguration(), restored.recognitionConfiguration)
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, restored.recognitionBackend)
        assertEquals(VoiceTranslationPair("fr-FR", "en-US", "estelle", "eponine", "fr-FR"), restored.translationPair)
        assertTrue(restored.translationMode)
    }

    @Test
    fun malformedTranslationFieldsCannotResetAnAcceleratorOrInventASourceDirection() {
        val restored = readVoicePreferences(
            nemotron + mapOf(
                "translation_mode" to true,
                "translation_base_language" to "invalid",
                "translation_target_language" to "en-GB",
                "translation_base_voice" to "rafael",
                "translation_target_voice" to "estelle",
                "translation_source_language" to "pt-BR"
            )
        )
        assertEquals(VoiceRecognitionConfiguration(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU, 4), restored.recognitionConfiguration)
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
        assertEquals("fr-CA", restored.language)
        assertEquals(VoiceTranslationPair("fr-FR", "en-US", "estelle", "azelma"), restored.translationPair)
        assertNull(restored.translationPair.sourceLanguage)
    }

    @Test
    fun legacyTranslationMigrationKeepsTheSelectedRecognitionRouteAndOrdinarySpeech() {
        val restored = readVoicePreferences(nemotron + mapOf("translation_mode" to true, "synthesis_language" to "en-US"), legacyReplyLanguage = "pt-BR")
        assertEquals(VoiceRecognitionConfiguration(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU, 4), restored.recognitionConfiguration)
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
        assertEquals(VoiceTranslationPair(translationLanguage = "pt-BR", translationVoiceId = "rafael"), restored.translationPair)
        assertEquals("en-US", restored.synthesis.language)
        assertTrue(restored.translationMode)
    }

    @Test
    fun frenchTranslationKeepsLocalZipformerWhenTheOrdinaryLanguageIsEnglish() {
        val saved = mapOf(
            "language" to "en-US",
            "recognition_backend" to VoiceRecognitionBackend.LOCAL_MOONSHINE.name,
            "recognition_model" to VoiceRecognitionModel.ZIPFORMER_FR.id,
            "recognition_compute" to VoiceRecognitionCompute.CPU.name,
            "recognition_threads" to 4,
            "translation_mode" to true,
            "translation_base_language" to "en-US",
            "translation_target_language" to "fr-FR",
            "translation_base_voice" to "azelma",
            "translation_target_voice" to "estelle"
        )
        for (source in listOf(null, "fr-FR")) {
            val restored = readVoicePreferences(saved + (source?.let { mapOf("translation_source_language" to it) } ?: emptyMap()))
            assertEquals("en-US", restored.language)
            assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, restored.recognitionBackend)
            assertEquals(VoiceRecognitionConfiguration(VoiceRecognitionModel.ZIPFORMER_FR, VoiceRecognitionCompute.CPU, 4), restored.recognitionConfiguration)
            assertEquals(source, restored.translationPair.sourceLanguage)
            assertEquals(
                if (source == null) VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE else VoiceRecognitionBackend.LOCAL_MOONSHINE,
                SpeechLanguages.translationRecognitionBackend(restored.translationPair.sourceLanguage, restored.recognitionBackend, restored.recognitionModel)
            )
            assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, SpeechLanguages.recognitionBackend(restored.language, restored.recognitionBackend, restored.recognitionModel))
        }
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, readVoicePreferences(saved + ("translation_mode" to false)).recognitionBackend)
    }

    private val pairedTranslation = mapOf(
        "translation_mode" to true,
        "translation_base_language" to "fr-CA",
        "translation_target_language" to "en-GB",
        "translation_base_voice" to "estelle",
        "translation_target_voice" to "eponine",
        "translation_source_language" to "fr-CA"
    )
    private val nemotron = mapOf(
        "language" to "fr-CA",
        "recognition_backend" to VoiceRecognitionBackend.LOCAL_MOONSHINE.name,
        "recognition_model" to VoiceRecognitionModel.NEMOTRON_3_5.id,
        "recognition_compute" to VoiceRecognitionCompute.GPU.name,
        "recognition_threads" to 4
    )
}
