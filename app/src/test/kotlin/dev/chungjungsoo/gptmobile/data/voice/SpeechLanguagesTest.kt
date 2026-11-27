package dev.chungjungsoo.gptmobile.data.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLanguagesTest {
    @Test fun catalogHasNineLanguageProfilesAndDistinctVoiceIds() {
        val profiles = SpeechLanguages.synthesis
        assertEquals(9, profiles.size)
        val voices = profiles.flatMap { it.voices }
        assertEquals(54, voices.size)
        assertEquals(voices.size, voices.distinct().size)
        assertTrue(profiles.all { it.preview.isNotBlank() })
    }

    @Test fun everyEnglishStreamingVoiceHasItsNativeSpeakerAndDictionary() {
        val english = SpeechLanguages.synthesis.filter { it.tag.startsWith("en-") }
        assertEquals(english.flatMap { it.voices }.toSet(), KokoroVoice.entries.map { it.streamingVoiceId }.toSet())
        assertEquals((0..27).toSet(), KokoroVoice.entries.map { it.speakerId }.toSet())
        for (profile in english) {
            for (id in profile.voices) {
                val voice = requireNotNull(KokoroVoice.fromStreamingId(id))
                assertEquals(if (profile.tag == "en-US") KokoroModelPack.US_LEXICON else KokoroModelPack.GB_LEXICON, voice.lexicon)
                assertEquals(voice, KokoroVoice.fromSaved(voice.name))
            }
        }
        assertEquals(3, KokoroVoice.HEART.speakerId)
        assertEquals(11, KokoroVoice.ADAM.speakerId)
        assertEquals(21, KokoroVoice.EMMA.speakerId)
        assertEquals(27, KokoroVoice.LEWIS.speakerId)
    }

    @Test fun freshInstallAndLegacyEnglishVoicesKeepAnOfflineDefault() {
        assertEquals("ja-JP", SpeechLanguages.recognitionDefault(Locale.JAPAN))
        assertEquals("ff_siwis", VoiceSynthesisPreferences.restore(null, null, null, "fr-CA").voiceId)
        assertEquals("en-CA", SpeechLanguages.recognitionDefault(Locale.CANADA))
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, SpeechLanguages.recognitionBackend("ja-JP"))
        assertEquals(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE, SpeechLanguages.recognitionBackend("en-CA", VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE))
        assertEquals(VoiceRecognitionBackend.LOCAL_MOONSHINE, SpeechLanguages.recognitionBackend("en-US"))
        val english = VoiceSynthesisPreferences.restore(null, null, "bf_emma")
        assertEquals("en-GB", english.language)
        assertEquals("bf_emma", english.voiceId)
    }

    @Test fun languageFrontendsFollowTheActualVoiceForEveryUtterance() {
        for (profile in SpeechLanguages.synthesis) {
            for (id in profile.voices) {
                val options = streamSynthesisOptions(id)
                assertEquals(id, options.voiceId)
                assertEquals(profile.tag, options.locale)
            }
        }
        assertEquals("fr", VoiceSynthesisPreferences().selectLanguage("fr-CA").profile.tag)
        assertEquals("en-US", streamSynthesisOptions("freya").locale)
        assertEquals("fr-FR", streamSynthesisOptions("estelle").locale)
        assertEquals("pt-BR", streamSynthesisOptions("rafael").locale)
        assertTrue(VoiceResponsePolicy.forLanguage("fr").instruction().contains("Use French"))
    }

    @Test fun unavailableVoicesCanOnlyFallBackWithinTheRequestedLanguage() {
        val available = setOf("jf_alpha", "af_heart")
        assertEquals("jf_alpha", SpeechLanguages.availableVoice("jm_kumo", available, true))
        assertEquals(null, SpeechLanguages.availableVoice("jm_kumo", available, false))
        assertEquals(null, SpeechLanguages.availableVoice("ff_siwis", available, true))
    }

    @Test fun switchingLanguagesRemembersEachVoiceAndRepairsUnknownIds() {
        val japanese = VoiceSynthesisPreferences().selectLanguage("ja").selectVoice("jm_kumo")
        val spanish = japanese.selectLanguage("es").selectVoice("em_alex")
        assertEquals("em_alex", spanish.selectVoice("jf_alpha").voiceId)
        assertEquals("jm_kumo", spanish.selectLanguage("ja").voiceId)
        val restored = VoiceSynthesisPreferences.restore("ja", setOf("ja=invalid", "es=em_alex", "other=em_alex", 5), null)
        assertEquals("jf_alpha", restored.voiceId)
        assertEquals("em_alex", restored.selectLanguage("es").voiceId)
    }
}
