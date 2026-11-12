package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedClaimMatchingTest {
    private fun source(uid: String, text: String) = CombinedModelResponse(uid, uid, content = text)

    @Test fun reversedOrMissingParticipantsCannotEstablishExactOrParaphraseCoverage() {
        val original = "Northwind acquired Contoso after lengthy regulatory negotiations."
        val changes = listOf(
            "Contoso acquired Northwind after lengthy regulatory negotiations.",
            "Northwind was acquired by Contoso after lengthy regulatory negotiations.",
            "Contoso was acquired after lengthy regulatory negotiations.",
            "Northwind acquired after lengthy regulatory negotiations."
        )
        for (changed in changes) {
            assertEquals(changed, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(original, changed))
            assertEquals(changed, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(CombinedClaimMatcher.fingerprint(original), CombinedClaimMatcher.fingerprint(changed)))
            assertEquals(changed, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(CombinedClaimMatcher.prepare(original), CombinedClaimMatcher.prepare(changed)))
            assertFalse(changed, CombinedContentCoverage(listOf(source("one", original))).check(changed).accepted)
            assertTrue(changed, combinedWordingSpans(changed, listOf(source("one", original))).isEmpty())
        }
        // An unsupported alignment is not itself proof that either event never happened.
        assertFalse(CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint(original), CombinedClaimMatcher.fingerprint(changes.first())))
    }

    @Test fun activeAndPassiveWordingWithTheSameParticipantsRemainLinked() {
        val pairs = listOf(
            "Northwind acquired Contoso after lengthy regulatory negotiations." to "Contoso was acquired by Northwind after lengthy regulatory negotiations.",
            "Northwind never acquired Contoso after lengthy regulatory negotiations." to "Contoso was never acquired by Northwind after lengthy regulatory negotiations.",
            "The silver mines of Athens financed construction of naval ships." to "Silver mines funded Athenian ships.",
            "The regional council funded the village library after lengthy public negotiations." to "The village library was funded by the regional council after lengthy public negotiations."
        )
        for ((original, rewritten) in pairs) {
            assertTrue(rewritten, CombinedClaimMatcher.match(original, rewritten) >= CombinedClaimMatcher.Match.PARAPHRASE)
            assertTrue(rewritten, CombinedClaimMatcher.match(CombinedClaimMatcher.fingerprint(original), CombinedClaimMatcher.fingerprint(rewritten)) >= CombinedClaimMatcher.Match.PARAPHRASE)
            assertTrue(rewritten, CombinedClaimMatcher.match(CombinedClaimMatcher.prepare(original), CombinedClaimMatcher.prepare(rewritten)) >= CombinedClaimMatcher.Match.PARAPHRASE)
            assertTrue(rewritten, CombinedContentCoverage(listOf(source("one", original))).check(rewritten).accepted)
        }
        assertTrue(CombinedClaimMatcher.match(pairs.first().second, pairs.first().first) >= CombinedClaimMatcher.Match.PARAPHRASE)
    }

    @Test fun ambiguousRolesAndDroppedCoParticipantsRemainUnresolved() {
        val pairs = listOf(
            "Northwind never acquired Contoso after lengthy regulatory negotiations." to "Northwind was never acquired by Contoso after lengthy regulatory negotiations.",
            "Northwind did not acquire Contoso after lengthy regulatory negotiations." to "Northwind was not acquired by Contoso after lengthy regulatory negotiations.",
            "Northwind and Fabrikam acquired Contoso after lengthy regulatory negotiations." to "Northwind acquired Contoso after lengthy regulatory negotiations.",
            "Northwind acquired Contoso and Fabrikam after lengthy regulatory negotiations." to "Northwind acquired Contoso after lengthy regulatory negotiations.",
            "Northwind Corporation and Fabrikam Corporation acquired Contoso after lengthy regulatory negotiations." to "Northwind Corporation acquired Contoso after lengthy regulatory negotiations.",
            "Northwind acquired Contoso Corporation and Fabrikam Corporation after lengthy regulatory negotiations." to "Northwind acquired Contoso Corporation after lengthy regulatory negotiations.",
            "Northwind acquired Contoso after approval from the pension fund." to "Contoso acquired Northwind after approval from the pension fund.",
            "Northwind, a software company, acquired Contoso after lengthy regulatory negotiations." to "Contoso, a software company, acquired Northwind after lengthy regulatory negotiations."
        )
        for ((original, changed) in pairs) {
            assertEquals(changed, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(original, changed))
            assertEquals(changed, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(CombinedClaimMatcher.fingerprint(original), CombinedClaimMatcher.fingerprint(changed)))
            assertEquals(changed, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(CombinedClaimMatcher.prepare(original), CombinedClaimMatcher.prepare(changed)))
            assertFalse(changed, CombinedContentCoverage(listOf(source("one", original))).check(changed).accepted)
            assertEquals(CombinedClaimMatcher.Match.EXACT, CombinedClaimMatcher.match(original, original))
        }
    }

    @Test fun independentAnswerClausesPreserveValidRelationsWithoutRestoringReversedOnes() {
        val original = "Northwind acquired Contoso after lengthy regulatory negotiations."
        val suffix = " The regional council funded the village library after public consultation."
        for (answer in listOf(original + suffix, "Contoso was acquired by Northwind after lengthy regulatory negotiations." + suffix)) {
            assertTrue(answer, CombinedClaimMatcher.match(original, answer) >= CombinedClaimMatcher.Match.PARAPHRASE)
            assertTrue(answer, CombinedClaimMatcher.match(CombinedClaimMatcher.prepare(original), CombinedClaimMatcher.prepare(answer)) >= CombinedClaimMatcher.Match.PARAPHRASE)
            assertTrue(answer, CombinedContentCoverage(listOf(source("one", original))).check(answer).accepted)
        }
        for (answer in listOf("Contoso acquired Northwind after lengthy regulatory negotiations." + suffix, "Northwind was acquired by Contoso after lengthy regulatory negotiations." + suffix)) {
            assertEquals(answer, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(original, answer))
            assertEquals(answer, CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(CombinedClaimMatcher.prepare(original), CombinedClaimMatcher.prepare(answer)))
            assertFalse(answer, CombinedContentCoverage(listOf(source("one", original))).check(answer).accepted)
        }
    }

    @Test fun retainedEvidenceAndDisputedDeclarationsCannotCertifyReversedParticipants() {
        val original = "Northwind acquired Contoso after lengthy regulatory negotiations."
        val reversed = "Contoso acquired Northwind after lengthy regulatory negotiations."
        val evidence = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://example.org/transaction", passage = original, retrieval = "READABLE_PASSAGE")))
        assertEquals("CITED_UNVERIFIED", CombinedEvidenceVerifier.assess("claim", reversed, evidence, setOf("E1")).result)
        assertEquals("TEXT_SUPPORTED_WITH_LIMITS", CombinedEvidenceVerifier.assess("claim", "Contoso was acquired by Northwind after lengthy regulatory negotiations.", evidence, setOf("E1")).result)

        fun check(text: String) = CombinedFinalCheck.check(
            text,
            listOf(source("one", original)),
            evidence,
            CombinedAnswerPolicy(),
            declarations = listOf(CombinedClaimReview("C1.1", "DISPUTED", "An attributed account requires qualification.")),
            revisionId = "report-revision"
        )
        val rejected = check(reversed.removeSuffix(".") + ", as reported.")
        assertEquals("UNRESOLVED", rejected.decisions.single().disposition)
        assertTrue(rejected.checks.any { it.id.startsWith("SEMANTIC_CONTRIBUTION_UNRESOLVED:") })
        val retained = check("Contoso was acquired by Northwind after lengthy regulatory negotiations, as reported.")
        assertEquals("DISPUTED", retained.decisions.single().disposition)
        assertTrue(retained.decisions.single().finalSpans.isNotEmpty())
    }

    @Test fun conflictScanReusesClauseFingerprintsWithoutChangingDecisions() {
        val first = CombinedClaimMatcher.fingerprint("The council approved 12 houses in 1930.").copy(
            eventClauses = listOf("The council approved 12 houses in 1930.", "The committee approved 14 schools in 1931.")
        )
        val second = CombinedClaimMatcher.fingerprint("The council approved 15 houses in 1930.")
        val cache = mutableMapOf<String, CombinedClaimMatcher.Fingerprint>()
        val expected = CombinedClaimMatcher.possibleConflict(first, second)
        repeat(20) { assertEquals(expected, CombinedClaimMatcher.possibleConflict(first, second, cache)) }
        assertEquals(2, cache.size)
        assertTrue(expected)
    }

    @Test fun reorderedParaphraseRetainsContentWithoutVerbatimWording() {
        val original = "The Athens fleet was financed by silver mines and trade."
        val revised = "Silver mines and trade funded the naval forces of Athens."
        assertEquals(CombinedClaimMatcher.Match.PARAPHRASE, CombinedClaimMatcher.match(original, revised))
        val result = CombinedContentCoverage(listOf(source("athens", original))).check(revised)
        assertTrue(result.accepted)
        assertEquals(1, result.paraphraseMatches)
        assertEquals(0, result.missing.size)
    }

    @Test fun compactParaphraseCanOmitNonessentialConstructionWordingWithoutLosingAttribution() {
        val source = "The silver mines of Athens financed construction of naval ships."
        val answer = "Silver mines funded Athenian ships."
        assertEquals(CombinedClaimMatcher.Match.PARAPHRASE, CombinedClaimMatcher.match(source, answer))
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

    @Test fun contractionsSignedQuantitiesPercentagesAndBoundsCannotDisappear() {
        val pairs = listOf(
            "The treatment didn't improve patient survival after surgery." to "The treatment improved patient survival after surgery.",
            "The station recorded temperatures of -12 degrees during winter." to "The station recorded temperatures of 12 degrees during winter.",
            "The station recorded temperatures of 12.5 degrees during winter." to "The station recorded temperatures of 12,5 degrees during winter.",
            "The trial reported recovery in 12% of patients after surgery." to "The trial reported recovery in 12 of the patients after surgery.",
            "The council approved at least 12 houses near the river." to "The council approved at most 12 houses near the river.",
            "The council approved 12 houses and 14 schools near the river." to "The council approved 14 houses and 12 schools near the river."
        )
        pairs.forEach { (original, changed) ->
            assertFalse("Changed claim was accepted: $changed", CombinedContentCoverage(listOf(source("one", original))).check(changed).accepted)
            assertTrue("Changed claim was coloured: $changed", combinedWordingSpans(changed, listOf(source("one", original))).isEmpty())
        }
    }

    @Test fun exactSubstringCannotBypassContradictionGuards() {
        val original = "The council approved 12 houses near the river."
        val denied = "It is not true that the council approved 12 houses near the river."
        assertFalse(CombinedContentCoverage(listOf(source("one", original))).check(denied).accepted)
    }

    @Test fun unrelatedSentencesCannotPoolTermsToInventCoverage() {
        val original = "The council approved 12 houses near the river."
        val changed = "The council approved 14 houses near the river. The mayor rejected 12 warehouses."
        assertFalse(CombinedContentCoverage(listOf(source("one", original))).check(changed).accepted)
    }

    @Test fun numberFormattingNeverDeduplicatesDifferentQuantities() {
        val rows = listOf(
            source("positive", "The station recorded 12 degrees during winter."),
            source("negative", "The station recorded -12 degrees during winter.")
        )
        val coverage = CombinedContentCoverage(rows)
        assertEquals(2, coverage.units.size)
        assertFalse(coverage.check(rows.first().content).accepted)
    }

    @Test fun sourceColourDoesNotClaimAnUnsupportedExtension() {
        val original = "The council approved new houses near the river."
        val extended = "The council approved new houses near the river and discovered alien spacecraft underground."
        val spans = combinedWordingSpans(extended, listOf(source("one", original)))
        assertTrue(spans.all { extended.substring(it.start, it.end) == original.removePrefix("The ").dropLast(1) })
    }

    @Test fun markupDoesNotJoinHeadingsOrSeparateListItemsIntoClaims() {
        val rows = listOf(source("one", "## Local history\n\nThe council approved new houses near the river.\n\n- Solar panels power the local hospital\n- Electric buses serve the nearby villages"))
        val answer = "The council approved new houses near the river. Solar panels power the local hospital. Electric buses serve the nearby villages."
        assertTrue(CombinedContentCoverage(rows).check(answer).accepted)
    }

    @Test fun interruptedResponseNotesAreNeverRequiredOrColouredAsFacts() {
        val fact = "The council approved new houses near the river."
        val error = "Connection lost while receiving the final response."
        val rows = listOf(source("one", "$fact\n\n[Response stopped: $error]"))
        assertTrue(CombinedContentCoverage(rows).check(fact).accepted)
        assertTrue(combinedWordingSpans(error, rows).isEmpty())
    }

    @Test fun softLineWrapsKeepTheWholeSourceClaim() {
        val original = "The council approved new houses\nnear the river in the northern district."
        val answer = "The council approved new houses near the river in the northern district."
        assertEquals(1, CombinedContentCoverage(listOf(source("one", original))).units.size)
        assertTrue(CombinedContentCoverage(listOf(source("one", original))).check(answer).accepted)
    }
}
