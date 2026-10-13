package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRepairContractsTest {
    @Test fun legacyObjectQuestionsKeepEvidenceRequirements() {
        val plan = ResearchPlanContract.parse(Json.parseToJsonElement("""{"questions":[{"question":"Does the fixture improve accuracy?","evidence_needed":"Controlled accuracy measurements"}],"queries":["fixture accuracy"]}""").jsonObject)
        assertEquals("Controlled accuracy measurements", plan.questions.single().evidenceNeeded)
        assertEquals(listOf("Q1"), plan.queries.single().questionIds)
        val snapshot = ResearchSnapshot("Task", questions = plan.questions.map { it.text }, questionPlan = plan.questions, planningStatus = "Validated")
        assertEquals(snapshot, ResearchSnapshot.parse(snapshot.json()))
    }

    @Test fun malformedRowsAndImplicitEmptyPlansCannotBecomeSuccess() {
        for (input in listOf("""{"questions":[12],"queries":[]}""", """{"questions":[],"queries":[]}""", """{"questions":["Valid",{}],"queries":[]}""")) {
            assertTrue(runCatching { ResearchPlanContract.parse(Json.parseToJsonElement(input).jsonObject) }.isFailure)
        }
        assertTrue(ResearchPlanContract.parse(Json.parseToJsonElement("""{"questions":[],"queries":[],"noResearchNeeded":true}""").jsonObject).noResearchNeeded)
        assertTrue(ResearchPlanContract.fallback("Task").fallback)
    }

    @Test fun unknownQuestionReferencesAreRejected() {
        assertTrue(runCatching { ResearchPlanContract.parse(Json.parseToJsonElement("""{"questions":[{"id":"Q1","text":"Task"}],"queries":[{"text":"query","questionIds":["Q2"]}]}""").jsonObject) }.isFailure)
    }

    @Test fun readableQuotaAndKeywordOverlapDoNotProveCoverage() {
        val questions = listOf(ResearchQuestion("Q1", "Does a fixture improve accuracy?"))
        val source = ResearchSource("S1", "https://example.org", "Fixture prices", "Read", "The fixture price is exactly twenty dollars.")
        val claim = ResearchClaim("Fixture price", "S1", source.passage, "Supported", "Q1", "Background", true)
        val coverage = assessResearchCoverage(questions, listOf(claim), listOf(source))
        assertFalse(coverage.complete)
        assertEquals(setOf("Q1"), coverage.missingQuestionIds)
        assertFalse(assessResearchCoverage(questions, listOf(claim.copy(relevance = "Relevant", verdict = "Unreviewed")), listOf(source)).complete)
    }

    @Test fun unknownPublicationStatusCannotSatisfyPeerReviewRequirement() {
        val questions = listOf(ResearchQuestion("Q1", "Fixture accuracy", "peer-reviewed study"))
        val source = ResearchSource("S1", "https://example.org", "Fixture accuracy", "Read", "The study measured an accuracy improvement of two percent.")
        val claim = ResearchClaim("Accuracy improves", "S1", source.passage, "Supported", "Q1", "Relevant", true)
        assertFalse(assessResearchCoverage(questions, listOf(claim), listOf(source)).complete)
        assertTrue(assessResearchCoverage(questions, listOf(claim), listOf(source.copy(reviewStatus = "peer_reviewed", publicationType = "controlled study"))).complete)
        assertFalse(assessResearchCoverage(questions, listOf(claim), listOf(source.copy(reviewStatus = "peer_reviewed", isRetracted = true))).complete)
    }

    @Test fun moreThanThirtyClaimsAndVerificationUpdatesSurviveStorageMerge() {
        val passage = "A complete synthetic quotation with enough context for verification."
        val source = ResearchSource("S1", "https://example.org", "Fixture", "Read", passage)
        val claims = (1..64).map { ResearchClaim("Claim $it", "S1", passage, "Unreviewed") }
        val snapshot = ResearchSnapshot("Task", sources = listOf(source), claims = claims)
        assertEquals(64, ResearchSnapshot.parse(snapshot.json())!!.claims.size)
        val reviewed = snapshot.copy(claims = listOf(claims.first().copy(verdict = "Supported")))
        val merged = mergeResearchSnapshots(listOf(snapshot), reviewed)
        assertEquals(64, merged.claims.size)
        assertEquals("Supported", merged.claims.first().verdict)
    }

    @Test fun oversizedQuoteIsRejectedWithoutManufacturingATruncatedQuote() {
        val quote = "Synthetic passage. ".repeat(100)
        val source = ResearchSource("S1", "https://example.org", "Fixture", "Read", quote)
        assertTrue(validatedResearchClaims(listOf(ResearchClaim("Claim", "S1", quote).json()), listOf(source)).isEmpty())
    }

    @Test fun empiricalCoverageRejectsKeywordOverlappingNonStudySourcesEvenWhenModelSaysRelevant() {
        val question = ResearchQuestion("Q1", "Does the accuracy fixture improve outcomes?", "Primary controlled study of measured outcomes")
        val types = listOf("controlled study", "product pricing", "prevalence study", "review comment", "study protocol", "questionnaire methods")
        val sources = types.mapIndexed { index, type -> ResearchSource("S$index", "https://example.org/$index", "Accuracy fixture $type", "Read", "A synthetic exact quotation about accuracy fixture outcomes.", publicationType = type) }
        val claims = sources.map { ResearchClaim("Accuracy fixture outcomes", it.id, it.passage, "Supported", "Q1", "Relevant", true) }
        val assessment = assessResearchCoverage(listOf(question), claims, sources)
        assertEquals(listOf("S0"), assessment.relevantClaims.map { it.sourceId })
    }

    @Test fun expiredSourceInvalidatesOnlyDependentEvidence() {
        val recent = ResearchSource("S1", "https://example.org/1", "Recent", "Read", "Complete recent passage", retrievedAt = 100000000)
        val old = recent.copy(id = "S2", retrievedAt = 1)
        val claims = listOf(recent, old).map { ResearchClaim("Claim", it.id, it.passage, "Supported", "Q1", "Relevant", true) }
        val refreshed = ResearchSnapshot("Task", complete = true, sources = listOf(recent, old), claims = claims, extractedSourceVersions = setOf("S1:hash", "S2:hash")).withFreshEvidence(100000001)
        assertTrue(refreshed.sources.first().readable)
        assertFalse(refreshed.sources.last().readable)
        assertEquals("Supported", refreshed.claims.first().verdict)
        assertEquals("Unreviewed", refreshed.claims.last().verdict)
        assertEquals(setOf("S1:hash"), refreshed.extractedSourceVersions)
    }

    @Test fun plannerCannotWaiveRequiredEvidenceOrUseUnknownSchema() {
        val waived = Json.parseToJsonElement("""{"questions":[],"queries":[],"noResearchNeeded":true}""").jsonObject
        assertTrue(runCatching { ResearchPlanContract.parse(waived, requiresEvidence = true) }.isFailure)
        val unknown = Json.parseToJsonElement("""{"schemaVersion":999,"questions":["Valid"],"queries":[]}""").jsonObject
        assertTrue(runCatching { ResearchPlanContract.parse(unknown) }.isFailure)
    }

    @Test fun plannerRejectsCoercedFieldsAndMalformedReferences() {
        val invalid = listOf(
            """{"questions":[{"text":12}],"queries":[]}""",
            """{"questions":[{"text":"Valid","evidenceNeeded":false}],"queries":[]}""",
            """{"questions":["Valid"],"queries":[{"text":"query","questionIds":[12]}]}""",
            """{"questions":["Valid"],"queries":[{"text":"query","questionIds":[]}]}""",
            """{"questions":["Valid"],"queries":[],"noResearchNeeded":true}""",
            """{"questions":[],"queries":[],"noResearchNeeded":"true"}"""
        )
        invalid.forEach { assertTrue(it, runCatching { ResearchPlanContract.parse(Json.parseToJsonElement(it).jsonObject) }.isFailure) }
        val legacy = ResearchPlanContract.parse(Json.parseToJsonElement("""{"questions":["Valid"],"queries":[{"query":"legacy query"}]}""").jsonObject)
        assertEquals(listOf("Q1"), legacy.queries.single().questionIds)
    }

    @Test fun applicablePrevalenceStudyIsNotGloballyExcluded() {
        val question = ResearchQuestion("Q1", "What is the prevalence of the synthetic trait?", "Primary prevalence study")
        val source = ResearchSource("S1", "https://example.org/prevalence", "Fixture prevalence", "Read", "The trait occurred in ten percent of the synthetic sample.", publicationType = "prevalence study")
        val claim = ResearchClaim("Prevalence estimate", "S1", source.passage, "Supported", "Q1", "Relevant", true)
        assertTrue(assessResearchCoverage(listOf(question), listOf(claim), listOf(source)).complete)
    }
}
