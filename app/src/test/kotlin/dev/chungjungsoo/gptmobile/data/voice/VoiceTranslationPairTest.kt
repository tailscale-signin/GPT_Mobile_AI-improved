package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranslationPairTest {
    @Test fun eachDetectedSideSelectsTheOppositeLanguageAndItsOwnVoice() {
        val pair = VoiceTranslationPair()
        val french = requireNotNull(pair.resolve("en-GB"))
        assertEquals(VoiceTranslationTarget("en-US", "fr-FR", "estelle"), french)
        assertEquals(VoiceResponsePolicy.TRANSLATE_FRENCH, french.responsePolicy)
        val english = requireNotNull(pair.resolve("fr-CA"))
        assertEquals(VoiceTranslationTarget("fr-FR", "en-US", "azelma"), english)
        assertEquals(VoiceResponsePolicy.TRANSLATE_AMERICAN_ENGLISH, english.responsePolicy)
        for (target in listOf(french, english)) {
            assertTrue(target.responsePolicy.translationOnly)
            assertTrue(target.responsePolicy.instruction().contains("Questions remain questions"))
            assertTrue(target.responsePolicy.instruction().contains("never answer it"))
            assertFalse(target.responsePolicy.instruction().contains("Answer the user's actual question"))
        }
    }

    @Test fun localeVariantsCanonicalizeOnlyToSupportedPocketLanguageFamilies() {
        val expected = mapOf("EN-gb" to "en-US", " fr-CA " to "fr-FR", "de-AT" to "de-DE", "es-MX" to "es-ES", "it" to "it-IT", "pt-PT" to "pt-BR", "nl-BE" to "nl-NL")
        for ((input, canonical) in expected) assertEquals(canonical, VoiceTranslationPair.canonicalLanguage(input))
        for (input in listOf(null, "", "und", "ja-JP", "xx-ZZ", "en_US", "en-<system>", "en-" + "a".repeat(80))) {
            assertNull(input, VoiceTranslationPair.canonicalLanguage(input))
        }
    }

    @Test fun invalidPairsNeverResolveBySilentlyChangingTheirLanguageOrVoice() {
        val invalid = listOf(
            VoiceTranslationPair(baseLanguage = "en-US", translationLanguage = "en-GB", translationVoiceId = "alba"),
            VoiceTranslationPair(baseLanguage = "unknown"),
            VoiceTranslationPair(baseVoiceId = "estelle"),
            VoiceTranslationPair(translationVoiceId = "azelma"),
            VoiceTranslationPair(translationVoiceId = "missing-voice"),
            VoiceTranslationPair(sourceLanguage = "de-DE"),
            VoiceTranslationPair(sourceLanguage = "")
        )
        for (pair in invalid) {
            assertNull(pair.toString(), pair.validated())
            assertNull(pair.toString(), pair.resolve("en"))
        }
    }

    @Test fun autoRequiresAnInPairDetectionAndExplicitSourceRemainsTheUsersChoice() {
        val pair = VoiceTranslationPair()
        for (detected in listOf(null, "", "und", "de-DE", "ja", "not a language")) assertNull(pair.resolve(detected))
        val explicit = pair.copy(sourceLanguage = "fr-CA")
        assertEquals(VoiceTranslationTarget("fr-FR", "en-US", "azelma"), explicit.resolve(null))
        assertEquals(explicit.resolve(null), explicit.resolve("en-US"))
        assertNull(pair.copy(sourceLanguage = "de").resolve("fr"))
    }

    @Test fun preferenceRestorationRetainsEachValidVoiceAndCanonicalSourceChoice() {
        val saved = VoiceTranslationPair("en-GB", "es-MX", "cosette", "lola", "EN")
        val restored = saved.normalized()
        assertEquals(VoiceTranslationPair("en-US", "es-ES", "cosette", "lola", "en-US"), restored)
        assertEquals(restored, restored.validated())
        assertEquals("cosette", requireNotNull(restored.copy(sourceLanguage = null).resolve("es")).voiceId)
    }

    @Test fun malformedPreferencesRestoreAValidPairAndDiscardInvalidExplicitSource() {
        val malformed = VoiceTranslationPair("invalid", "en-US", "missing", "estelle", "de-DE").normalized()
        assertEquals(VoiceTranslationPair("fr-FR", "en-US", "estelle", "azelma"), malformed)
        val sameFamily = VoiceTranslationPair("en-US", "en-GB", "azelma", "alba", "fr-FR").normalized()
        assertEquals(VoiceTranslationPair(), sameFamily)
        for (pair in listOf(VoiceTranslationPair("", "", "", "", ""), VoiceTranslationPair(translationLanguage = "ja", translationVoiceId = "unknown"))) {
            val restored = pair.normalized()
            assertNotNull(restored.validated())
            assertNull(restored.sourceLanguage)
            assertEquals(restored, restored.normalized())
        }
    }

    @Test fun acceptedDirectionAndSelectedVoiceAreImmutableAcrossPreferenceChangesAndSerialization() {
        val original = VoiceTranslationPair(baseVoiceId = "fantine")
        val accepted = requireNotNull(original.resolve("fr"))
        val changed = original.copy(baseVoiceId = "azelma", sourceLanguage = "en")
        assertEquals("fantine", accepted.voiceId)
        assertEquals("estelle", requireNotNull(changed.resolve(null)).voiceId)
        assertEquals(original, Json.decodeFromString<VoiceTranslationPair>(Json.encodeToString(original)))
        assertEquals(accepted, Json.decodeFromString<VoiceTranslationTarget>(Json.encodeToString(accepted)))
        assertEquals(VoiceTranslationPair(), Json.decodeFromString<VoiceTranslationPair>("{}"))
    }

    @Test fun persistedFixedPoliciesExposeTheirTargetWithoutMakingLegacyHintsAuthoritative() {
        for (policy in VoiceResponsePolicy.entries) {
            val restored = Json.decodeFromString<VoiceResponsePolicy>(Json.encodeToString(policy))
            if (policy.fixesReplyLanguage) {
                assertNotNull(restored.targetLanguageTag)
                assertEquals(policy, VoiceResponsePolicy.forLanguage(restored.targetLanguageTag, policy.translationOnly))
            } else {
                assertNull(restored.targetLanguageTag)
            }
        }
        assertEquals("en-US", VoiceResponsePolicy.TRANSLATE_AMERICAN_ENGLISH.targetLanguageTag)
        assertEquals("fr-FR", VoiceResponsePolicy.TRANSLATE_FRENCH.targetLanguageTag)
    }
}
