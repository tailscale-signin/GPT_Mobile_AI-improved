package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetFingerprintTest {
    private val policy = SearchMergePolicy(contentMode = SearchContentMode.ENABLED)
    private val title = "Canadian transit service expansion"
    private val text = "The city council approved a new transit service expansion with frequent buses and reliable connections across all neighbourhoods beginning this autumn."
    private fun fingerprint(text: String = this.text, title: String = this.title, date: String? = null) = SnippetFingerprint.create(text, title, date)

    @Test fun unicodeCaseAndMarkupCopiesMatch() {
        assertNotNull(fingerprint().match(fingerprint("<p>${text.uppercase()}</p>"), policy))
        val french = "Le conseil municipal a approuvé une extension du service de transport avec des autobus fréquents et des connexions fiables dans tous les quartiers."
        assertNotNull(fingerprint(french, "Extension du transport municipal").match(fingerprint(french.uppercase(), "Extension du transport municipal"), policy))
        assertEquals(fingerprint("café").tokens, fingerprint("cafe\u0301").tokens)
    }

    @Test fun shortEmptyAndGenericEvidenceDoesNotMerge() {
        for (value in listOf("", "Visit our website to learn more", "hello ".repeat(30))) assertNull(fingerprint(value).match(fingerprint(value), policy))
        assertNull(fingerprint(title = "About our company").match(fingerprint(title = "About our company"), policy))
        assertNull(fingerprint(title = "https://example.org/same").match(fingerprint(title = "https://example.org/same"), policy))
        assertNull(fingerprint(title = "").match(fingerprint(title = ""), policy))
    }

    @Test fun conflictingFactsTitlesDatesAndNegationRemainSeparate() {
        for ((first, second) in listOf(
            "$text Version 1.2.3." to "$text Version 1.2.4.",
            "$text Fare 10 CAD." to "$text Fare 12 CAD.",
            "$text Service is available." to "$text Service is not available.",
            "$text Product AX100." to "$text Product AX101."
        )) {
            assertNull(fingerprint(first).match(fingerprint(second), policy))
        }
        assertNull(fingerprint().match(fingerprint(title = "Unrelated medical study findings"), policy))
        assertNull(fingerprint(date = "2026-10-08").match(fingerprint(date = "2026-10-09"), policy))
    }

    @Test fun scriptsWithoutWordBoundariesDoNotSuppressSources() {
        val words = "漢字による検索結果と地域交通の最新ニュース ".repeat(15)
        assertNull(fingerprint(words).match(fingerprint(words), policy))
    }

    @Test fun matchingIncludesTheThresholdAndRejectsTheValueBelowIt() {
        val first = fingerprint().copy(shingles = (1..17).map(Int::toString).toSet())
        val boundary = fingerprint().copy(shingles = (1..14).map(Int::toString).toSet() + (18..20).map(Int::toString))
        val below = fingerprint().copy(shingles = (1..13).map(Int::toString).toSet() + (18..21).map(Int::toString))
        assertEquals(0.70, first.match(boundary, policy)!!, 0.0)
        assertNull(first.match(below, policy))
    }

    @Test fun thresholdIsInclusiveAndFingerprintWorkIsBounded() {
        assertEquals(0.70, SnippetFingerprint.jaccard((1..7).map { it.toString() }.toSet(), (1..10).map { it.toString() }.toSet()), 0.0)
        val bounded = fingerprint("word ".repeat(1000))
        assertTrue(bounded.truncated)
        assertTrue(bounded.tokens.size <= 256)
    }
}
