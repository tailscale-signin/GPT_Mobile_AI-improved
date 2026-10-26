package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedContributionUsability
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisInputs
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CombinedDeliveryRepairTest {
    private fun profile(uid: String) = PlatformV2(uid = uid, name = uid, compatibleType = ClientType.CUSTOM, apiUrl = "https://example.org/v1", model = uid, maxTokens = 8192)
    private val france = "History of France in 250 words"

    @Before fun reset() {
        CombinedWriterObservations.clearForTest()
        FreeCatalogState.resetForTest()
    }

    @After fun clear() {
        CombinedWriterObservations.clearForTest()
    }

    @Test fun actualHordeCapacityIsResolvedBeforeRankingAndContinuationPlanning() {
        val horde = FreeAiProvider.AI_HORDE.applyTo(profile("builtin-free-ai_horde")).copy(model = "horde-text")
        assertEquals(256, ProviderRouteCapacity.output(horde, 3188))
        assertEquals(256, ProviderRouteCapacity.output(horde, 5381))
        assertNotNull(CombinedWriterPolicy.unavailableReason(horde, france))
        assertEquals(listOf("z-writer"), CombinedWriterPolicy.rank(listOf(horde, profile("z-writer")), france, horde.uid).map { it.uid })
        assertTrue(CombinedWriterPolicy.requiredOutput(france, profile("z")) > 256)
    }

    @Test fun preferredWriterWinsAmongEquivalentRoutesButStalledWriterDoesNot() {
        val preferred = profile("z-preferred")
        val other = profile("a-other")
        assertEquals(preferred, CombinedWriterPolicy.rank(listOf(other, preferred), france, preferred.uid).first())
        repeat(2) { CombinedWriterObservations.record(CombinedWriterObservations.key(preferred), true, 0) }
        assertEquals(listOf(other), CombinedWriterPolicy.rank(listOf(preferred, other), france, preferred.uid))
    }

    @Test fun resolvedAliasObservationsDoNotCertifyStructuredDeliveryFromVisibleBytes() {
        val p = profile("alias")
        val key = CombinedWriterObservations.key(p)
        CombinedWriterObservations.recordRoute(key, "real-model-1", "host", "reasoning.enabled=false", true)
        assertEquals(0, CombinedWriterObservations.get(key)!!.completedPassages)
        CombinedWriterObservations.record(key, true, 0)
        assertEquals(1, CombinedWriterObservations.get(key)!!.reasoningOnlyAttempts)
        CombinedWriterObservations.recordRoute(key, "real-model-2", "host", null, false)
        assertEquals(0, CombinedWriterObservations.get(key)!!.reasoningOnlyAttempts)
        assertEquals("real-model-2", CombinedWriterObservations.get(key)!!.servedModel)
    }

    @Test fun visiblePassagesAloneNeverCalibrateACompleteAnswer() {
        val p = profile("calibration")
        val key = CombinedWriterObservations.key(p)
        CombinedWriterObservations.record(key, false, 4, 3688)
        assertEquals(0, CombinedWriterObservations.get(key)?.completedPassages)
        assertEquals(4, CombinedWriterObservations.get(key)?.observedPassages)
        assertEquals(null, CombinedWriterObservations.get(key)?.minimumUsefulOutputAllowance)
        val partial = CombinedQualityReport(answerDigest = "partial", complete = false, deliveredScopeStatus = "PARTIAL", finalAssessment = true)
        CombinedWriterObservations.recordDelivery(key, partial, 3688)
        assertEquals(0, CombinedWriterObservations.get(key)?.completeAnswers)
        assertEquals(1, CombinedWriterObservations.get(key)?.incompleteAnswers)
        assertEquals(null, CombinedWriterObservations.get(key)?.minimumUsefulOutputAllowance)
        CombinedWriterObservations.recordDelivery(key, partial.copy(answerDigest = "complete", complete = true, deliveredScopeStatus = "COMPLETED", lengthCompliance = "MET", topicCoverage = "LOCAL_CHECKS_PASSED"), 6200)
        assertEquals(1, CombinedWriterObservations.get(key)?.completeAnswers)
        assertEquals(6200, CombinedWriterObservations.get(key)?.minimumUsefulOutputAllowance)
    }

    @Test fun delayedUsageStaysWithItsServedRouteAfterAnotherAliasResolves() {
        val key = CombinedWriterObservations.key(profile("auto-route"))
        val first = CombinedWriterObservations.recordRoute(key, "reasoner", "backend-a", "reasoning.enabled=false", true)
        val second = CombinedWriterObservations.recordRoute(key, "writer", "backend-b", "reasoning.enabled=false", false)
        CombinedWriterObservations.recordUsage(first, 2878, "length", 3688)
        CombinedWriterObservations.recordUsage(second, 0, "stop", 4096)
        assertEquals(2878, CombinedWriterObservations.get(first)?.reasoningTokensHighWater)
        assertEquals(3688, CombinedWriterObservations.get(first)?.stalledOutputAllowance)
        assertEquals(0, CombinedWriterObservations.get(second)?.reasoningTokensHighWater)
        assertEquals(null, CombinedWriterObservations.get(second)?.stalledOutputAllowance)
        assertEquals("writer", CombinedWriterObservations.get(key)?.servedModel)
    }

    @Test fun reasoningUsageIsReservedAndCannotBeErasedByVisibleText() {
        val p = profile("reasoning")
        val key = CombinedWriterObservations.key(p)
        CombinedWriterObservations.recordRoute(key, p.model, null, "reasoning.enabled=false", false)
        CombinedWriterObservations.recordUsage(key, 2878)
        CombinedWriterObservations.record(key, false, 3, 3688)
        assertEquals("NOT_HONORED", CombinedWriterObservations.get(key)?.reasoningDisabled)
        assertTrue(CombinedWriterPolicy.requiredOutput("History of Portugal in 500 words", p) > 500 * 2.2 + 512 + 2878)
        val limited = p.copy(maxTokens = 4096)
        assertNotNull(CombinedWriterPolicy.unavailableReason(limited, "History of Portugal in 500 words"))
        assertEquals(listOf(profile("compatible")), CombinedWriterPolicy.rank(listOf(limited, profile("compatible")), "History of Portugal in 500 words", limited.uid))
    }

    @Test fun outputLimitWithVisibleProgressRaisesTheNextBudgetWithoutCalibratingSuccess() {
        val writer = profile("partial-limit")
        val key = CombinedWriterObservations.key(writer)
        CombinedWriterObservations.recordUsage(key, null, "length", 6200)
        CombinedWriterObservations.record(key, false, 3, 6200)
        CombinedWriterObservations.recordDelivery(key, CombinedQualityReport(answerDigest = "partial", deliveredScopeStatus = "PARTIAL", finalAssessment = true), 6200)
        assertEquals(6200, CombinedWriterObservations.get(key)?.stalledOutputAllowance)
        assertEquals(null, CombinedWriterObservations.get(key)?.minimumUsefulOutputAllowance)
        assertEquals(0, CombinedWriterObservations.get(key)?.completedPassages)
        assertTrue(CombinedWriterPolicy.requiredOutput(france, writer) > 6200)
    }

    @Test fun typedRecoveryRotatesFailedWritersButRetainsPendingRemoteJobsAndStopsAuthorityFailures() {
        for (code in listOf("INVALID_DELIVERY_RECORD:reason", "COMBINED_WRITER_STALLED", "NETWORK_ERROR")) {
            assertTrue(code, CombinedRecoveryFailure.from(ProviderEvent.Failed("provider detail", errorCode = code)).rotateWriter)
        }
        val pending = CombinedRecoveryFailure.fromCode("QUEUE_JOB_PENDING")
        assertFalse(pending.rotateWriter)
        assertEquals("STOP", pending.decision)
        assertEquals("WAITING_REMOTE_JOB", pending.localAttemptStatus)
        assertEquals("PENDING", pending.remoteJobStatus)
        assertEquals("INCOMPLETE", pending.deliveryStatus)
        assertFalse(CombinedRecoveryFailure.fromCode("COMBINED_SUCCESSOR_AUTHORITY_CHANGED").rotateWriter)
        assertFalse(CombinedRecoveryFailure.fromCode("401").rotateWriter)
        assertFalse(CombinedRecoveryFailure.from(ProviderEvent.Failed("An arbitrary answer mentioning timeout and network failure")).rotateWriter)
        assertEquals("RESUME_SAVED_RECORD", CombinedRecoveryFailure.fromCode("COMBINED_PARTIAL").decision)
    }

    @Test fun malformedAndQueueDelayedRoutesAreExcludedFromLaterWriterSelection() {
        val bad = profile("bad")
        val good = profile("good")
        CombinedWriterObservations.recordFailure(CombinedWriterObservations.key(bad), CombinedRecoveryCategory.MALFORMED_RECORD)
        assertEquals(listOf(good), CombinedWriterPolicy.rank(listOf(bad, good), france, bad.uid))
        CombinedWriterObservations.clearForTest()
        CombinedWriterObservations.recordFailure(CombinedWriterObservations.key(bad), CombinedRecoveryCategory.QUEUE_PENDING)
        assertEquals(listOf(good), CombinedWriterPolicy.rank(listOf(bad, good), france, bad.uid))
    }

    @Test fun clippedHordeRepliesRemainUsefulPartialContributions() {
        for ((request, text) in listOf(
            france to "France changed through centuries of conflict and political transformation. The Renaissance saw a resurgence of art,",
            "explain the history of Portugal" to "Portugal developed from several ancient societies and later medieval kingdoms. In 868, the Visigoths were defeated"
        )) {
            val assessment = CombinedContributionUsability.assess("COMPLETED", text, request = request, outputAllowance = 256)
            assertTrue(assessment.admitted)
            assertTrue(assessment.usablePartial)
            assertEquals("PARTIAL", assessment.completeness)
            val sources = combinedSynthesisInputs(listOf(MessageV2(platformType = "builtin-free-ai_horde", content = text)), emptyMap(), emptyMap(), emptyMap(), request)!!
            assertEquals(ContributionDisposition.USABLE_PARTIAL, sources.single().disposition)
            assertEquals("COMPLETED", sources.single().completionStatus)
            assertEquals("UNVERIFIED", sources.single().factualSupport)
        }
        for (text in listOf("OK", "Test received.", "- 1789: Revolution\n- 1958: Fifth Republic", "```kotlin\nval x = 1\n```")) {
            assertFalse(text, CombinedContributionUsability.assess("COMPLETED", text, request = "test").usablePartial)
        }
        assertTrue(CombinedContributionUsability.assess("COMPLETED", "A sentence that looks finished.", finishReason = "length").usablePartial)
    }

    @Test fun chronologyReservesAncientCoverageAndDifferentEventsAreNotConflicts() {
        val sentences = listOf(
            "Julius Caesar conquered Roman Gaul in 52 BC.",
            "The Capetian dynasty consolidated the French monarchy during medieval times.",
            "The French Revolution began in 1789 and changed political institutions.",
            "Napoleon seized power in France in 1799.",
            "Napoleon became emperor of France in 1804.",
            "Napoleon was crowned emperor of France in 1804.",
            "The French Fifth Republic was established in 1958.",
            "France established its Fifth Republic in 1958.",
            "France participated in European integration and the European Union."
        )
        val claims = CombinedClaimSemantics.records(sentences.mapIndexed { i, text -> CombinedModelResponse("p$i", "P$i", content = text) })
        val selected = CombinedEditorialInventory.select(claims, france, CombinedAnswerPolicy.from(france))
        assertTrue(selected.any { it.proposition.contains("Gaul") })
        assertTrue(selected.flatMap { CombinedHistoryOutline.periods(it.proposition) }.containsAll(setOf(0, 1, 2, 3, 4, 5)))
        assertEquals(1, selected.count { it.proposition.contains("1804") })
        assertEquals(2, selected.single { it.proposition.contains("1804") }.profileUids.size)
        assertFalse(CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint(sentences[3]), CombinedClaimMatcher.fingerprint(sentences[4])))
        assertFalse(CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint("Henry the Navigator was not himself a sailor."), CombinedClaimMatcher.fingerprint("Henry the Navigator sponsored Portuguese voyages.")))
        assertEquals(setOf(1), CombinedHistoryOutline.periods("After Rome collapsed, medieval France developed feudal institutions."))
        assertEquals(setOf(3), CombinedHistoryOutline.periods(sentences[4]))
    }

    @Test fun toolFreeContributorInstructionIncludesRoleAssignmentAndActualCap() {
        val horde = FreeAiProvider.AI_HORDE.applyTo(profile("horde"))
        val requested = CombinedRequestedPolicy(userPrompt = france, participantUids = listOf("horde"), requestedModels = mapOf("horde" to horde.model))
        val instruction = combinedContributorInstruction(horde, CombinedExecutionContext(requested, rootBudgetId = "test", role = CombinedRunRole.CONTRIBUTOR, profileUid = "horde"))
        assertTrue(instruction.contains("contributor to a Combined answer"))
        assertTrue(instruction.contains("256 generation tokens"))
        assertTrue(instruction.contains("Complementary assignment"))
    }

    @Test fun testAcknowledgmentUsesCurrentSourceAndNoAdditionalProviderSession() = runTest {
        val source = CombinedModelResponse("p", "Provider", content = "Test received. France was transformed by Napoleon in 1804.")
        val snapshot = CombinedInputSnapshot(userMessage = MessageV2(platformType = null, content = "test"), history = emptyList(), replies = emptyList(), sources = listOf(source), prompt = "", profileUid = "p", model = "model", systemPrompt = "", configKey = "key")
        val events = CombinedConversationSession.create(snapshot)!!.streamRound(emptyList(), emptyList()).toList()
        val parser = CombinedRecordProtocol(CombinedRecordCheckpoint(snapshotDigest = "test", revisionId = "revision"), emptySet(), setOf("C1"), emptySet(), 10000)
        events.filterIsInstance<ProviderEvent.TextDelta>().forEach { parser.accept(it.text) }
        assertTrue(parser.checkpoint().ended)
        assertFalse(parser.checkpoint().records.joinToString { it.wire }.contains("Napoleon"))
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun emptyFinalNeverClaimsSuccessfulDelivery() = runTest {
        val empty = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow { emit(ProviderEvent.Completed) }
        }
        val delivery = CombinedDeliverySession(empty, emptyList(), 10000, validateAccess = {}, structured = true, originalRequest = france, maxRepairRounds = 0) { _, _ -> error("No repair budget") }
        val events = delivery.streamRound(emptyList(), emptyList()).toList()
        val quality = events.combinedReport()
        assertFalse(quality.complete)
        assertEquals("FAILED", quality.deliveredScopeStatus)
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.filterIsInstance<ProviderEvent.Notice>().any { "answer was delivered" in it.message })
    }
}
