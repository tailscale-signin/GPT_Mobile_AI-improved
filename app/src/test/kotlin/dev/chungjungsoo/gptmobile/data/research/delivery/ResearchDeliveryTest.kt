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
    @Test fun unreadOnlyCorpusReturnsPartialWithoutRepeatedWriterRepairs() = runTest {
        val evidence = ResearchDeliveryPacket.build(ResearchSnapshot("Hydrogen inhalation", sources = listOf(unread), complete = false), ResearchReportDetail.BRIEF)
        var writerCalls = 0
        val writer = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                emit(ProviderEvent.TextDelta("No readable study was obtained."))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(writer, { evidence }, true) { _, _ ->
            writerCalls++
            writer
        }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, writerCalls)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().any { it.text.contains("S2") })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().any { it.message.startsWith("RESEARCH_PARTIAL") })
        assertFalse(events.any { it == ProviderEvent.Completed })
    }

    private val passage = "A synthetic randomized trial enrolled 20 participants. Scores were 9 versus 14 points. Follow-up was short and safety remains uncertain."
    private val read = ResearchSource("S1", "https://example.org/study", "Hydrogen inhalation sleep trial", "Read", passage, versionId = "v1")
    private val unread = ResearchSource("S2", "https://example.org/surgery", "Hydrogen inhalation surgery trial", "Failed", failureReason = "Retrieval failed")
    private fun packet() = ResearchDeliveryPacket.build(ResearchSnapshot("Hydrogen inhalation", sources = listOf(read, unread), claims = listOf(ResearchClaim("Synthetic scores", "S1", passage, "Supported", exactQuoteVerified = true)), complete = false), ResearchReportDetail.BRIEF)

    @Test fun defaultContributorBudgetRetainsExactEvidenceAlongsideQuestionsAndLimitations() {
        val snapshot = packet().snapshot.copy(
            questionPlan = listOf(dev.chungjungsoo.gptmobile.data.research.ResearchQuestion("Q1", "What did the trial measure?", "Exact passage")),
            notes = listOf("Partial coverage; independent review unavailable.", "Additional context".repeat(100))
        )
        val brief = ResearchDeliveryPacket.build(snapshot, ResearchReportDetail.BRIEF, tokenBudget = 512)
        assertTrue(brief.studies.isNotEmpty())
        val serialized = brief.json().toString()
        assertTrue(serialized.contains("20 participants"))
        assertTrue(serialized.contains(read.url))
        assertTrue(serialized.contains("Q1"))
        assertTrue(serialized.contains("Partial coverage"))
        assertTrue(serialized.toByteArray().size <= 512 * 3)
    }

    @Test fun smallHandoffRetainsReadableEvidenceBeforeUnreadLeads() {
        val readable = read.copy(passage = passage.repeat(30))
        val leads = (2..18).map { unread.copy(id = "S$it", equivalenceGroup = "lead-$it") }
        val packet = ResearchDeliveryPacket.build(ResearchSnapshot("Hydrogen inhalation", sources = listOf(readable) + leads), tokenBudget = 2048)
        assertEquals(listOf("S1"), packet.studies.map { it.id })
        assertTrue(packet.json().toString().toByteArray().size <= 2048 * 3)
        assertTrue(packet.json().toString().contains("A synthetic randomized trial"))
        assertEquals(readable.passage, packet.studies.single().passage)
    }

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

    @Test fun headingWithoutBlankLineDoesNotContaminateTheFollowingParagraph() {
        val result = ResearchDeliveryValidator.validate("## Leading studies\nIndependent review is not completed.\n\n## Findings\nPatients improved after surgery.", packet())
        assertTrue(result.issues.contains("UNSOURCED_SCIENTIFIC_PARAGRAPH"))
        assertEquals(1, result.repairHints.size)
        assertTrue(result.repairHints.single().contains("Patients improved"))
        assertFalse(result.repairHints.single().contains("Leading studies"))
    }

    @Test fun shortNumericalClaimCannotBypassValidation() {
        assertTrue(ResearchDeliveryValidator.validate("999 patients improved. [S1]", packet()).issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
    }

    @Test fun hashPrefixedClaimsAreNotMistakenForMarkdownHeadings() {
        for (prefix in listOf("#1", "#######")) {
            val result = ResearchDeliveryValidator.validate("$prefix Patients improved after surgery.", packet())
            assertTrue(result.issues.contains("UNSOURCED_SCIENTIFIC_PARAGRAPH"))
        }
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
        assertEquals(0, events.count { it == ProviderEvent.Completed })
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.startsWith("RESEARCH_PARTIAL:"))
    }

    @Test fun completedResearchDisablesToolsDuringFirstFinalDraft() = runTest {
        val complete = packet().copy(snapshot = packet().snapshot.copy(complete = true))
        var toolsAdvertised = -1
        var requestedToolFree = false
        val writer = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                toolsAdvertised = tools.size
                emit(ProviderEvent.TextDelta("A short invalid draft"))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(writer, { complete }) { _, toolFree ->
            requestedToolFree = requestedToolFree || toolFree
            writer
        }
        session.streamRound(listOf(AgentToolDefinition("external", "External", kotlinx.serialization.json.JsonObject(emptyMap()))), emptyList()).toList()
        assertTrue(requestedToolFree)
        assertEquals(0, toolsAdvertised)
    }

    @Test fun detailedReportRetriesInToolFreeChaptersBeforeEvidenceFallback() = runTest {
        val detailed = packet().copy(snapshot = packet().snapshot.copy(task = "Hydrogen inhalation clinical trials"), detail = ResearchReportDetail.DETAILED)
        var generated = 0
        val headerOne = """
            ## Executive findings
            Partial coverage. Independent review is not available.

            ## Scientific background
            No verified scientific background is available.

            ## Leading studies
            A study is captured [S1]; only its source passage was reviewed.

            ## Latest papers
            The unread paper [S2] was not read; retrieval failed.
        """.trimIndent()
        val headerTwo = """
            ## Comparison of evidence
            No verified comparison is available.

            ## Safety and limitations
            Safety evidence is not verified.

            ## Research gaps and outlook
            Independent research gaps remain unverified.

            ## References
            [S1] https://example.org/study
            [S2] https://example.org/surgery
        """.trimIndent()
        val writer = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                generated++
                val answer = when (generated) {
                    3 -> headerOne
                    4 -> headerTwo
                    else -> "Unsupported unsourced draft."
                }
                emit(ProviderEvent.TextDelta(answer))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(writer, { detailed }) { _, _ -> writer }
        val output = session.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text }
        assertEquals(4, generated)
        assertTrue(output.contains("## Executive findings"))
        assertTrue(output.contains("## References"))
        assertFalse(output.contains("captured passage"))
    }

    @Test fun completeResearchPreservesOnlyTheLocalSavedEvidenceReader() = runTest {
        val complete = packet().copy(snapshot = packet().snapshot.copy(complete = true))
        val advertised = mutableListOf<String>()
        val writer = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                advertised += tools.map { it.name }
                emit(ProviderEvent.TextDelta("Unsupported draft"))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(writer, { complete }) { _, _ -> writer }
        val tools = listOf(
            AgentToolDefinition("read_recovery_context", "Read saved work", kotlinx.serialization.json.JsonObject(emptyMap())),
            AgentToolDefinition("read_url", "Read network URL", kotlinx.serialization.json.JsonObject(emptyMap())),
            AgentToolDefinition("discover_available_tools", "Find external tools", kotlinx.serialization.json.JsonObject(emptyMap()))
        )
        session.streamRound(tools, emptyList()).toList()
        assertEquals(listOf("read_recovery_context"), advertised)
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

    @Test fun writerTransportFailurePreservesTheVisibleDraftBeforeReportingFailure() = runTest {
        val interrupted = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("The available trial enrolled 20 participants. [S1]"))
                emit(ProviderEvent.Failed("NETWORK_TIMEOUT"))
            }
        }
        val session = ResearchDeliverySession(interrupted, { packet() }) { _, _ -> interrupted }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("20 participants"))
        assertTrue(events.indexOfFirst { it is ProviderEvent.TextDelta } < events.indexOfFirst { it is ProviderEvent.Failed })
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun emptyExplicitAcquisitionCannotBypassTheEvidenceGuard() = runTest {
        val draft = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Unsupported complete report."))
                emit(ProviderEvent.Completed)
            }
        }
        val session = ResearchDeliverySession(draft, { null }, expectResearch = true) { _, _ -> error("No repair without evidence") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.any { it is ProviderEvent.Failed && it.message.startsWith("RESEARCH_PARTIAL") })
        assertFalse(events.any { it == ProviderEvent.Completed })
        assertFalse(events.filterIsInstance<ProviderEvent.TextDelta>().any { "Unsupported" in it.text })
    }
}
