package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackSelectionTest {
    @Test fun retainedFrenchOutputSurvivesDefaultAndRestoredOrdinarySettingsTogether() {
        val translated = factory("multilingual-estelle")
        val retained = VoiceTranslationPlayback(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), translated)
        val ordinaryStates = listOf(
            factory("initial-default-pocket") to requireNotNull(MediaPipeSpeechVoices.forVoice("alba")),
            factory("restored-pocket-marius") to requireNotNull(MediaPipeSpeechVoices.forVoice("marius")),
            factory("changed-ordinary-spanish") to requireNotNull(MediaPipeSpeechVoices.forVoice("lola"))
        )
        for ((ordinaryFactory, ordinaryProfile) in ordinaryStates) {
            val selected = selectVoicePlayback(ordinaryFactory, ordinaryProfile, retained)
            assertSame(translated, selected.factory)
            assertEquals("fr-FR", selected.profile.tag)
            assertTrue(selected.requireLocalOutput)
        }
    }

    @Test fun explicitOwnerClearingRestoresAllOrdinaryOutputSettings() {
        val ordinary = factory("ordinary-pocket")
        val profile = requireNotNull(MediaPipeSpeechVoices.forVoice("marius"))
        val accepted = VoiceTranslationPlayback(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), factory("multilingual-estelle"))
        assertTrue(selectVoicePlayback(ordinary, profile, accepted).requireLocalOutput)
        val cleared = selectVoicePlayback(ordinary, profile, null)
        assertSame(ordinary, cleared.factory)
        assertSame(profile, cleared.profile)
        assertFalse(cleared.requireLocalOutput)
    }

    @Test fun retainedEnglishVoiceDoesNotFollowTheFutureOrdinaryFrenchSelection() {
        val acceptedFactory = factory("multilingual-eponine")
        val accepted = VoiceTranslationPlayback(VoiceTranslationTarget("fr-FR", "en-US", "eponine"), acceptedFactory)
        val selected = selectVoicePlayback(factory("ordinary-french"), requireNotNull(MediaPipeSpeechVoices.forVoice("estelle")), accepted)
        assertSame(acceptedFactory, selected.factory)
        assertEquals("eponine", accepted.target.voiceId)
        assertEquals("en-US", selected.profile.tag)
        assertTrue(selected.requireLocalOutput)
    }

    private fun factory(name: String) = object : VoicePhraseEngineFactory {
        override val identity = name
        override fun create(directory: File, lexicon: String, threads: Int): VoicePhraseEngine = error("Selection must not open a speech engine")
    }
}
