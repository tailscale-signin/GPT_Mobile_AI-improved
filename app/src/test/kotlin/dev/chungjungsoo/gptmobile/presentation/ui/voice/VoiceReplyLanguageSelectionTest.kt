package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.PocketCompiledSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReplyLanguageSelectionTest {
    @Test fun compiledPocketOffersOnlyItsEnglishProfileAndPreservesTheNativeLanguagePreference() {
        val native = VoiceSynthesisPreferences("ja", mapOf("ja" to "jf_nezumi"))
        for (engine in listOf(VoiceSpeechBackend.POCKET_GPU, VoiceSpeechBackend.POCKET_NPU)) {
            val settings = VoiceBackendSettings(backend = engine, streamingVoiceId = "mary")
            val profiles = settings.replyLanguageProfiles(native)
            assertEquals(listOf("en-US"), profiles.map { it.tag })
            assertEquals(PocketCompiledSpeechCatalog.VOICES, profiles.single().voices)
            assertEquals(settings.synthesisProfile(native), profiles.single())
            val selectedVoices = mutableListOf<String>()
            val selectedLanguages = mutableListOf<VoiceSynthesisPreferences>()
            for (tag in listOf("ja", "fr", "en-US", "unknown")) {
                settings.selectReplyLanguage(tag, native, selectedVoices::add, selectedLanguages::add)
            }
            assertTrue(selectedVoices.isEmpty())
            assertTrue(selectedLanguages.isEmpty())
            assertEquals("jf_nezumi", native.voiceId)
        }
    }

    @Test fun multilingualPocketChangesItsOwnVoiceWithoutChangingTheNativeLanguage() {
        val native = VoiceSynthesisPreferences("ja")
        val settings = VoiceBackendSettings(backend = VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU, streamingVoiceId = "alba")
        val french = MediaPipeSpeechVoices.profiles(MediaPipeSpeechEngine.POCKET_MULTILINGUAL).single { it.tag == "fr-FR" }
        val selectedVoices = mutableListOf<String>()
        val selectedLanguages = mutableListOf<VoiceSynthesisPreferences>()
        settings.selectReplyLanguage("fr-FR", native, selectedVoices::add, selectedLanguages::add)
        settings.selectReplyLanguage("unknown", native, selectedVoices::add, selectedLanguages::add)
        assertEquals(listOf(french.voices.first()), selectedVoices)
        assertTrue(selectedLanguages.isEmpty())
    }

    @Test fun kokoroLanguageSelectionStillUsesItsSavedPerLanguageVoices() {
        val native = VoiceSynthesisPreferences("ja", mapOf("ja" to "jf_nezumi", "fr" to "ff_siwis"))
        val settings = VoiceBackendSettings(backend = VoiceSpeechBackend.LITERT_KOKORO_CPU)
        assertEquals(SpeechLanguages.synthesis, settings.replyLanguageProfiles(native))
        val selectedVoices = mutableListOf<String>()
        val selectedLanguages = mutableListOf<VoiceSynthesisPreferences>()
        settings.selectReplyLanguage("fr", native, selectedVoices::add, selectedLanguages::add)
        settings.selectReplyLanguage("unknown", native, selectedVoices::add, selectedLanguages::add)
        assertTrue(selectedVoices.isEmpty())
        assertEquals(listOf(native.selectLanguage("fr")), selectedLanguages)
        assertEquals("ff_siwis", selectedLanguages.single().voiceId)
    }
}
