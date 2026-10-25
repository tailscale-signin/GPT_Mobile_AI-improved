package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review regressions, including the exact public-history excerpts recovered from the export. */
class CombinedExportReviewRegressionTest {
    @Test fun compressedOpeningAccountsForAncientAndMedievalPeriods() {
        val opening = "Portugal's ancient Lusitanians experienced Roman rule, Visigoths and Moorish occupation before the kingdom emerged in 1139."
        assertEquals(setOf(0, 1), CombinedHistoryOutline.periods(opening))
        assertEquals(setOf(0, 1), CombinedHistoryOutline.periods("Roman settlement began in 44 BC, before Portugal became a kingdom in 1139."))
        assertEquals(setOf(1), CombinedHistoryOutline.periods("After Rome collapsed, medieval France developed feudal institutions."))
        assertEquals(setOf(4), CombinedHistoryOutline.periods("Portugal's revolution established a republic in 1910."))
        assertEquals(setOf(3), CombinedHistoryOutline.periods("Napoleon became emperor of France in 1804."))
    }

    @Test fun completedShortAnswerRequiresEditingRatherThanRecordResume() = runTest {
        val answer = "Portugal's ancient Lusitanians experienced Roman rule before its medieval kingdom emerged in 1139."
        val sources = listOf(CombinedModelResponse("p", "Profile", content = answer))
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), listOf("p"), mapOf("p" to "model"), "History of Portugal in 200 words", singlePass = true)
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(answer), ProviderEvent.Completed)
        }
        val session = CombinedDeliverySession(provider, sources, 20000, validateAccess = {}, originalRequest = requested.userPrompt, combined = CombinedExecutionContext(requested, rootBudgetId = "review-short")) { _, _ -> error("No hidden rewrite") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val report = events.filterIsInstance<ProviderEvent.CombinedQuality>().last().report
        assertTrue(report.protocolComplete)
        assertFalse(report.complete)
        assertEquals("UNMET", report.lengthCompliance)
        assertFalse(report.issues.any { it.contains("Origins and early societies") })
        val failure = CombinedRecoveryFailure.from(events.last() as ProviderEvent.Failed)
        assertEquals(CombinedRecoveryCategory.REQUIREMENTS_UNMET, failure.category)
        assertEquals("REVIEW_COMPLETED_ANSWER", failure.decision)
        assertEquals("COMPLETE_NEEDS_CORRECTION", failure.deliveryStatus)
        assertFalse(failure.rotateWriter)
    }

    @Test fun transportCompletionDoesNotMeanRecordCompletion() {
        val limited = ProviderEvent.Failed("COMBINED_PARTIAL: output limit", transportComplete = true)
        assertEquals(CombinedRecoveryCategory.INCOMPLETE_RECORD, CombinedRecoveryFailure.from(limited).category)
        assertEquals(CombinedRecoveryCategory.MALFORMED_RECORD, CombinedRecoveryFailure.fromCode("OUTPUT_PROTOCOL_INVALID").category)
        assertEquals(CombinedRecoveryCategory.NETWORK_TRANSIENT, CombinedRecoveryFailure.fromCode("NETWORK_ERROR").category)
    }

    @Test fun compatibleMembershipDatesRemainAssociatedWithTheirEvents() {
        val first = "Portugal joined NATO in 1949 and the EEC in 1986."
        val second = "Portugal joined the EEC in 1986 and adopted the euro in 1999."
        assertFalse(CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint(first), CombinedClaimMatcher.fingerprint(second)))
        assertTrue(CombinedClaimMatcher.match("Portugal joined the EEC in 1986.", first) >= CombinedClaimMatcher.Match.PARAPHRASE)
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match("Portugal joined the EEC in 1986.", "Portugal joined NATO in 1986 and the EEC in 1949."))
        assertTrue(CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint("Portugal joined the EEC in 1979."), CombinedClaimMatcher.fingerprint(second)))
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match("The town built 12 homes and 14 schools.", "The town built 14 homes and 12 schools."))
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(first, "Portugal joined the EEC in 1949 and NATO in 1986."))
    }

    @Test fun quantitiesRemainBoundToTheirMeasurementUnits() {
        val source = "The sample contains 12 mg of iron and 14 g of sodium."
        val changed = "The sample contains 12 g of iron and 14 mg of sodium."
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(source, changed))
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match("The sample contains 12mg of iron.", "The sample contains 14mg of iron."))
        val mapping = CombinedAnswerMappings.get(changed, listOf(CombinedModelResponse("p", "Profile", content = source)))
        assertFalse(mapping.claims.single().fullyLinked)
    }

    @Test fun oneMappingDrivesCompressedAttributionCoverageAndClaimReview() {
        val sources = listOf(CombinedModelResponse("ovh", "OVHcloud", content = "France adopted the Paris Agreement in 2015. France experienced Yellow Vests protests in 2018."))
        val answer = "France adopted the Paris Agreement in 2015, and France experienced Yellow Vests protests in 2018."
        val mapping = CombinedAnswerMappings.get(answer, sources)
        assertTrue(mapping.evaluated)
        assertTrue(mapping.claims.all { it.fullyLinked })
        assertEquals(2, mapping.claims.size)
        assertTrue(mapping.links.all { it.matches(answer) && it.profileUids == setOf("ovh") })
        assertTrue(mapping.links.any { "Paris Agreement" in answer.substring(it.start, it.end) })
        assertTrue(mapping.links.any { "Yellow Vests" in answer.substring(it.start, it.end) })
        assertTrue(CombinedContentCoverage(sources).check(answer).missing.isEmpty())
        assertEquals(mapping.links.map { CombinedWordingSpan(it.start, it.end, it.profileUids) }, combinedWordingSpans(answer, sources))
        val assessment = CombinedFinalCheck.check(answer, sources, CombinedEvidenceLedger(), CombinedAnswerPolicy.from("History of France"))
        assertTrue(assessment.decisions.all { it.disposition == "INCLUDED" })
        assertFalse(assessment.receipts.any { it.evidenceIds.isNotEmpty() })
    }

    @Test fun originalParentheticalEventListKeepsPartialSourceLinks() {
        // Exact source/final wording from the France example. Profile identifiers and
        // unrelated retained records are intentionally absent from this regression.
        val source = "France has also been at the forefront of global issues, such as climate change, with the Paris Agreement in 2015 being a notable achievement. However, the country has faced social unrest, including the Yellow Vests protests in 2018, which highlighted economic disparities and dissatisfaction with government policies."
        val answer = "Recently, France has seen leadership from Sarkozy, Hollande, and Macron, the Paris Agreement (2015), and Yellow Vests protests (2018)."
        for (extraction in listOf(2, 3)) {
            val sources = listOf(CombinedModelResponse("history", "History", content = source, claimExtractionVersion = extraction))
            val mapping = CombinedAnswerMappings.get(answer, sources)
            assertTrue(mapping.evaluated)
            assertTrue(mapping.claims.all { it.status == "PARTIAL" })
            assertTrue(mapping.links.all { it.matches(answer) && it.profileUids == setOf("history") })
            assertEquals(listOf("Paris Agreement (2015", "Yellow Vests protests (2018"), mapping.links.map { answer.substring(it.start, it.end) })
            assertTrue(mapping.links.none { "Macron" in answer.substring(it.start, it.end) })
            assertEquals(mapping.links.map { CombinedWordingSpan(it.start, it.end, it.profileUids) }, combinedWordingSpans(answer, sources))
            val assessment = CombinedFinalCheck.check(answer, sources, CombinedEvidenceLedger(), CombinedAnswerPolicy.from("History of France"))
            assertTrue(assessment.decisions.all { it.disposition == "PARTIALLY_REPRESENTED" })
        }
    }

    @Test fun parentheticalEventLinksDoNotBorrowDatesOrEraseDenials() {
        val source = "France has also been at the forefront of global issues, such as climate change, with the Paris Agreement in 2015 being a notable achievement. However, the country has faced social unrest, including the Yellow Vests protests in 2018, which highlighted economic disparities and dissatisfaction with government policies."
        val sources = listOf(CombinedModelResponse("history", "History", content = source, claimExtractionVersion = 2))
        val swapped = "Recently, France has seen the Paris Agreement (2018), and Yellow Vests protests (2015)."
        assertTrue(CombinedAnswerMappings.get(swapped, sources).links.isEmpty())
        val denied = "France did not experience the Paris Agreement (2015), and Yellow Vests protests (2018)."
        assertTrue(CombinedAnswerMappings.get(denied, sources).links.isEmpty())
        val deniedSource = listOf(CombinedModelResponse("history", "History", content = "France did not adopt the Paris Agreement in 2015."))
        assertTrue(CombinedAnswerMappings.get("France saw events, the Paris Agreement (2015).", deniedSource).links.isEmpty())
    }

    @Test fun partialTextLinkIsNotFullClaimCoverageOrProofOfOmission() {
        val sources = listOf(CombinedModelResponse("p", "Profile", content = "Portugal joined NATO in 1949 and the EEC in 1986."))
        val answer = "Portugal joined NATO in 1949."
        val mapping = CombinedAnswerMappings.get(answer, sources)
        assertEquals("PARTIAL", mapping.claims.single().status)
        assertTrue(mapping.links.isNotEmpty())
        assertEquals(1, CombinedContentCoverage(sources).check(answer).partialMatches)
        val assessment = CombinedFinalCheck.check(answer, sources, CombinedEvidenceLedger(), CombinedAnswerPolicy.from("Explain Portugal"))
        assertEquals("PARTIALLY_REPRESENTED", assessment.decisions.single().disposition)
    }

    @Test fun adjacentQualificationIsRetainedAsOneComparableUnit() {
        val text = "The treatment improves symptoms in adults. However, the evidence remains limited."
        val sources = listOf(CombinedModelResponse("p", "Profile", content = text, claimExtractionVersion = 2))
        val mapping = CombinedAnswerMappings.get(text, sources)
        assertEquals(1, mapping.claims.size)
        assertTrue(mapping.claims.single().fullyLinked)
        assertTrue(CombinedContentCoverage(sources).check(text).accepted)
        assertFalse(CombinedAnswerMappings.get("The treatment improves symptoms in adults.", sources).claims.single().fullyLinked)
    }

    @Test fun compressedDatedObjectKeepsItsColourAndExactOriginalRange() {
        val sources = listOf(CombinedModelResponse("eec", "EEC", content = "Portugal joined the EEC in 1986."))
        val answer = "Portugal joined NATO in 1949 and the EEC in 1986."
        val mapping = CombinedAnswerMappings.get(answer, sources)
        assertTrue(mapping.claims.single().fullyLinked)
        assertTrue(mapping.links.any { answer.substring(it.start, it.end) == "the EEC in 1986" && it.profileUids == setOf("eec") })
        assertTrue(mapping.links.all { it.matches(answer) })
    }

    @Test fun coordinatedNamedSubjectsDoNotInheritThePreviousSubject() {
        val source = "Spain joined the EEC in 1986."
        val answer = "Portugal joined NATO in 1949 and Spain joined the EEC in 1986."
        val clauses = CombinedClaimClauses.spans(answer)
        assertEquals("Spain joined the EEC in 1986", clauses.last().comparisonText)
        val mapping = CombinedAnswerMappings.get(answer, listOf(CombinedModelResponse("spain", "Spain", content = source)))
        assertTrue(mapping.claims.single().fullyLinked)
        assertTrue(mapping.links.any { answer.substring(it.start, it.end) == "Spain joined the EEC in 1986" })
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match("Portugal joined the EEC in 1986.", answer))
    }
}
