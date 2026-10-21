package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRepairContractsTest {
    @Test fun turnCorpusPreservesReadableEvidenceAndDistinctVersions() {
        val readable = ResearchSource("S1", "https://example.org/paper", "Fixture", "Read", "A complete retained source passage.", versionId = "v1", retrievedAt = 1)
        val failed = readable.copy(status = "Failed", passage = "", retrievedAt = 2, retrievalQuality = "EMPTY")
        val revised = readable.copy(id = "S2", versionId = "v2", passage = "A revised retained source passage.", retrievedAt = 3)
        val corpus = ResearchCorpus.freezeAll(listOf(ResearchSnapshot("Task", sources = listOf(readable), updatedAt = 1), ResearchSnapshot("Task", sources = listOf(failed, revised), updatedAt = 3)))!!
        assertEquals(setOf("v1", "v2"), corpus.sources.map { it.versionId }.toSet())
        assertTrue(corpus.sources.all { it.readable })
        assertEquals(readable.passage, corpus.sources.single { it.versionId == "v1" }.passage)
    }

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
        val lateExtraction = mergeResearchSnapshots(listOf(merged), snapshot)
        assertEquals(64, lateExtraction.claims.size)
        assertEquals("Supported", lateExtraction.claims.first().verdict)
    }

    @Test fun exactEvidenceCheckpointKeepsItsReviewDigestAndAnEmptyShellCannotRenameQuestions() {
        val source = ResearchSource("S1", "https://example.org/retained", "Retained", "Read", "An exact retained scientific passage.")
        val original = ResearchSnapshot("Task", sources = listOf(source), questionPlan = listOf(ResearchQuestion("Q1", "What was measured?", "Captured results")), attempts = 31, searches = 3, engineResponses = 22, engineFailures = 9, targetSources = 12)
        val reviewed = original.copy(reviewRecord = ResearchReviewRecord("UNAVAILABLE", original.evidenceRevisionDigest(), researchHash("handoff"), "Reviewer unavailable"))
        val firstSave = mergeResearchSnapshots(emptyList(), reviewed)
        assertEquals(original.evidenceRevisionDigest(), firstSave.evidenceRevisionDigest())
        assertEquals("UNAVAILABLE", firstSave.currentReviewRecord()?.status)
        val shell = mergeResearchSnapshots(listOf(firstSave), ResearchSnapshot("Task", phase = "Writing", attempts = 9, updatedAt = 900))
        assertEquals(original.evidenceRevisionDigest(), shell.evidenceRevisionDigest())
        assertEquals("Q1", shell.questionPlan.single().id)
        assertEquals("UNAVAILABLE", shell.currentReviewRecord()?.status)
        assertEquals("Writing", shell.phase)
        assertEquals(listOf(31, 3, 22, 9, 12), listOf(shell.attempts, shell.searches, shell.engineResponses, shell.engineFailures, shell.targetSources))
        assertEquals(900L, shell.updatedAt)
        val later = mergeResearchSnapshots(listOf(shell), shell.copy(attempts = 40, searches = 4, engineResponses = 25, engineFailures = 10, targetSources = 15, updatedAt = 1000))
        assertEquals(listOf(40, 4, 25, 10, 15), listOf(later.attempts, later.searches, later.engineResponses, later.engineFailures, later.targetSources))
        assertEquals(original.evidenceRevisionDigest(), later.evidenceRevisionDigest())
        assertEquals("UNAVAILABLE", later.currentReviewRecord()?.status)
    }

    @Test fun failedEmptyRetryPreservesOneUnambiguousCaptureWhileChangedVersionsRemainSeparate() {
        val source = ResearchSource("S1", "https://example.org/retained", "Retained", "Read", "An exact retained scientific passage.")
        val previous = ResearchSnapshot("Task", sources = listOf(source))
        val failed = source.copy(status = "Failed", passage = "", retrievalQuality = "EMPTY", retrievedAt = 9, failureReason = "HOST_DNS_UNAVAILABLE")
        val kept = mergeResearchSnapshots(listOf(previous), previous.copy(sources = listOf(failed)))
        assertEquals(1, kept.sources.size)
        assertTrue(kept.sources.single().readable)
        assertEquals(source.passage, kept.sources.single().passage)
        assertEquals("HOST_DNS_UNAVAILABLE", kept.sources.single().failureReason)
        val changed = source.copy(passage = "A different retained scientific passage.", versionId = "version-two")
        val versions = mergeResearchSnapshots(listOf(previous), previous.copy(sources = listOf(changed)))
        assertEquals(2, versions.sources.size)
        assertEquals(2, versions.sources.map { it.id }.distinct().size)
        assertTrue(versions.sources.any { it.versionId == "version-two" && it.passage == changed.passage })
    }

    @Test fun incrementalEvidenceCannotResetAcquisitionCountersOrExtractionReceipts() {
        val first = ResearchSource("S1", "https://example.org/first", "First", "Read", "The first retained source contains measured results.")
        val second = ResearchSource("S2", "https://example.org/second", "Second", "Read", "The second retained source contains additional results.")
        val previous = ResearchSnapshot(
            "Task", sources = listOf(first), round = 4, attempts = 31, searches = 3,
            engineResponses = 22, engineFailures = 9, targetSources = 12,
            extractedSourceVersions = setOf("S1:${capturedResearchVersion(first)}")
        )
        val incoming = ResearchSnapshot(
            "Task", sources = listOf(second), round = 1, attempts = 2, searches = 1,
            engineResponses = 1, engineFailures = 1, targetSources = 2,
            extractedSourceVersions = setOf("S2:${capturedResearchVersion(second)}")
        )
        val merged = mergeResearchSnapshots(listOf(previous), incoming)
        assertEquals(setOf("S1", "S2"), merged.sources.map { it.id }.toSet())
        assertEquals(listOf(4, 31, 3, 22, 9, 12), listOf(merged.round, merged.attempts, merged.searches, merged.engineResponses, merged.engineFailures, merged.targetSources))
        assertEquals(previous.extractedSourceVersions + incoming.extractedSourceVersions, merged.extractedSourceVersions)
    }

    @Test fun richerReviewedCaptureRetainsWorkSpentByLaterSparseCheckpoint() {
        val source = ResearchSource("S1", "https://example.org/retained", "Retained", "Read", "An exact retained scientific passage.")
        val captured = ResearchSnapshot("Task", sources = listOf(source), round = 1, attempts = 2)
        val reviewed = captured.copy(reviewRecord = ResearchReviewRecord("UNAVAILABLE", captured.evidenceRevisionDigest(), researchHash("handoff"), "Reviewer unavailable"))
        val later = ResearchSnapshot("Task", round = 4, attempts = 31, searches = 3, engineResponses = 22, engineFailures = 9, targetSources = 12, updatedAt = captured.updatedAt + 1)
        val retained = requireNotNull(richestResearchSnapshot(listOf(reviewed, later)))
        assertEquals(reviewed.evidenceRevisionDigest(), retained.evidenceRevisionDigest())
        assertEquals("UNAVAILABLE", retained.currentReviewRecord()?.status)
        assertEquals(listOf(4, 31, 3, 22, 9, 12), listOf(retained.round, retained.attempts, retained.searches, retained.engineResponses, retained.engineFailures, retained.targetSources))
    }

    @Test fun storedPassageKeepsExactBytesAndReviewedQuoteBeyondPromptExcerptLimit() {
        val quote = "The late results section reports no measured improvement in performance."
        val passage = "Captured background and methods. ".repeat(250) + quote
        val source = ResearchSource("S1", "https://example.org/long-study", "Long study", "Read", passage, versionId = "capture-v1")
        val claim = ResearchClaim(
            "No measured improvement was reported.", source.id, quote, "Supported", "Q1", "Relevant", true,
            sourceVersion = source.versionId, passageHash = researchHash(passage), reviewedSourceVersion = source.versionId
        )
        val snapshot = ResearchSnapshot("Task", sources = listOf(source), claims = listOf(claim))
        val reviewed = snapshot.copy(reviewRecord = ResearchReviewRecord("PASSED", snapshot.evidenceRevisionDigest(), researchHash("handoff"), "Reviewed exact passage"))
        val restored = requireNotNull(ResearchSnapshot.parse(reviewed.json()))
        assertEquals(passage, restored.sources.single().passage)
        assertEquals(claim, restored.claims.single())
        assertEquals(reviewed.evidenceRevisionDigest(), restored.evidenceRevisionDigest())
        assertEquals("PASSED", restored.currentReviewRecord()?.status)
    }

    @Test fun questionIdentityDoesNotRemoveIdentifierTextFromQuestionContent() {
        fun snapshot(index: Int): ResearchSnapshot {
            val source = ResearchSource("S$index", "https://example.org/$index", "Quarterly results", "Read", "The Q1 result was measured using the retained protocol.")
            val question = ResearchQuestion("Q$index", "How was Q1 measured?", "The Q1 measurement protocol")
            val claim = ResearchClaim("Q1 used the retained protocol.", source.id, source.passage, "Supported", question.id, "Relevant", true)
            return ResearchSnapshot("Compare quarterly results", sources = listOf(source), claims = listOf(claim), questionPlan = listOf(question))
        }
        val firstMerge = mergeResearchSnapshots(listOf(snapshot(1)), snapshot(2))
        assertEquals(1, firstMerge.questionPlan.size)
        assertEquals("How was Q1 measured?", firstMerge.questionPlan.single().text)
        assertEquals(setOf(firstMerge.questionPlan.single().id), firstMerge.claims.map { it.questionId }.toSet())
        val continued = mergeResearchSnapshots(listOf(firstMerge), snapshot(3))
        assertEquals(firstMerge.questionPlan, continued.questionPlan)
        assertEquals(setOf(firstMerge.questionPlan.single().id), continued.claims.map { it.questionId }.toSet())
        assertEquals(3, continued.claims.size)
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

    @Test fun independentReviewSurvivesSerializationButNeverAppliesToEditedEvidence() {
        val source = ResearchSource("S1", "https://example.org/source", "Fixture", "Read", "Complete synthetic evidence passage retained verbatim.")
        val original = ResearchSnapshot("Task", sources = listOf(source))
        val report = ResearchReviewRecord("UNAVAILABLE", original.evidenceRevisionDigest(), researchHash("input handoff"), "[REVIEW_UNAVAILABLE] retained final handoff")
        val restored = ResearchSnapshot.parse(original.copy(reviewRecord = report).json())!!
        assertEquals(report, restored.currentReviewRecord())
        assertEquals(null, restored.copy(sources = listOf(source.copy(passage = source.passage + " Changed passage."))).currentReviewRecord())
        assertEquals(null, restored.copy(task = "Different task").currentReviewRecord())
    }
}
