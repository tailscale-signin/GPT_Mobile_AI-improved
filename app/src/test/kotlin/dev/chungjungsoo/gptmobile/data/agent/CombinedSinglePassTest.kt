package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSinglePassTest {
    private fun context(): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p", "q"), mapOf("p" to "model", "q" to "model"), "Explain the world", singlePass = true)
        return CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "single-root", profileUid = "p")
    }

    @Test fun concurrentPrimaryDispatchAndRestorationPermitOnlyOnePhysicalRequest() = runTest {
        val ctx = context().forRole(CombinedRunRole.SYNTHESIS)
        val budget = CombinedTurnBudgetStore.inMemory().open(ctx)
        val result = (1..12).map { async { budget.reserve(ctx, "attempt-$it", CombinedResourceQuota(10, 10)) } }.awaitAll()
        assertEquals(1, result.count { it is CombinedBudgetAdmission.Granted })
        val restored = CombinedTurnBudget(Json.decodeFromString(Json.encodeToString(budget.snapshot())))
        assertTrue(restored.reserve(ctx, "after-restart", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Denied)
        for (role in listOf(CombinedRunRole.VERIFIER, CombinedRunRole.REPAIR, CombinedRunRole.CONTINUATION)) {
            assertTrue(restored.reserve(ctx.forRole(role), role.name, CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Denied)
        }
    }

    @Test fun knownNotDispatchedReservationCanBeReleasedButUncertainAttemptCannot() = runTest {
        val ctx = context().forRole(CombinedRunRole.SYNTHESIS)
        val budget = CombinedTurnBudgetStore.inMemory().open(ctx)
        assertTrue(budget.reserve(ctx, "before-wire", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Granted)
        budget.releaseNotDispatched("before-wire")
        assertTrue(budget.reserve(ctx, "wire", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Granted)
        budget.unknown("wire", "Connection lost")
        assertTrue(budget.reserve(ctx, "duplicate", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Denied)
    }

    @Test fun explicitContributorRetryReopensOnlySelectedStageWithoutResettingCharges() = runTest {
        val ctx = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(ctx)
        val allowance = ctx.plan!!.baselineByOwner.getValue("contributor:p")
        assertTrue(budget.reserve(ctx, "p-first", allowance) is CombinedBudgetAdmission.Granted)
        budget.finishStage("contributor:p", "FAILED")
        budget.finishStage("contributor:q", "COMPLETED")
        val charged = budget.snapshot().charged
        assertTrue(budget.reopenContributor("p"))
        assertEquals(charged, budget.snapshot().charged)
        assertFalse(budget.snapshot().stageOutcomes.containsKey("contributor:p"))
        assertEquals("COMPLETED", budget.snapshot().stageOutcomes["contributor:q"])
        assertTrue(budget.reserve(ctx, "p-retry", allowance) is CombinedBudgetAdmission.Granted)
        budget.cancel()
        assertFalse(budget.reopenContributor("p"))
    }

    @Test fun failedContractRunsLocalChecksWithoutVerifierOrRepairAndRetainsOutput() = runTest {
        var generations = 0
        val original = "1. One reason.\n2. Another reason."
        val base = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                generations++
                emit(ProviderEvent.TextDelta(original))
                emit(ProviderEvent.Completed)
            }
        }
        val session = CombinedDeliverySession(base, listOf(CombinedModelResponse("p", "Primary", content = original)), 10000, validateAccess = {}, originalRequest = "List the top 10 reasons", combined = context().forRole(CombinedRunRole.SYNTHESIS), verify = { error("Verifier must never run") }, repairDraft = { error("Repair must never run") }) { _, _ -> error("Legacy repair must never run") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, generations)
        assertEquals(original, events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        val report = events.filterIsInstance<ProviderEvent.CombinedQuality>().last().report
        assertFalse(report.complete)
        assertEquals("PARTIAL", report.deliveredScopeStatus)
        assertEquals(0, report.repairRounds)
        assertEquals(setOf("p"), report.includedProfileUids)
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun officialPriceFollowupResolvesServicesWithoutSubstitutingModelName() {
        val request = "Check the official price on both sites for Google Flash 3.8."
        val policy = CombinedAnswerPolicy.from(request, "Compare LLM7 and OpenRouter prices.")
        assertTrue(policy.requiresCurrentEvidence)
        assertTrue(policy.requiresOfficialPrices)
        assertTrue(policy.comparative)
        assertEquals("FOLLOW_UP", policy.task?.relationship)
        assertEquals(setOf("LLM7", "OpenRouter"), policy.task?.subjects?.toSet())
        assertTrue(policy.task!!.request.contains("Google Flash 3.8"))
        assertEquals(listOf(0), CombinedTaskContract.relevantHistoryIndices(request, listOf("Compare LLM7 and OpenRouter prices.")))
    }

    @Test fun fixedCountCannotPassWithNineOrUnnumberedSections() {
        val policy = CombinedAnswerPolicy.from("List the top 10 reasons to be alive.")
        assertEquals(10, policy.requestedItems)
        assertEquals("NUMBERED_LIST", policy.requiredFormat)
        assertTrue(policy.violations("A paragraph without the requested items.").any { it.startsWith("ITEM_COUNT_UNMET") })
        assertTrue(policy.violations((1..9).joinToString("\n") { "$it. Reason $it" }).any { it.startsWith("ITEM_COUNT_UNMET") })
        assertTrue(policy.violations((1..10).joinToString("\n") { "$it. Distinct reason $it" }).isEmpty())
    }

    @Test fun unsupportedHardwarePricingAndTitleOnlyEvidenceFailLocally() {
        val policy = CombinedAnswerPolicy.from("Compare LLM7 and OpenRouter prices.")
        val answer = "LLM7 and OpenRouter offer TP-Link hardware for $100."
        assertTrue(policy.violations(answer).contains("API_PRICING_HARDWARE_MISMATCH"))
        val ledger = CombinedEvidenceLedger(listOf(CombinedEvidenceRecord("E1", "https://openrouter.ai/pricing", title = "Pricing", retrieval = "TITLE_ONLY")))
        assertTrue(CombinedPricingContract.check(answer, ledger, policy).any { it.id == "OFFICIAL_PRICE_EVIDENCE_MISSING" })
        assertTrue(CombinedPricingContract.check("LLM7 and OpenRouter prices could not be verified; the requested model was not found.", ledger, policy).isEmpty())
    }

    @Test fun emptyContributorDoesNotStartHiddenAgentFinalization() = runTest {
        var generations = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                generations++
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(combined = context())).run(provider, emptyList()).toList()
        assertEquals(1, generations)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
    }
}
