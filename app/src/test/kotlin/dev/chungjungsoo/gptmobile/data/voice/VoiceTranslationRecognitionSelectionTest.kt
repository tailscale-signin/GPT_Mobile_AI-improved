package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranslationRecognitionSelectionTest {
    @Test
    fun automaticTranslationKeepsAndroidRecognitionForEveryLocalModel() {
        for (model in VoiceRecognitionModel.entries) {
            for (selected in VoiceRecognitionBackend.entries) {
                assertEquals(
                    VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE,
                    SpeechLanguages.translationRecognitionBackend(null, selected, model)
                )
            }
        }
    }

    @Test
    fun anExplicitSourceCanUseEachSelectedLocalModelsSupportedLanguages() {
        val cases = listOf(
            VoiceRecognitionModel.MOONSHINE_SMALL to "en-CA",
            VoiceRecognitionModel.ZIPFORMER_EN to "en-GB",
            VoiceRecognitionModel.ZIPFORMER_FR to "fr-CA",
            VoiceRecognitionModel.NEMOTRON_3_5 to "fr-FR",
            VoiceRecognitionModel.NEMOTRON_3_5 to "pt-PT",
            VoiceRecognitionModel.NEMOTRON_3_5 to "de-DE"
        )
        for ((model, language) in cases) {
            assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, SpeechLanguages.translationRecognitionBackend(language, VoiceRecognitionBackend.LOCAL_MOONSHINE, model))
        }
    }

    @Test
    fun explicitSourcesOutsideTheSelectedModelsLanguagesUseAndroid() {
        val cases = listOf(
            VoiceRecognitionModel.MOONSHINE_MEDIUM to "fr-FR",
            VoiceRecognitionModel.ZIPFORMER_EN to "fr-CA",
            VoiceRecognitionModel.ZIPFORMER_FR to "en-US",
            VoiceRecognitionModel.NEMOTRON_3_5 to "xx"
        )
        for ((model, language) in cases) {
            assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, SpeechLanguages.translationRecognitionBackend(language, VoiceRecognitionBackend.LOCAL_MOONSHINE, model))
        }
    }

    @Test
    fun aSavedAndroidChoiceIsNotReplacedWhenTheLocalModelAlsoSupportsTheSource() {
        for (model in VoiceRecognitionModel.entries) {
            for (language in listOf("en-US", "fr-CA", "pt-BR")) {
                assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, SpeechLanguages.translationRecognitionBackend(language, VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, model))
            }
        }
    }
}
