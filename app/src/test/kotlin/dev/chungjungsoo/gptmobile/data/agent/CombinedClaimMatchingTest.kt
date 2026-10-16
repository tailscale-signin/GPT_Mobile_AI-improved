package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedClaimMatchingTest {
    private fun source(uid: String, text: String) = CombinedModelResponse(uid, uid, content = text)

    @Test fun reorderedParaphraseRetainsContentWithoutVerbatimWording() {
        val original = "The Athens fleet was financed by silver mines and trade."
        val revised = "Silver mines and trade funded the naval forces of Athens."
        assertEquals(CombinedClaimMatcher.Match.PARAPHRASE, CombinedClaimMatcher.match(original, revised))
        val result = CombinedContentCoverage(listOf(source("athens", original))).check(revised)
        assertTrue(result.accepted)
        assertEquals(1, result.paraphraseMatches)
        assertEquals(0, result.missing.size)
    }

    @Test fun contradictoryNumbersNegationAndUnsupportedCertaintyNeverMatch() {
        assertEquals(
            CombinedClaimMatcher.Match.NONE,
            CombinedClaimMatcher.match("The committee approved 12 homes in 1930.", "The committee approved 14 homes in 1930.")
        )
        assertEquals(
            CombinedClaimMatcher.Match.NONE,
            CombinedClaimMatcher.match("The treaty was not signed in 1910.", "The treaty was signed in 1910.")
        )
        assertEquals(
            CombinedClaimMatcher.Match.NONE,
            CombinedClaimMatcher.match("The medicine may reduce mortality in patients.", "The medicine reduces mortality in patients.")
        )
        val missing = CombinedContentCoverage(listOf(source("s1", "The committee approved 12 homes in 1930.")))
            .check("The committee approved 14 homes in 1930.")
        assertFalse(missing.accepted)
    }

    @Test fun uncertainParaphrasesRemainPendingAndCannotSilentlyComplete() {
        val original = "Ancient Athens collected silver wealth, built long walls and financed naval fleets."
        val output = "Athens collected silver riches and built civic walls."
        val result = CombinedContentCoverage(listOf(source("athens", original))).check(output)
        assertFalse(result.accepted)
        assertTrue(result.missing.any { "naval" in it.text })
    }

    @Test fun conflictingModelsCreateComparisonCandidatesNotAutomaticallyResolvedFacts() {
        val rows = listOf(
            source("a", "The council approved 12 houses in 1930."),
            source("b", "The council approved 14 houses in 1930."),
            source("c", "The council did not approve 12 houses in 1930."),
            source("d", "Poetry flourished in another ancient kingdom.")
        )
        val scan = CombinedConflictDetector.scan(rows)
        assertTrue(scan.complete)
        assertTrue(scan.candidates.any { it.leftUid == "a" && it.rightUid == "b" && it.kind == "NUMERIC" })
        assertTrue(scan.candidates.any { it.leftUid == "a" && it.rightUid == "c" && it.kind == "POLARITY" })
        assertTrue(scan.candidates.none { it.rightUid == "d" })
    }

    @Test fun attributionFollowsAllStrongParaphraseSubmittersButNotGenericOpeners() {
        val original = "The Athens fleet was financed by silver mines and trade."
        val revised = "Silver mines and trade funded the naval forces of Athens."
        val shared = listOf(source("a", original), source("b", "Trade and silver mines financed the Athenian navy."))
        val spans = combinedWordingSpans(revised, shared)
        assertEquals(1, spans.size)
        assertEquals(setOf("a", "b"), spans.single().profileUids)
        assertEquals(revised.dropLast(1), revised.substring(spans.single().start, spans.single().end))
        assertTrue(combinedWordingSpans("There is no single definitive answer.", shared).isEmpty())
    }

    @Test fun canadaCoastlineMeasurementsAreComparedAfterNormalizingThousands() {
        val first = "Canada has the world's longest coastline, about 243,042 kilometers, stretching along three oceans."
        val sameNumber = "Canada has the world's longest coastline about 243042 kilometers stretching along three oceans."
        val second = "Canada has the longest coastline in the world, measuring about 202,080 kilometers along several oceans."
        assertEquals(setOf("243042"), CombinedClaimMatcher.fingerprint(first).numbers)
        assertEquals(setOf("243042"), CombinedClaimMatcher.fingerprint(sameNumber).numbers)
        assertTrue(CombinedClaimMatcher.match(first, sameNumber) != CombinedClaimMatcher.Match.NONE)
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(first, second))
        val scan = CombinedConflictDetector.scan(listOf(source("a", first), source("b", second)))
        assertTrue(scan.candidates.any { it.kind == "NUMERIC" })
    }

}
