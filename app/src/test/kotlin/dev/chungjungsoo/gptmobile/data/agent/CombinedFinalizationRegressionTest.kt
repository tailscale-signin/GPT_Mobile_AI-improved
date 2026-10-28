package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions for the exported Combined answer and its finalization transaction. */
class CombinedFinalizationRegressionTest {
    @Test fun answerMappingCanRestrictWorkToRequestRelevantClaims() {
        val sources = listOf(
            CombinedModelResponse("one", "One", content = "The first distinct source claim describes coastal erosion."),
            CombinedModelResponse("two", "Two", content = "The second distinct source claim describes urban rainfall.")
        )

        val mapping = CombinedAnswerMappings.get(
            "The second distinct source claim describes urban rainfall.",
            sources,
            setOf("C2.1")
        )

        assertEquals(listOf("C2.1"), mapping.claims.map { it.claimId })
        assertTrue(mapping.links.any { "two" in it.profileUids })
    }

    private fun context(request: String): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("source"), mapOf("source" to "model"), request, singlePass = true)
        return CombinedExecutionContext(requested, rootBudgetId = "finalization-regression")
    }

    private fun provider(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }

    @Test fun mixedAnimalAndHumanQualificationMatchesItselfWithoutCertifyingFactualAccuracy() {
        val text = "Most robust evidence is from animal models; large, high-quality human RCTs are still limited."
        val sources = listOf(CombinedModelResponse("source", "Source", content = text, claimExtractionVersion = 4))
        val qualifiers = CombinedClaimSemantics.qualifiers(text)
        assertTrue(CombinedClaimSemantics.applicabilityConflicts(qualifiers, qualifiers).isEmpty())
        assertTrue(CombinedClaimSemantics.lostQualifiers(qualifiers, qualifiers).isEmpty())
        val mapping = CombinedAnswerMappings.get(text, sources)
        assertEquals("EXACT", mapping.claims.single().status)
        assertTrue(mapping.links.isNotEmpty())
        assertTrue(mapping.links.all { it.matches(text) })
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match("Animal studies suggest improved endurance.", "Human studies suggest improved endurance."))
        assertFalse(CombinedAnswerMappings.get("Most robust evidence is from animal models.", sources).claims.single().fullyLinked)
    }

    @Test fun exactCopyElsewhereDoesNotEraseTheOverviewPartialLink() {
        val source = "Medical use should be supervised by a qualified clinician, especially in people with lung, heart, or seizure disorders."
        val overview = "Consultation with a healthcare professional is advised before starting, and supervised medical use is especially important for people with lung, heart, or seizure disorders."
        val sources = listOf(CombinedModelResponse("source", "Source", content = source, claimExtractionVersion = 3))
        val initial = CombinedAnswerMappings.get(overview, sources)
        assertTrue(initial.links.isNotEmpty())
        val expanded = "$overview\n\n$source"
        val final = CombinedAnswerMappings.get(expanded, sources)
        assertTrue(final.claims.single().fullyLinked)
        initial.links.forEach { old ->
            assertTrue("A later exact copy erased a valid original link", final.links.any { it.start == old.start && it.end == old.end && it.profileUids == old.profileUids })
        }
        assertTrue(final.links.any { it.start > overview.length })
    }

    @Test fun sourceAccountingRetainsUnknownLinksWithoutAddingAnAppendixOrClaimingOmission() {
        val sources = listOf(
            CombinedModelResponse("source", "Source", content = "Portugal joined NATO in 1949 and the EEC in 1986.", claimExtractionVersion = 4),
            CombinedModelResponse("other", "Other", content = "Portugal restored independence in 1640.", claimExtractionVersion = 4)
        )
        val text = "Portugal joined NATO in 1949."
        val account = CombinedSourcePreservation.account(text, sources)
        assertEquals(text, account.text)
        assertEquals(0, account.fragments)
        assertEquals(setOf("PARTIALLY_LINKED", "UNRESOLVED_LINK"), account.dispositions.map { it.disposition }.toSet())
        assertTrue(account.dispositions.all { it.sourceDigest == researchHash(sources.single { source -> source.platformUid == it.profileUid }.content) })
        assertTrue(account.dispositions.none { it.disposition == "MISSING" })
    }

    @Test fun omittedUniqueContributorClaimBlocksSuccessfulSynthesis() {
        val sources = listOf(
            CombinedModelResponse("source", "Source", content = "Portugal joined NATO in 1949 and the EEC in 1986.", claimExtractionVersion = 4),
            CombinedModelResponse("other", "Other", content = "Portugal restored independence in 1640.", claimExtractionVersion = 4)
        )
        val assessment = CombinedFinalCheck.check(
            "Portugal joined NATO in 1949.",
            sources,
            CombinedEvidenceLedger(),
            CombinedAnswerPolicy.from("Explain the history of Portugal")
        )
        assertTrue(assessment.checks.any { it.id.startsWith("SEMANTIC_CONTRIBUTION_UNRESOLVED:") && it.result == "FAIL" })
        assertTrue(assessment.requiredFailures.any { it.startsWith("SEMANTIC_CONTRIBUTION_UNRESOLVED:") })
    }

    @Test fun oversizedWriterRecordIsMappedPerHeadingAndIndividualListItem() {
        val sources = listOf(
            CombinedModelResponse("first", "First", content = "The northern garden contains twelve apple trees.", claimExtractionVersion = 4),
            CombinedModelResponse("second", "Second", content = "The southern garden contains seven cherry trees.", claimExtractionVersion = 4)
        )
        val answer = "### Gardens\n- The northern garden contains twelve apple trees.\n- The southern garden contains seven cherry trees."
        val declared = CombinedPassageRecord(0, answer.length, researchHash(answer), setOf("first", "second"), claimIds = setOf("C1.1", "C2.1"), passageId = "large-section")
        val passages = CombinedAnswerMappings.passages(answer, CombinedAnswerMappings.get(answer, sources), listOf(declared))
        assertEquals(3, passages.size)
        assertTrue(passages.first().profileUids.isEmpty())
        assertEquals(setOf("first"), passages[1].profileUids)
        assertEquals(setOf("second"), passages[2].profileUids)
        assertTrue(passages.all { it.matches(answer) && it.links.all { link -> link.matches(answer) && link.start >= it.start && link.end <= it.end } })
        assertFalse("C2.1" in passages[1].claimIds)
        assertFalse("C1.1" in passages[2].claimIds)
    }

    @Test fun localLengthAssemblyReassessesTheDisplayedBodyAndKeepsWriterMeasurementsSeparate() = runTest {
        val request = "Explain the findings in at most 10 words"
        val original = "The garden contains several species of flowers and trees. The plants provide seasonal colour throughout the spring and summer months."
        val sources = listOf(CombinedModelResponse("source", "Source", content = original, claimExtractionVersion = 4))
        val session = CombinedDeliverySession(provider(original), sources, 10000, preserveSourceContent = true, validateAccess = {}, maxRepairRounds = 0, originalRequest = request, combined = context(request)) { _, _ -> error("No hidden second writer") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val bundle = events.filterIsInstance<ProviderEvent.CombinedAnswer>().single()
        val report = bundle.report
        assertTrue(events.none { it is ProviderEvent.TextDelta || it is ProviderEvent.CombinedQuality })
        assertTrue(visibleWordCount(bundle.text) <= 10)
        assertEquals(researchHash(bundle.text), report.answerDigest)
        assertEquals(report.answerDigest, report.claimMapping!!.answerDigest)
        assertTrue(report.claimMapping.matches(bundle.text))
        assertEquals("FINAL_DISPLAY", report.assessmentStage)
        assertEquals(researchHash(original), report.writerAnswerDigest)
        assertEquals(report.writerAnswerDigest, report.writerAssessment!!.answerDigest)
        assertTrue(report.writerAssessment.checks.any { it.id == "ANSWER_CONTRACT" && it.result == "FAIL" })
        assertTrue(report.checks.any { it.id == "ANSWER_CONTRACT" && it.result == "PASS" })
        assertEquals(original.length, report.writerCharacters)
        assertEquals(bundle.text.length, report.displayedCharacters)
        assertEquals(original.length - bundle.text.length, report.locallyRemovedCharacters)
        assertEquals(0, report.locallyAddedCharacters)
        assertEquals(report.claimMapping.claims.size, report.fullyLinkedClaims + report.partialSourceLinks + report.unresolvedSourceLinks)
    }

    @Test fun unverifiedClinicalAssertionDoesNotBecomeAKnownCorrectionOrASecondWriterCall() = runTest {
        val text = "The experimental treatment may improve clinical outcomes in adults."
        val request = "Explain the reported findings"
        val sources = listOf(CombinedModelResponse("source", "Source", content = text, claimExtractionVersion = 4))
        val session = CombinedDeliverySession(provider(text), sources, 10000, preserveSourceContent = true, validateAccess = {}, originalRequest = request, combined = context(request)) { _, _ -> error("Single-pass checks are local") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val report = events.combinedReport()
        assertEquals(text, events.combinedAnswerText())
        assertEquals("NEEDS_VERIFICATION", report.verificationStatus)
        assertFalse(report.correctionRequired)
        assertFalse(report.factualAccuracyVerified)
        assertTrue(report.semanticCoverageVerified)
        assertTrue(report.protocolComplete)
        assertTrue(report.complete)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun reportedAthleteUseCannotBecomeAStudyFindingAboutRecovery() {
        val practice = "Some athletes use hydrogen inhalation to reduce muscle fatigue and improve recovery time after intense exercise."
        val study = "Some studies show hydrogen inhalation can reduce muscle fatigue and improve recovery time after intense exercise."
        assertFalse(CombinedClaimMatcher.compatible(CombinedClaimMatcher.fingerprint(practice), CombinedClaimMatcher.fingerprint(study)))
        val sources = listOf(CombinedModelResponse("source", "Source", content = practice, claimExtractionVersion = 4))
        assertFalse(CombinedAnswerMappings.get(study, sources).claims.single().fullyLinked)
    }

    @Test fun versionFourExcludesStructuralLabelsAndKeepsMultipleAdjacentQualifications() {
        val text = "**Important evidence caveats:**\n\nThe treatment improves symptoms. However, evidence remains limited. These findings require confirmation.\n\n**Evidence is limited**"
        val legacy = CombinedClaimSemantics.records(listOf(CombinedModelResponse("source", "Source", content = text, claimExtractionVersion = 3)))
        val current = CombinedClaimSemantics.records(listOf(CombinedModelResponse("source", "Source", content = text, claimExtractionVersion = 4)))
        assertTrue(legacy.any { it.proposition.contains("Important evidence caveats") })
        assertFalse(current.any { it.proposition.contains("Important evidence caveats") })
        assertTrue(current.any { it.proposition.contains("improves symptoms") && it.proposition.contains("require confirmation") })
        assertTrue(current.any { it.proposition.contains("Evidence is limited") })
    }

    @Test fun topicPlanIncludesEveryDistinctCandidateOnceWithoutProfileQuotas() {
        val source = CombinedModelResponse("source", "Source", content = "- **Antioxidant effects:** Hydrogen may affect oxidative stress.\n- **Metabolic health:** Hydrogen may affect glucose metabolism.\n- Hydrogen is flammable and requires ventilation.\n- Most robust evidence is from animal models; large human trials remain limited.", claimExtractionVersion = 4)
        val claims = CombinedClaimSemantics.records(listOf(source))
        val plan = CombinedSectionPlan.create(CombinedAnswerPolicy.from("Explain the reported benefits"), claims)
        assertTrue(plan.size > 1)
        assertEquals(claims.map { it.id }.toSet(), plan.flatMap { it.candidateIds }.toSet())
        assertEquals(claims.size, plan.sumOf { it.candidateIds.size })
        assertTrue(plan.all { it.targetWords == null })
        assertTrue(plan.any { it.label.contains("Safety") })
        assertTrue(plan.any { it.label.contains("limitations") })
    }

    @Test fun conflictReferencesFollowTheClaimTextWhenVersionFourChangesSentenceIds() {
        val sources = listOf(
            CombinedModelResponse("source", "Source", content = "**Important council record:**\n\nThe council approved 12 houses in 1930. However, evidence remains limited.", claimExtractionVersion = 4),
            CombinedModelResponse("other", "Other", content = "**Important council record:**\n\nThe council approved 14 houses in 1930.", claimExtractionVersion = 4)
        )
        val request = "Explain the council records and their disagreement"
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), sources.map { it.platformUid }, sources.associate { it.platformUid to "model" }, request, singlePass = true)
        val execution = CombinedExecutionContext(requested, rootBudgetId = "conflict-reference-regression")
        val prompt = Json.parseToJsonElement(dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt(request, sources, combined = execution)).jsonObject
        val claims = CombinedClaimInventoryWire.decode(prompt).associate { it.getValue("id").jsonPrimitive.content to it }
        val conflict = prompt.getValue("potential_conflicts").jsonArray.single().jsonObject
        assertEquals("NUMERIC", conflict.getValue("kind").jsonPrimitive.content)
        assertEquals("C1.1", conflict.getValue("left_claim_id").jsonPrimitive.content)
        assertEquals("C2.1", conflict.getValue("right_claim_id").jsonPrimitive.content)
        val first = claims.getValue("C1.1")
        assertTrue(first.getValue("text").jsonPrimitive.content.contains("12 houses"))
        assertTrue(first.getValue("text").jsonPrimitive.content.contains("evidence remains limited"))
        assertTrue(first.getValue("qualifiers").jsonObject.isNotEmpty())
        assertTrue(claims.getValue("C2.1").getValue("text").jsonPrimitive.content.contains("14 houses"))
        assertFalse("left_excerpt" in conflict)
        assertFalse("right_excerpt" in conflict)

        val legacy = Json.parseToJsonElement(dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt(request, sources)).jsonObject
        val legacyConflict = legacy.getValue("potential_conflicts").jsonArray.single().jsonObject
        assertTrue(legacyConflict.getValue("left_excerpt").jsonPrimitive.content.contains("12 houses"))
        assertTrue(legacyConflict.getValue("right_excerpt").jsonPrimitive.content.contains("14 houses"))
    }
}
