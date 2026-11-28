package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeSpeechVoicesTest {
    @Test fun installedPackIdentitiesExposeOnlyTheirOwnVoices() {
        assertEquals(8, MediaPipeSpeechVoices.ids(MediaPipeSpeechEngine.POCKET).size)
        assertEquals(14, MediaPipeSpeechVoices.ids(MediaPipeSpeechEngine.POCKET_MULTILINGUAL).size)
        assertEquals(5, MediaPipeSpeechVoices.ids(MediaPipeSpeechEngine.PHONON).size)
        assertEquals(7, MediaPipeSpeechVoices.profiles(MediaPipeSpeechEngine.POCKET_MULTILINGUAL).size)
        assertNull(MediaPipeSpeechVoices.profile(MediaPipeSpeechEngine.POCKET, "estelle"))
        assertNull(MediaPipeSpeechVoices.profile(MediaPipeSpeechEngine.PHONON, "marius"))
        assertEquals("fr-FR", MediaPipeSpeechVoices.profile(MediaPipeSpeechEngine.POCKET_MULTILINGUAL, "estelle")?.tag)
        for (engine in MediaPipeSpeechEngine.entries) {
            val model = MediaPipeSpeechCatalog.model(engine)
            assertTrue(model.defaultVoice in MediaPipeSpeechVoices.ids(engine))
            assertEquals(model.defaultVoice, MediaPipeSpeechVoices.selected(engine, "not-a-voice"))
            assertEquals(MediaPipeSpeechVoices.ids(engine).distinct(), MediaPipeSpeechVoices.ids(engine))
        }
    }

    @Test fun persistedVoicesCannotCrossModelFamiliesOrInventLanguages() {
        val restored = MediaPipeSpeechVoices.restore(setOf("POCKET=marius", "POCKET_MULTILINGUAL=rafael", "PHONON=claire", "KOKORO=af_heart", "unknown=alba", 7))
        assertEquals(mapOf("POCKET" to "marius", "POCKET_MULTILINGUAL" to "rafael", "PHONON" to "claire"), restored)
        assertTrue(MediaPipeSpeechVoices.restore(setOf("POCKET=rafael", "PHONON=alba", "POCKET_MULTILINGUAL=af_heart")).isEmpty())
    }

    @Test fun localeAndFallbackStayBoundToTheSelectedVoiceFamily() {
        assertEquals("en-US", SpeechLanguages.forVoice("freya")?.tag)
        assertEquals("pt-BR", SpeechLanguages.forVoice("rafael")?.tag)
        assertEquals("de-DE", SpeechLanguages.forVoice("juergen")?.tag)
        assertEquals("alba", SpeechLanguages.availableVoice("marius", setOf("alba"), true))
        assertNull(SpeechLanguages.availableVoice("marius", setOf("freya"), true))
        assertNull(SpeechLanguages.availableVoice("estelle", setOf("alba"), true))
        assertNull(SpeechLanguages.availableVoice("marius", setOf("alba"), false))
        assertEquals("ff_siwis", VoiceSynthesisPreferences().selectLanguage("fr-FR").voiceId)
    }

    @Test fun multilingualBundleHasPinnedDownloadAndNoUnsupportedDelegates() {
        val model = MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.POCKET_MULTILINGUAL)
        assertEquals(760_061_960L, model.downloadBytes)
        assertEquals("9c5427cf3fe2d6680f8268e1c06537496be52d818ef12233a461bf193f314b3c", model.sha256)
        assertTrue(model.downloadUrl.contains("/00c866f2397788f514ab6bbcb931be22afc3b58f/"))
        assertEquals(listOf(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU), VoiceSpeechBenchmarkEngine.POCKET_MULTILINGUAL.supportedBackends)
        assertTrue(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU.isStreamingLocal)
        assertFalse(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU.isMediaPipeAccelerator)
        assertTrue(MediaPipeSpeechVoices.processorAvailability(MediaPipeSpeechEngine.POCKET_MULTILINGUAL)?.contains("This Pocket pack runs on CPU") == true)
        assertTrue(MediaPipeSpeechVoices.processorAvailability(MediaPipeSpeechEngine.POCKET_MULTILINGUAL)?.contains("separate compiled English pack") == true)
    }

    @Test fun germanAndDutchReplyPoliciesUseTheSelectedLanguageWithoutChangingLegacyPrompts() {
        assertEquals(VoiceResponsePolicy.REPLY_GERMAN, VoiceResponsePolicy.forLanguage("de-DE"))
        assertEquals(VoiceResponsePolicy.TRANSLATE_DUTCH, VoiceResponsePolicy.forLanguage("nl-NL", true))
        assertTrue(VoiceResponsePolicy.forLanguage("de-DE").instruction().contains("Use German for the entire reply"))
        assertTrue(VoiceResponsePolicy.forLanguage("nl-NL", true).instruction().contains("into Dutch"))
        assertEquals(VoiceResponsePolicy.REPLY_FRENCH, VoiceResponsePolicy.forLanguage("fr-FR"))
    }
}
