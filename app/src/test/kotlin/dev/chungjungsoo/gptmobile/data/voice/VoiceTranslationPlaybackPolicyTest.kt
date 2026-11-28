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

    @Test fun acceptedTranslationSelectionUsesTheSavedDestinationAndItsVoice() {
        val pair = VoiceTranslationPair(sourceLanguage = "en-US", baseVoiceId = "marius")
        val french = selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_FRENCH, pair)
        val english = selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_BRITISH_ENGLISH, pair)
        assertEquals(VoiceTranslationPlaybackSelection.Translated(VoiceTranslationTarget("en-US", "fr-FR", "estelle")), french)
        assertEquals(VoiceTranslationPlaybackSelection.Translated(VoiceTranslationTarget("fr-FR", "en-US", "marius")), english)
    }

    @Test fun unresolvedMetadataCannotClearAnAcceptedVoiceAndKnownOrdinaryRepliesCannotInheritIt() {
        val pair = VoiceTranslationPair()
        val retained = requireNotNull(pair.resolve("en"))
        assertEquals(VoiceTranslationPlaybackSelection.MissingPolicy, selectTranslationPlayback(null, pair, retained))
        for (policy in listOf(VoiceResponsePolicy.CONVERSATIONAL, VoiceResponsePolicy.RESPONSIVE, VoiceResponsePolicy.REPLY_FRENCH)) {
            assertEquals(VoiceTranslationPlaybackSelection.Ordinary, selectTranslationPlayback(policy, pair, retained))
        }
    }

    @Test fun anOwnedValidatedTargetKeepsItsExactVoiceAfterPreferencesChange() {
        val original = VoiceTranslationPair(baseVoiceId = "marius")
        val retainedEnglish = requireNotNull(original.resolve("fr"))
        val current = VoiceTranslationPair()
        assertEquals(
            VoiceTranslationPlaybackSelection.Translated(retainedEnglish),
            selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_BRITISH_ENGLISH, current, retainedEnglish)
        )

        val retainedFrench = requireNotNull(original.resolve("en"))
        val changedPair = VoiceTranslationPair(translationLanguage = "es-ES", translationVoiceId = "lola")
        assertEquals(
            VoiceTranslationPlaybackSelection.Translated(retainedFrench),
            selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_FRENCH, changedPair, retainedFrench)
        )
        assertEquals(VoiceTranslationPlaybackSelection.IncompatiblePair, selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_FRENCH, changedPair))
    }

    @Test fun unrelatedOrInvalidRetainedTargetsNeverSelectTheWrongVoice() {
        val pair = VoiceTranslationPair()
        val invalidTargets = listOf(
            VoiceTranslationTarget("en-US", "fr-FR", "estelle"),
            VoiceTranslationTarget("fr-FR", "en-US", "estelle"),
            VoiceTranslationTarget("fr-FR", "en-US", "missing-voice"),
            VoiceTranslationTarget("en-US", "en-US", "marius"),
            VoiceTranslationTarget("unknown", "en-US", "marius")
        )
        val expected = VoiceTranslationPlaybackSelection.Translated(VoiceTranslationTarget("fr-FR", "en-US", "azelma"))
        for (retained in invalidTargets) {
            assertEquals(expected, selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_AMERICAN_ENGLISH, pair, retained))
        }
    }

    @Test fun unsupportedAcceptedLanguagesAndInvalidPairsBlockInsteadOfSelectingOrdinarySpeech() {
        assertEquals(VoiceTranslationPlaybackSelection.IncompatiblePair, selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_GERMAN, VoiceTranslationPair()))
        assertEquals(VoiceTranslationPlaybackSelection.IncompatiblePair, selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_HINDI, VoiceTranslationPair()))
        assertEquals(
            VoiceTranslationPlaybackSelection.IncompatiblePair,
            selectTranslationPlayback(VoiceResponsePolicy.TRANSLATE_FRENCH, VoiceTranslationPair(baseVoiceId = "estelle"))
        )
    }
}
