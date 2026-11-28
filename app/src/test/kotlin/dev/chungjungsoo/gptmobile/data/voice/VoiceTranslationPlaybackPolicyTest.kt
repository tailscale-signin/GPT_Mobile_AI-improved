package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTranslationPlaybackPolicyTest {
    @Test fun acceptedFrenchUsesEstelleAndAcceptedEnglishUsesAzelma() {
        val pair = VoiceTranslationPair()
        val french = requireNotNull(translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_FRENCH, pair))
        val english = requireNotNull(translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_AMERICAN_ENGLISH, pair))
        assertEquals("fr-FR", french.targetLanguage)
        assertEquals("estelle", french.voiceId)
        assertEquals("en-US", english.targetLanguage)
        assertEquals("azelma", english.voiceId)
    }

    @Test fun replayUsesAcceptedDestinationEvenWhenManualSourceHasChanged() {
        val manualEnglish = VoiceTranslationPair(sourceLanguage = "en-US")
        assertEquals("fr-FR", manualEnglish.resolve(null)?.targetLanguage)
        val replay = requireNotNull(translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_AMERICAN_ENGLISH, manualEnglish))
        assertEquals("en-US", replay.targetLanguage)
        assertEquals("azelma", replay.voiceId)
    }

    @Test fun eachLanguageRetainsItsOwnVoice() {
        val pair = VoiceTranslationPair(baseVoiceId = "marius")
        assertEquals("marius", translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_BRITISH_ENGLISH, pair)?.voiceId)
        assertEquals("estelle", translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_FRENCH, pair)?.voiceId)
    }

    @Test fun unrelatedRepliesAndInvalidPairsCannotAcquireTranslationVoices() {
        assertNull(translationPlaybackTarget(VoiceResponsePolicy.REPLY_FRENCH, VoiceTranslationPair()))
        assertNull(translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_GERMAN, VoiceTranslationPair()))
        assertNull(translationPlaybackTarget(VoiceResponsePolicy.TRANSLATE_FRENCH, VoiceTranslationPair(baseVoiceId = "estelle")))
        assertNull(translationPlaybackTarget(null, VoiceTranslationPair()))
    }
}
