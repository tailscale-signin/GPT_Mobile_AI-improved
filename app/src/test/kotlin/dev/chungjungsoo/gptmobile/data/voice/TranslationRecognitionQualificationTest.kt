package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationRecognitionQualificationTest {
    private val pair = listOf("en-US", "fr-FR")

    @Test fun bothExactOfflineLocalesMustBeInstalled() {
        assertEquals(RecognitionSupportStatus.SUPPORTED, qualifyTranslationRecognitionLanguages(pair, true, listOf("en-US", "fr-fr"), emptyList(), emptyList()).status)
        assertEquals(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, qualifyTranslationRecognitionLanguages(pair, true, listOf("en-US"), listOf("fr-FR"), emptyList()).status)
        assertEquals(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, qualifyTranslationRecognitionLanguages(pair, true, listOf("en-US"), emptyList(), listOf("fr-FR")).status)
        assertEquals(RecognitionSupportStatus.UNSUPPORTED, qualifyTranslationRecognitionLanguages(pair, true, listOf("en-US"), emptyList(), emptyList()).status)
    }

    @Test fun anotherRegionAndOnlineRecognitionNeverQualifyThePair() {
        assertEquals(RecognitionSupportStatus.UNSUPPORTED, qualifyTranslationRecognitionLanguages(pair, true, listOf("en-US", "fr-CA"), emptyList(), emptyList()).status)
        assertEquals(RecognitionSupportStatus.UNSUPPORTED, qualifyTranslationRecognitionLanguages(pair, false, pair, emptyList(), emptyList()).status)
    }

    @Test fun malformedSameLanguageAndIncompletePairsCannotClaimAutomaticSupport() {
        for (invalid in listOf(emptyList(), listOf("en-US"), listOf("en-US", "en-GB"), listOf("en_US", "fr-FR"), listOf("und", "fr-FR"))) {
            assertEquals(RecognitionSupportStatus.UNSUPPORTED, qualifyTranslationRecognitionLanguages(invalid, true, pair, emptyList(), emptyList()).status)
        }
        assertEquals(pair, translationRecognitionLanguages(listOf("en-us", "fr-fr")))
        assertEquals(emptyList<String>(), translationRecognitionLanguages(emptyList()))
        assertNull(translationRecognitionLanguages(listOf("en-US", "en-GB")))
    }

    @Test fun supportIdentityIncludesTheEntirePairAndTransportMode() {
        val initial = RecognitionRequestKey("en-US", pair, true)
        val changed = listOf(
            RecognitionRequestKey("en-US", listOf("en-US", "fr-CA"), true),
            RecognitionRequestKey("en-US", emptyList(), true),
            RecognitionRequestKey("en-US", pair, false)
        )
        assertEquals(4, (changed + initial).distinct().size)
    }
}
