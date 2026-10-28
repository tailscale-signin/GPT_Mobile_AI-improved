package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExaminationContractTest {
    private val profile = PlatformV2(uid = "selected", name = "Selected", model = "known")
    private val features = AppFeatureSettings(delegation = ModelDelegationSettings(deepResearch = DeepResearchSettings(academicSources = true))).withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.DEEP_RESEARCH, true)
    private val request = ExaminationRequest("work", profile.uid, origin = ExaminationOrigin.USER_EXAMINE)

    @Test fun explicitExamineAtDepthZeroRetainsPaperAndProfileConsent() {
        assertNull(examinationAdmission(features, profile, request, ChatMcpToolConfig()))
        assertTrue(examinationAdmission(features, profile, request.copy(origin = ExaminationOrigin.AUTHORIZED_RESEARCH), ChatMcpToolConfig()) != null)
        assertTrue(examinationAdmission(features.withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, false), profile, request, ChatMcpToolConfig()) != null)
        assertTrue(examinationAdmission(features, profile.copy(disableRemoteTools = true), request, ChatMcpToolConfig()) != null)
        assertTrue(examinationAdmission(features, profile, request, ChatMcpToolConfig(disabledToolIds = setOf("deep_research_read_paper"))) != null)
        assertTrue(examinationAdmission(features.copy(delegation = features.delegation.copy(deepResearch = DeepResearchSettings(academicSources = false))), profile, request, ChatMcpToolConfig()) != null)
    }

    @Test fun automaticChatRequiresItsOwnOptIn() {
        assertTrue(examinationAdmission(features, profile, request.copy(origin = ExaminationOrigin.AUTOMATIC_CHAT), ChatMcpToolConfig()) != null)
        assertNull(examinationAdmission(features.copy(researchFeature = ResearchFeatureSettings(ordinaryChatAutoExamination = true)), profile, request.copy(origin = ExaminationOrigin.AUTOMATIC_CHAT), ChatMcpToolConfig()))
    }

    @Test fun selectedGatewayMustResolveBothReaderOperationsUnlessNativeFallbackWasAccepted() {
        fun tool(operation: String, connectionUid: String?) = ResolvedAgentTool(
            tool = object : AgentTool {
                override val definition = AgentToolDefinition("deep_research_$operation", "test", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject) = error("readiness must not execute tools")
            },
            connectionUid = connectionUid,
            connectionName = "test",
            realToolName = "deep_research_$operation",
            modelToolName = "deep_research_$operation"
        )
        val selected = MultiSearchResearchSelection(gatewayConnectionUid = "gateway")
        val ready = listOf(tool("get_paper", "gateway"), tool("read_paper", "gateway"))

        assertNull(examinationGatewayReadinessBlocker(selected, ready))
        assertTrue(examinationGatewayReadinessBlocker(selected, ready.dropLast(1))!!.contains("read_paper"))
        assertTrue(examinationGatewayReadinessBlocker(selected, listOf(tool("get_paper", "gateway"), tool("read_paper", "other"))) != null)
        assertNull(examinationGatewayReadinessBlocker(selected.copy(allowNativeFallback = true), listOf(tool("get_paper", null), tool("read_paper", null))))
        assertNull(examinationGatewayReadinessBlocker(MultiSearchResearchSelection(), emptyList()))
    }

    @Test fun chunkOffsetsRoundTripUnicodeAndDoNotPretendToInspectFigures() {
        val body = ("🧪 Study methods and results. ".repeat(4000))
        val chunks = examinationChunks(body)
        assertEquals(0, chunks.first().first)
        assertEquals(body.length, chunks.last().first + chunks.last().second.length)
        chunks.zipWithNext().forEach { (a, b) -> assertTrue(b.first > a.first && b.first < a.first + a.second.length) }
        chunks.forEach { (offset, text) -> assertEquals(text, body.substring(offset, offset + text.length)) }
        assertFalse(ExaminationCheckpoint(nextChunk = chunks.size, chunkCount = chunks.size).coverage.complete)
        assertTrue(runCatching { examinationChunks("x".repeat(240001)) }.isFailure)
    }

    @Test fun unfavorableReviewIsACompleteResultBoundToAnExactArtifact() {
        val reply = """{"schemaVersion":1,"artifactHash":"original","verdict":"rejected","reasons":["The estimate is from an uncontrolled comparison"]}"""
        assertEquals("rejected", validateIndependentAppraisalReview(reply, "original"))
        assertTrue(runCatching { validateIndependentAppraisalReview(reply, "edited") }.isFailure)
        assertTrue(runCatching { validateIndependentAppraisalReview(reply.dropLast(1), "original") }.isFailure)
    }

    @Test fun conflictingStudyCountsKeepBothLocatorsAndNeverOverwriteWithMissingData() {
        fun field(value: String) = StudyField("id", "version", "overall", "analyzed_n", value, null, Missingness.PRESENT, Json.encodeToString(listOf("passage:$value")), "extractive")
        val merged = mergeScientificField(field("100"), field("92"))
        assertEquals(Missingness.CONFLICTING, merged.missingness)
        assertNull(merged.value)
        assertEquals(listOf("passage:100", "passage:92"), Json.decodeFromString<List<String>>(merged.passageIdsJson))
        assertEquals(field("100"), mergeScientificField(field("100"), field("100").copy(value = null, missingness = Missingness.NOT_REPORTED)))
    }

    @Test fun citationResolverIgnoresCodeAndTitleSimilarityAndPreservesArxivVersion() {
        val content = """
            A claim [S123abc] and DOI:10.1234/example.

            `PMID:1234` is an example, not evidence.
            ```
            [S999] https://example.com/fake
            ```

            A second source arxiv:2401.12345v2 and PMCID:PMC123456.
            A similar title does not identify any work.
        """.trimIndent()
        val keys = articleCitationOccurrences(content).map { it.key }
        assertEquals(listOf("S123abc", "doi:10.1234/example", "arxiv:2401.12345v2", "pmcid:PMC123456"), keys)
        assertTrue(articleCitationOccurrences("A study with a similar title").isEmpty())
    }

    @Test fun resumeCheckpointKeepsReviewReasonsSourceCheckAndPhysicalInvocations() {
        val report = IndependentAppraisalReport("appraisal", "immutable-hash", "reviewer", "canonical-model", "rejected", listOf("No randomized comparison"), listOf("review-attempt"))
        val receipt = SourceValidationReceipt(versionId = "version", contentHash = "hash", retrievedAt = 123L, status = "metadata_checked_abstract_only", owner = "native")
        val checkpoint = ExaminationCheckpoint(rootWorkId = "original-task", parentRunId = "generation", nextChunk = 1, invocationIds = listOf("extract-attempt", "review-attempt"), independentReports = listOf(report), sourceValidation = receipt)
        assertEquals(checkpoint, Json.decodeFromString<ExaminationCheckpoint>(Json.encodeToString(checkpoint)))
        assertTrue(Json.decodeFromString<ExaminationCheckpoint>("{}").independentReports.isEmpty())
        assertFalse(checkpoint.coverage.complete)
    }

    @Test fun researchCardsFollowCitedParagraphsWithoutSplittingFencedCode() {
        val text = "Introduction.\n\nFirst finding [Sabc].\n\n```\nnot a citation [S999]\n\ncode continues\n```\n\nSecond finding DOI:10.1234/example."
        val blocks = researchResponseBlocks(text)
        assertEquals(2, blocks.size)
        assertTrue(blocks.first().text.contains("First finding"))
        assertTrue(blocks.last().text.contains("```\nnot a citation [S999]\n\ncode continues\n```"))
        assertEquals(listOf("Sabc", "doi:10.1234/example"), blocks.flatMap { articleCitationOccurrences(it.citationText).map { occurrence -> occurrence.key } })
    }

    @Test fun onlyCompleteStructuredTextCanJustifyAbsentFiguresAndSupplements() {
        val evidence = dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence("paper", "v1", "https://example.org/article", 1, "hash", "methods results limitations", "read", structuredFullText = true, sectionNames = setOf("Methods", "Results", "Limitations").map { it.lowercase() }.toSet(), declaredFigures = 0, declaredSupplements = 0, declaredTables = 0)
        assertTrue(verifiedStructuredCoverage(evidence).complete)
        assertFalse(verifiedStructuredCoverage(evidence.copy(structuredFullText = false)).complete)
        assertFalse(verifiedStructuredCoverage(evidence.copy(declaredFigures = 1)).complete)
        assertFalse(verifiedStructuredCoverage(evidence.copy(retrievalStatus = "partially_read")).complete)
    }
}
