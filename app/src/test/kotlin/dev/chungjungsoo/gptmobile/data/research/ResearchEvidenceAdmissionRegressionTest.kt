package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind
import dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
import dev.chungjungsoo.gptmobile.data.research.feature.ArticleSectionCoverage
import dev.chungjungsoo.gptmobile.data.research.feature.ContentInspection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEvidenceAdmissionRegressionTest {
    @Test fun successfulDownloadsNeedPositiveStructuredBodyProofForFullText() {
        val methods = "Methods: participants were randomized. Results: outcomes were measured."
        assertEquals(SourceContentValidator.Kind.ERROR_PAGE, SourceContentValidator.validate("Sorry, we cannot find the requested article. Article not found.", completeArticle = true).kind)
        assertEquals(SourceContentValidator.Kind.APPLICATION_SHELL, SourceContentValidator.validate("Glossary: randomized controlled trial means allocation by chance.").kind)
        assertEquals(SourceContentValidator.Kind.SEARCH_RESULTS, SourceContentValidator.validate("Search results. Sort by year. Cited by 14", url = "https://scholar.google.com/scholar?q=trial").kind)
        assertEquals(SourceContentValidator.Kind.ABSTRACT, SourceContentValidator.validate("In 24 participants, measured performance did not differ.", abstractOnly = true).kind)
        assertFalse(SourceContentValidator.validate(methods, completeArticle = true).fullTextProven)
        assertFalse(SourceContentValidator.validate(methods, completeArticle = true, structuredBody = true, sectionNames = setOf("methods", "results"), truncated = true).fullTextProven)
        assertTrue(SourceContentValidator.validate(methods, completeArticle = true, structuredBody = true, sectionNames = setOf("methods", "results"), truncated = false).fullTextProven)
    }

    @Test fun metadataFromAuthorizedCaptureRetainsIdentifiersWithoutInventingPeerReview() {
        val source = ResearchSource("S1", "https://pmc.ncbi.nlm.nih.gov/articles/PMC1234567/", "Randomized controlled trial", "Read", "Measured performance in 24 adults showed no difference.")
        val body = "Publication type: Randomized controlled trial\nDOI: 10.1234/this-trial\nPMID: 123456\nMethods and results\nReferences\nDOI: 10.1111/other-trial\nPMID: 999999"
        val enriched = enrichResearchSourceMetadata(source, body)
        assertEquals("10.1234/this-trial", enriched.doi)
        assertEquals("PMC1234567", enriched.pmcid)
        assertEquals("123456", enriched.pmid)
        assertTrue("RANDOMIZED_TRIAL" in enriched.studyDesigns)
        assertEquals("unknown", enriched.reviewStatus)
        assertTrue(enriched.metadataProvenance.single().startsWith("authorized_capture:"))
        val decoded = ResearchSnapshot.parse(ResearchSnapshot("task", sources = listOf(enriched)).json())!!.sources.single()
        assertEquals(enriched.doi, decoded.doi)
        assertEquals(enriched.studyDesigns, decoded.studyDesigns)
        val referencesOnly = enrichResearchSourceMetadata(source.copy(title = "Article"), "Summary of findings.\nREFERENCES\nDOI: 10.9999/wrong\nPMID: 77777")
        assertEquals("", referencesOnly.doi)
        assertEquals("", referencesOnly.pmid)
    }

    @Test fun publisherWrapperAndDoiResolveToOneScientificIdentity() {
        val doi = SourceIdentity.create("https://doi.org/10.1234/ABC", "A", "body")
        val publisher = SourceIdentity.create("https://publisher.example/doi/full/10.1234/ABC", "A", "body")
        assertEquals(doi.equivalenceKey, publisher.equivalenceKey)
        assertNotEquals(doi.equivalenceKey, SourceIdentity.create("https://example.org/search?q=10.1234/ABC", "Search", "body").equivalenceKey)
    }

    @Test fun anyOfDesignAlternativesAndUnknownPeerReviewAreDifferentOutcomes() {
        val quote = "Measured performance in 24 adults showed no difference."
        val source = ResearchSource("S1", "https://example.org/article", "Trial", "Read", quote, publicationType = "randomized controlled trial", reviewStatus = "peer_reviewed")
        val alternatives = ResearchQuestion("Q1", "Does it change performance?", evidenceNeeded = "peer-reviewed controlled studies or observational studies or systematic reviews")
        assertEquals(RequirementCombination.ANY_OF, researchEvidenceRequirement(alternatives).combination)
        assertEquals(ResearchRequirementResult.SATISFIED, assessResearchRequirement(alternatives, source))
        val all = alternatives.copy(requirement = ResearchEvidenceRequirement(setOf("RANDOMIZED_TRIAL", "OBSERVATIONAL"), RequirementCombination.ALL_OF, peerReviewed = true))
        assertEquals(ResearchRequirementResult.UNSUITABLE, assessResearchRequirement(all, source))
        val unknown = source.copy(reviewStatus = "unknown")
        assertEquals(ResearchRequirementResult.METADATA_REQUIRED, assessResearchRequirement(alternatives, unknown))
        val assessment = assessResearchCoverage(listOf(alternatives), emptyList(), listOf(unknown))
        assertEquals(setOf("Q1"), assessment.metadataRequiredQuestionIds)
        assertEquals(ResearchRequirementResult.METADATA_REQUIRED, assessResearchRequirement(alternatives, source.copy(publicationType = "Journal Article", studyDesigns = emptySet())))
        assertEquals(ResearchRequirementResult.UNSUITABLE, assessResearchRequirement(alternatives, source.copy(isPreprint = true)))
    }

    @Test fun legacyVersionsBindToExactBodyHashWhileVersionedClaimsCannotCrossCaptures() {
        val quote = "Measured performance in 24 adults showed no difference."
        val source = ResearchSource("S1", "https://example.org/article", "Study", "Read", quote)
        val question = ResearchQuestion("Q1", "What happened?", evidenceNeeded = "exact source passage")
        val claim = ResearchClaim(text = "No difference was reported.", sourceId = source.id, quote = quote, verdict = "Supported", relevance = "Relevant", questionId = "Q1", exactQuoteVerified = true, reviewedSourceVersion = researchHash(quote))
        assertTrue(assessResearchCoverage(listOf(question), listOf(claim), listOf(source)).complete)
        assertFalse(assessResearchCoverage(listOf(question), listOf(claim), listOf(source.copy(passage = quote + " Different version."))).complete)
        assertFalse(assessResearchCoverage(listOf(question), listOf(claim), listOf(source.copy(versionId = "specific-version"))).complete)
    }

    @Test fun tableInspectionIsSeparateFromTextBreadthAndOldAvailabilityFlags() {
        val old = Json.decodeFromString<ArticleSectionCoverage>("""{"inspected":["methods","results","limitations"],"figuresAvailable":true,"supplementsAvailable":true}""")
        assertFalse(old.complete)
        val textComplete = old.copy(figuresInspection = ContentInspection.NOT_APPLICABLE, figuresReason = "Structured inventory declares none", supplementsInspection = ContentInspection.NOT_APPLICABLE, supplementsReason = "Structured inventory declares none")
        assertFalse(textComplete.complete)
        assertTrue(textComplete.copy(tablesInspection = ContentInspection.INSPECTED).complete)
        assertFalse(textComplete.copy(tablesInspection = ContentInspection.NOT_APPLICABLE).complete)
        assertTrue(textComplete.copy(tablesInspection = ContentInspection.NOT_APPLICABLE, tablesReason = "Structured inventory declares no tables").complete)
    }

    @Test fun typedTransportAndOutputLimitDoNotBecomeMalformedSchemaCircuits() = runTest {
        var calls = 0
        val events = mutableListOf<ResearchModelEvent>()
        val kept = mutableListOf<String>()
        val target = ResearchModelEndpoint("profile", "model", "canonical", false, { true }) { _, _, _ ->
            calls++
            if (calls == 1) throw DelegateGenerationException(DelegateFailureKind.TRANSPORT_FAILURE, "HTTP 503", 0, 0, 0, true, partialText = "{\"questions\":[")
            if (calls == 2) throw DelegateGenerationException(DelegateFailureKind.OUTPUT_LIMIT, "Output limit", 0, 0, 0, true, partialText = "{\"questions\":[]")
            ResearchModelReply("""{"questions":[],"queries":[]}""", "canonical")
        }
        val coordinator = ResearchModelCoordinator(listOf(target), onEvent = { events += it }, retainPartial = { _, _, text ->
            kept += text
            "private-${kept.size}"
        })
        assertNull(coordinator.generate(ResearchModelStage.PLAN, "Plan", 128))
        assertNull(coordinator.generate(ResearchModelStage.PLAN, "Plan", 128))
        assertEquals(2, kept.size)
        assertTrue(coordinator.generate(ResearchModelStage.PLAN, "Plan", 128) != null)
        assertEquals(3, calls)
        assertFalse(events.any { it.reason in setOf("MALFORMED_JSON", "MODEL_CIRCUIT_OPEN") })
        assertEquals(listOf("TRANSPORT", "OUTPUT_LIMIT"), events.filter { it.outcome == "FAILED" }.map { it.failureCategory })
        assertTrue(events.filter { it.outcome == "FAILED" }.all { it.partialRetained && it.checkpointId != null && !it.stageAccepted && !it.schemaValid })
        assertTrue(events.last().transportComplete && events.last().schemaValid && events.last().stageAccepted)
    }

    @Test fun partialCheckpointStorageFailureNeverReclassifiesOriginalTransportFailure() = runTest {
        val target = ResearchModelEndpoint("profile", "model", "canonical", false, { true }) { _, _, _ -> ResearchModelReply("{partial", "canonical", transportComplete = false) }
        val coordinator = ResearchModelCoordinator(listOf(target), retainPartial = { _, _, _ -> throw java.io.IOException("disk unavailable") })
        assertNull(coordinator.generate(ResearchModelStage.PLAN, "Plan", 128))
        assertEquals("TRANSPORT", coordinator.events.last().failureCategory)
        assertFalse(coordinator.events.last().partialRetained)
    }
}
