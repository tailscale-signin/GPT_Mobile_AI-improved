package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedPortugalRegressionTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/combined/portugal-2026-10-23.json")).readText()).jsonObject
    private val sources = fixture.getValue("sources").jsonArray.map { source ->
        val record = source.jsonObject
        CombinedModelResponse(record.getValue("platformUid").jsonPrimitive.content, record.getValue("platformName").jsonPrimitive.content, content = record.getValue("content").jsonPrimitive.content)
    }

    @Test fun mergedDatesRetainTheEventAssociations() {
        val correct = "Bartolomeu Dias rounded the Cape of Good Hope in 1488, and Vasco da Gama reached India in 1498."
        val wrong = "Bartolomeu Dias rounded the Cape of Good Hope in 1498, and Vasco da Gama reached India in 1488."
        val source = "Vasco da Gama reached India in 1498."
        assertTrue(CombinedClaimMatcher.match(source, correct) >= CombinedClaimMatcher.Match.PARAPHRASE)
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(source, wrong))
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match(source, "It is not true that Bartolomeu Dias rounded the Cape in 1488 and Vasco da Gama reached India in 1498."))
        assertEquals(CombinedClaimMatcher.Match.NONE, CombinedClaimMatcher.match("The town built 12 homes and 14 schools.", "The town built 14 homes and 12 schools."))
    }

    @Test fun exportedTreatyAndSlaveTradeRetainDistinctContributors() {
        val answer = fixture.getValue("latest").jsonPrimitive.content
        val spans = combinedWordingSpans(answer, sources)
        assertTrue(spans.any { "builtin-free-bridgenode" in it.profileUids && "Tordesillas" in answer.substring(it.start, it.end) })
        assertTrue(spans.any { "builtin-free-vireonix" in it.profileUids && "slave trade" in answer.substring(it.start, it.end) })
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test fun exportedIncompleteAnswersFailLengthAndHistoricalScopeLocally() {
        val request = fixture.getValue("request").jsonPrimitive.content
        val policy = CombinedAnswerPolicy.from(request)
        for (key in listOf("latest", "previous")) {
            val answer = fixture.getValue(key).jsonPrimitive.content
            assertTrue(policy.violations(answer).isNotEmpty())
        }
        val assessed = CombinedFinalCheck.check(fixture.getValue("latest").jsonPrimitive.content, sources, CombinedEvidenceLedger(), policy)
        assertTrue(assessed.checks.any { it.category == "SCOPE" && it.result == "FAIL" })
        assertFalse(assessed.receipts.any { it.evidenceIds.isNotEmpty() })
    }

    @Test fun completeReferencePassesLocalDeliveryWithoutAnotherGeneration() = kotlinx.coroutines.test.runTest {
        // A deterministic candidate assembled from retained source text, not a claim that
        // a live provider has passed this device/model calibration or that its facts are verified.
        val request = fixture.getValue("request").jsonPrimitive.content
        val answer = fixture.getValue("completeTestCandidate").jsonPrimitive.content
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), sources.map { it.platformUid }, sources.associate { it.platformUid to "model" }, request, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "complete-reference")
        var generations = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = kotlinx.coroutines.flow.flow {
                generations++
                emit(ProviderEvent.TextDelta(answer))
                emit(ProviderEvent.Completed)
            }
        }
        val session = CombinedDeliverySession(provider, sources, 20000, validateAccess = {}, originalRequest = request, combined = context, verify = { error("No verifier request") }, repairDraft = { error("No rewrite request") }) { _, _ -> error("No continuation request") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val report = events.combinedReport()
        assertEquals(1, generations)
        assertTrue("${report.wordCount} words: ${report.issues}", report.complete)
        assertTrue(report.wordCount in 450..550)
        assertEquals("MET", report.lengthCompliance)
        assertEquals("LOCAL_CHECKS_PASSED", report.topicCoverage)
        assertFalse(report.factualAccuracyVerified)
        assertTrue("builtin-free-bridgenode" in report.representedProfileUids)
        assertTrue("builtin-free-vireonix" in report.representedProfileUids)
    }

    @Test fun missingHistoricalScopeDoesNotClaimAFactualContradiction() = kotlinx.coroutines.test.runTest {
        val request = fixture.getValue("request").jsonPrimitive.content
        val answer = fixture.getValue("latest").jsonPrimitive.content
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), sources.map { it.platformUid }, sources.associate { it.platformUid to "model" }, request, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "partial-reference")
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = kotlinx.coroutines.flow.flowOf(ProviderEvent.TextDelta(answer), ProviderEvent.Completed)
        }
        val session = CombinedDeliverySession(provider, sources, 20000, validateAccess = {}, originalRequest = request, combined = context) { _, _ -> error("No rewrite request") }
        val report = session.streamRound(emptyList(), emptyList()).toList().combinedReport()
        assertFalse(report.complete)
        assertEquals("PARTIAL", report.deliveredScopeStatus)
        assertEquals("UNMET", report.lengthCompliance)
        assertEquals("UNRESOLVED", report.topicCoverage)
        assertEquals("UNVERIFIED", report.verificationStatus)
    }

    @Test fun compactPromptPreservesFullBodiesAndResolvableClaimOffsets() {
        val request = fixture.getValue("request").jsonPrimitive.content
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), sources.map { it.platformUid }, sources.associate { it.platformUid to "model" }, request, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "portugal-root")
        val prompt = dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt(request, sources, combined = context, deliveryProtocol = "PASSAGES_V3")
        val envelope = Json.parseToJsonElement(prompt).jsonObject
        val bodies = envelope.getValue("contributions").jsonArray.map { it.jsonObject.getValue("response").jsonPrimitive.content }
        assertEquals(sources.map { it.content }, bodies)
        assertTrue("Prompt was ${prompt.length} chars", prompt.length < 40_000)
        val claims = envelope.getValue("claim_inventory").jsonArray
        val index = CombinedContentIndexes.get(sources)
        assertEquals(index.records.size, claims.size)
        assertTrue(claims.any { "source_ranges_utf16" in it.jsonObject })
        for (entry in claims) {
            val claim = entry.jsonObject
            val record = requireNotNull(index.byId[claim.getValue("id").jsonPrimitive.content])
            assertFalse("canonical_id" in claim)
            assertEquals(record, index.byId[record.canonicalId])
            val aliases = claim.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(record.candidateIds, aliases.toSet())
            assertTrue(aliases.all { index.byId[it] == record })
            claim["source_ranges_utf16"]?.jsonArray?.let { ranges ->
                assertEquals(record.occurrences.size, ranges.size)
                ranges.forEachIndexed { occurrenceIndex, raw ->
                    val range = raw.jsonArray
                    val source = bodies[range[0].jsonPrimitive.content.removePrefix("C").toInt() - 1]
                    val start = range[1].jsonPrimitive.content.toInt()
                    val end = range[2].jsonPrimitive.content.toInt()
                    val occurrence = record.occurrences[occurrenceIndex]
                    val expected = requireNotNull(occurrence.sourceSpan)
                    assertEquals(occurrence.candidateId.substringBefore('.'), range[0].jsonPrimitive.content)
                    assertEquals(expected.startUtf16, start)
                    assertEquals(expected.endUtf16, end)
                    val exact = source.substring(start, end)
                    assertTrue(exact.isNotBlank())
                    assertEquals(expected.textDigest, dev.chungjungsoo.gptmobile.data.research.researchHash(exact))
                }
            }
        }
    }

    @Test fun shorterExportedRetryRemainsSeparateFromBetterRetainedRevision() {
        val previous = fixture.getValue("previous").jsonPrimitive.content
        val latest = fixture.getValue("latest").jsonPrimitive.content
        val old = dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision(content = previous, createdAt = 1, runId = "combined-synthesis:old")
        val message = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(content = "", platformType = sources.first().platformUid, currentRunId = "combined-synthesis:new", revisions = listOf(old), activeRevisionIndex = 0)
        val snapshot = CombinedInputSnapshot(userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(platformType = null, content = fixture.getValue("request").jsonPrimitive.content), history = emptyList(), replies = emptyList(), sources = sources, prompt = "frozen", profileUid = sources.first().platformUid, model = "model", systemPrompt = "", configKey = "key", resumeFromRunId = old.runId, deliveryProtocol = "PASSAGES_V3")
        val run = dev.chungjungsoo.gptmobile.data.database.entity.AgentRun("combined-synthesis:new", 1, 2, 3, sources.first().platformUid, "CUSTOM", "model", synthesisSnapshot = Json.encodeToString(CombinedInputSnapshot.serializer(), snapshot))
        val report = CombinedQualityReport(answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(latest), deliveredScopeStatus = "PARTIAL", passages = listOf(CombinedPassageRecord(0, latest.length, dev.chungjungsoo.gptmobile.data.research.researchHash(latest), setOf(sources.first().platformUid))))
        val candidate = message.copy(content = latest, timeline = listOf(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, combinedQuality = report)))
        for (status in listOf(null, "FAILED", "INTERRUPTED", "COMPLETED")) {
            val retained = preserveCombinedRetrySelection(message, candidate, run, status)
            assertEquals(0, retained.activeRevisionIndex)
            assertEquals(previous, retained.revisions.single().content)
            assertEquals(latest, retained.content)
        }
    }

    @Test fun numericCenturiesCountAsScopeEvenAlongsideLaterEvents() {
        assertEquals(setOf(3), CombinedHistoryOutline.periods("The 19th century brought political instability and social change."))
        assertEquals(setOf(3, 4), CombinedHistoryOutline.periods("The 19th century brought changes culminating in the republic in 1910."))
        assertEquals(setOf(4, 5), CombinedHistoryOutline.periods("The late 20th and early 21st centuries brought economic development."))
    }

    @Test fun sharedEntitiesAreNotSplitIntoUnrelatedClauses() {
        val treaty = "The 1494 Treaty of Tordesillas divided newly discovered lands between Portugal and Spain."
        assertEquals(1, CombinedClaimClauses.spans(treaty).size)
        val answer = fixture.getValue("latest").jsonPrimitive.content
        assertTrue(combinedWordingSpans(answer, sources).any { "builtin-free-vireonix" in it.profileUids && "Celtic and Lusitanian peoples" in answer.substring(it.start, it.end) })
        assertEquals(emptyList<CombinedWordingSpan>(), combinedWordingSpans("Portugal joined the EU in 1995.", listOf(CombinedModelResponse("p", "Profile", content = "Portugal joined the EU in 1986."))))
    }

    @Test fun retryClassificationSurvivesIsoCooldownAndDemoExclusion() {
        val rateLimited = ProfileFailureRecord.classify("Rate limit. Next eligible retry: 2026-10-24T01:02:03Z")
        assertEquals("RATE_LIMIT", rateLimited.category)
        assertEquals(java.time.Instant.parse("2026-10-24T01:02:03Z").toEpochMilli(), rateLimited.retryAfterMillis)
        assertFalse(ProfileFailureRecord.classify("No usable answer", demoExcluded = true).retryAllowed)
        val restored = Json.decodeFromString(ProfileFailureRecord.serializer(), Json.encodeToString(ProfileFailureRecord.serializer(), rateLimited))
        assertEquals(rateLimited, restored)
    }
}
