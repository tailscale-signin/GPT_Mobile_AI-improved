package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchClaim
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchDeliveryTest {
    private val passage = "A synthetic randomized trial enrolled 20 participants. Scores were 9 versus 14 points. Follow-up was short and safety remains uncertain."
    private val read = ResearchSource("S1", "https://example.org/study", "Hydrogen inhalation sleep trial", "Read", passage, versionId = "v1")
    private val unread = ResearchSource("S2", "https://example.org/surgery", "Hydrogen inhalation surgery trial", "Failed", failureReason = "Retrieval failed")
    private fun packet() = ResearchDeliveryPacket.build(ResearchSnapshot("Hydrogen inhalation", sources = listOf(read, unread), claims = listOf(ResearchClaim("Synthetic scores", "S1", passage, "Supported", exactQuoteVerified = true)), complete = false), ResearchReportDetail.BRIEF)

    @Test fun acquisitionAndReviewStatesNeverCollapseIntoOneVerifiedCount() {
        val packet = packet()
        assertEquals(listOf("S1"), packet.studies.map { it.id })
        assertEquals(listOf("S2"), packet.unread.map { it.id })
        assertTrue(packet.json().toString().contains("\"complete\":false"))
        assertTrue(packet.json().toString().contains("\"independentReviewClaims\":0"))
        assertTrue(packet.instruction().contains("unread"))
    }

    @Test fun reportDetailDoesNotResetCollectionAuthorityOrBudget() {
        val original = dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings()
        val key = original.collectionPolicyKey()
        for (detail in ResearchReportDetail.entries) assertEquals(key, original.copy(reportDetail = detail).collectionPolicyKey())
        assertTrue(key.endsWith("disabledEngineIds=[])"))
        assertFalse(key == original.copy(userLimits = dev.chungjungsoo.gptmobile.data.model.ResearchUserLimits(maxRequests = 20)).collectionPolicyKey())
        assertEquals(ResearchReportDetail.DETAILED, kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings>("{}").reportDetail)
    }

    @Test fun combinedCorpusPreservesCapturedDateAndStopCause() {
        val snapshot = packet().snapshot.copy(updatedAt = 0, phase = "Partial coverage", cancellationOrigin = "USER_STOP_RESEARCH_AND_SUMMARIZE")
        val frozen = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freeze(snapshot).json().toString()
        assertTrue(frozen.contains("1970-01-01"))
        assertTrue(frozen.contains("USER_STOP_RESEARCH_AND_SUMMARIZE"))
        assertTrue(frozen.contains("Partial coverage"))
    }

    @Test fun unrelatedAndRetractedSourcesCannotDisplaceTheLeadingStudy() {
        val sources = listOf(read, read.copy(id = "S3", url = "https://example.org/other", title = "Unemployment loyalty programs", passage = "Benefits and groundwater", equivalenceGroup = "other"), read.copy(id = "S4", isRetracted = true, equivalenceGroup = "retracted"))
        val selected = ResearchDeliveryPacket.build(ResearchSnapshot("Hydrogen inhalation", sources = sources))
        assertEquals(listOf("S1"), selected.studies.map { it.id })
        assertEquals(2, selected.omitted["irrelevantOrRetracted"])
    }

    @Test fun recentFailedPaperIsPreservedAlongsideReadableSleepTrial() {
        val olderLeads = (3..80).map { unread.copy(id = "S$it", title = "Hydrogen inhalation randomized trial", status = "Metadata only", publicationYear = 2020, equivalenceGroup = "$it") }
        val recent = unread.copy(publicationYear = 2026)
        val selected = ResearchDeliveryPacket.build(ResearchSnapshot("Hydrogen inhalation", sources = olderLeads + read + recent))
        assertTrue(selected.studies.any { it.id == "S1" })
        assertTrue(selected.unread.any { it.id == "S2" })
        assertTrue(ResearchDeliveryPacket.relevance("hydrogen inhalation", "Smoke inhalation trial") == 0)
    }

    @Test fun shortNumericalClaimCannotBypassValidation() {
        assertTrue(ResearchDeliveryValidator.validate("999 patients improved. [S1]", packet()).issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
    }

    @Test fun publisherAlternativesStayOnTheSameHost() {
        assertEquals("https://www.nature.com/articles/example", dev.chungjungsoo.gptmobile.data.research.academic.academicHtmlAlternative("https://www.nature.com/articles/example_reference.pdf"))
        assertEquals("https://www.mdpi.com/1/2/3", dev.chungjungsoo.gptmobile.data.research.academic.academicHtmlAlternative("https://www.mdpi.com/1/2/3/pdf"))
        assertEquals("https://unknown.org/article.pdf", dev.chungjungsoo.gptmobile.data.research.academic.academicHtmlAlternative("https://unknown.org/article.pdf"))
    }

    @Test fun fabricatedQuotesAndNumericalClaimsAreRejected() {
        val packet = packet()
        val text = "Partial coverage. Independent review is not available.\n\nThe synthetic trial enrolled 999 patients and significantly improved recovery outcomes after surgery, with treatment reducing all symptoms. [S1]\n\nReferences: https://example.org/study"
        assertTrue(ResearchDeliveryValidator.validate(text, packet).issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
        val forged = ResearchDeliveryPacket.build(packet.snapshot.copy(claims = listOf(ResearchClaim("Fake", "S1", "This quotation never occurred in the captured source."))))
        assertTrue(forged.claims.isEmpty())
    }

    @Test fun unreadPaperCannotBePresentedAsAReadStudy() {
        val result = ResearchDeliveryValidator.validate("The researchers observed improved recovery in the study, reporting a substantial effect across many participants in surgery. [S2]", packet())
        assertTrue(result.issues.contains("METADATA_PRESENTED_AS_READ"))
        assertTrue(result.issues.contains("PARTIAL_COVERAGE_NOT_DISCLOSED"))
    }

    @Test fun readableVersionWinsOverEarlierMetadataAndRetractionCoversAliases() {
        val lead = read.copy(id = "S3", status = "Discovered", passage = "", retrievalQuality = "METADATA")
        val selected = ResearchDeliveryPacket.build(packet().snapshot.copy(sources = listOf(lead, read)))
        assertEquals(listOf("S1"), selected.studies.map { it.id })
        assertTrue(selected.unread.isEmpty())
        val retracted = ResearchDeliveryPacket.build(packet().snapshot.copy(sources = listOf(lead.copy(isRetracted = true), read)))
        assertTrue(retracted.studies.isEmpty())
    }

    @Test fun importantUnreadLeadMustBeDisclosedInTheReport() {
        val result = ResearchDeliveryValidator.validate("Partial coverage. Unreviewed. [S1] https://example.org/study", packet())
        assertTrue(result.issues.contains("OMITTED_UNREAD_LEAD:S2"))
    }

    @Test fun referencesAloneDoNotProveLeadingStudyCoverage() {
        val result = ResearchDeliveryValidator.validate("Partial coverage. Independent review is not available.\n\n## References\n\n[S1] https://example.org/study", packet())
        assertTrue(result.issues.contains("OMITTED_LEADING_STUDY:S1"))
    }

    @Test fun validSourcedBriefPassesStructuralChecksWithoutClaimingSemanticVerification() {
        val text = "Partial coverage. Independent review is not available.\n\nThe synthetic trial enrolled 20 participants and reported scores of 9 versus 14 points; follow-up was short and safety remains uncertain. [S1]\n\nThe surgery paper is unread; retrieval failed. [S2]\n\n## References\n\n[S1] https://example.org/study\n\n[S2] https://example.org/surgery"
        val result = ResearchDeliveryValidator.validate(text, packet())
        assertEquals(emptyList<String>(), result.issues)
    }

    @Test fun largeCorpusRetainsOmissionAccountingAndAtomicClaims() {
        val sources = (1..100).map { read.copy(id = "S$it", url = "https://example.org/$it", equivalenceGroup = "$it", passage = passage.repeat(20)) }
        val result = ResearchDeliveryPacket.build(ResearchSnapshot("hydrogen", sources = sources), ResearchReportDetail.DETAILED, 4000)
        assertTrue(result.json().toString().toByteArray().size <= 12000)
        assertTrue(result.studies.isNotEmpty())
        assertTrue(result.omitted.getValue("readableByBudget") > 0)
    }

    @Test fun nativeResearchAcquiredDuringTheRoundIsCheckedBeforeDelivery() = runTest {
        var acquired = false
        val native = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                acquired = true
                emit(ProviderEvent.TextDelta("999 patients were cured."))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(native, { if (acquired) packet() else null }, expectResearch = true) { _, _ -> throw java.io.IOException("repair unavailable") }
        val text = session.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertFalse(text.contains("999"))
        assertTrue(text.contains("Partial coverage"))
    }

    @Test fun repairHasADeadlineAndRetainsEvidenceOnTimeout() = runTest {
        val bad = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Unsupported response."))
                emit(ProviderEvent.Completed)
            }
        }
        val stalled = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> { kotlinx.coroutines.awaitCancellation() }
        }
        val session = ResearchDeliverySession(bad, { packet() }) { _, repair -> if (repair) stalled else bad }
        val text = session.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("Partial coverage"))
        assertTrue(text.contains("20 participants"))
    }

    @Test fun oneToolFreeRepairThenSafeEvidenceFallback() = runTest {
        var attempts = 0
        val bad = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Unsubstantiated miracle cure: 999 patients."))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(bad, { packet() }) { _, repair ->
            attempts++
            if (attempts == 2) assertTrue(repair)
            bad
        }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(2, attempts)
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertFalse(text.contains("999"))
        assertTrue(text.contains("20 participants"))
        assertTrue(text.contains("Partial coverage"))
        assertEquals(1, events.count { it == ProviderEvent.Completed })
    }

    @Test fun revokedRepairNeverReleasesCachedEvidenceFallback() = runTest {
        val invalid = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Invalid draft"))
                emit(ProviderEvent.Completed)
            }
        }
        val denied = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.Failed("SOURCE_PERMISSION_REVOKED: source disabled"))
            }
        }
        val session = ResearchDeliverySession(invalid, { packet() }) { _, repair -> if (repair) denied else invalid }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.any { it is ProviderEvent.Failed })
        assertFalse(events.any { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
    }
}
