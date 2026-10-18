package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWorkInventoryTest {
    @Test
    fun explicitSourceRequestCanRenewPastThreeHundredWithoutCompletingContributorStage() = runBlocking {
        val context = context("Compare 500 independent sources on battery recycling.")
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val work = budget.assignEvidenceWork(context, "battery recycling studies", false)
        assertNotNull(work)
        assertTrue(budget.reserve(context, "initial-tranche", CombinedResourceQuota(toolOperations = 300)) is CombinedBudgetAdmission.Granted)
        assertTrue(budget.reserve(context, "before-progress", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
        budget.recordDiscovery(requireNotNull(work), "https://example.org/battery-evidence")
        assertTrue(budget.recordInspection(work, "https://example.org/battery-evidence"))
        assertTrue(budget.reserve(context, "renewed-request", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Granted)
        val snapshot = budget.snapshot()
        assertEquals(2L, snapshot.optionalTranches)
        assertEquals(499L, snapshot.pendingWork[work]?.remainingUnits)
        assertTrue("contributor:a" in snapshot.pendingWork)
        assertEquals(301L, snapshot.charged.toolOperations)
    }

    @Test
    fun unrelatedQueriesNovelUrlsAndDuplicateReadsCannotExpandAcceptedScope() = runBlocking {
        val context = context("Find 2 sources on battery recycling.")
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertNull(budget.assignEvidenceWork(context, "weekend football scores", false))
        val work = requireNotNull(budget.assignEvidenceWork(context, "battery recycling", false))
        assertFalse(budget.recordInspection(work, "https://unrelated.example/novelty"))
        budget.recordDiscovery(work, "https://example.org/one https://example.org/two https://example.org/three")
        assertTrue(budget.recordInspection(work, "https://example.org/one"))
        budget.recordDiscovery(work, "https://example.org/one")
        assertFalse(budget.recordInspection(work, "https://example.org/one"))
        assertTrue(budget.recordInspection(work, "https://example.org/two"))
        assertFalse(budget.recordInspection(work, "https://example.org/three"))
        assertTrue(work in budget.snapshot().completedWork)
        assertEquals(2, budget.snapshot().progressFingerprints.size)
    }

    @Test
    fun numberedQuestionsRemainDistinctAndOpenTaskHasFiniteInventory() {
        val numbered = context("1. Evaluate battery recycling costs.\n2. Assess battery recycling emissions.")
        val evidence = requireNotNull(numbered.plan).workItems.filter { it.kind == CombinedWorkKind.EVIDENCE }
        assertEquals(2, evidence.size)
        assertTrue(evidence.all { it.remainingUnits == 12L })
        assertEquals(0, CombinedWorkInventory.derive(numbered.requested, false).size)
        assertTrue(evidence.all { it.description.isNotBlank() && it.reason == "BOUNDED_REQUEST_QUESTION" })
    }

    @Test
    fun terminalStageReleasesProtectedCapacityAndCancellationCannotBeReopened() = runBlocking {
        val original = context("Research battery recycling")
        val sourcePlan = requireNotNull(original.plan).copy(
            baselineByOwner = mapOf("contributor:a" to CombinedResourceQuota(10, 10)),
            finalizationReserve = CombinedResourceQuota(5, 5),
            hardLimits = CombinedHardLimits(totalTokens = 30),
            effectivePlanDigest = ""
        )
        val context = original.copy(plan = sourcePlan.copy(effectivePlanDigest = combinedDigest(Json.encodeToString(sourcePlan))))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.finishStage("contributor:a", "FAILED", "No usable response")
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.SYNTHESIS), "final", CombinedResourceQuota(10, 5)) is CombinedBudgetAdmission.Granted)
        assertTrue(requireNotNull(budget.snapshot().stageOutcomes["contributor:a"]).startsWith("FAILED"))
        budget.cancel()
        budget.close("COMPLETED")
        assertEquals("CANCELLED", budget.snapshot().terminalOutcome)
        assertTrue(budget.reserve(context, "retry", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
    }

    private fun context(prompt: String): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 100), listOf("a"), mapOf("a" to "test"), prompt, acceptedAtMillis = 1)
        return CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), java.util.UUID.randomUUID().toString(), profileUid = "a")
    }
}
