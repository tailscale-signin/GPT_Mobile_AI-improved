package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedResearchStageTest {
    private fun context(): CombinedExecutionContext {
        val request = CombinedPolicyResolver.accept(
            ConversationCombinedSettings(effort = 0),
            listOf("fast", "slow"),
            mapOf("fast" to "a", "slow" to "b"),
            "Research the selected sources",
            acceptedResearch = CombinedResearchRequirement(1, 4, "WEB_ONLY")
        )
        return CombinedExecutionContext(request, CombinedPolicyResolver.resolve(request, estimatedInputTokens = 512), "shared-research", profileUid = "fast")
    }

    @Test fun helpersKeepAcceptedWorkerOwnershipEvenWhenAnotherParticipantIsTheirPhysicalTarget() = runTest {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val stage = CombinedResearchStages.taskId("extract")
        val helper = context.forResearchTarget("local-helper", stage)
        assertEquals("fast", helper.profileUid)
        assertTrue(budget.reserve(helper, "helper", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        budget.finishResearchStage(stage, "fast", "COMPLETED")
        val peerHelper = context.copy(profileUid = "slow").forResearchTarget("fast", stage)
        assertEquals("slow", peerHelper.profileUid)
        assertTrue(budget.reserve(peerHelper, "peer-helper", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
    }

    @Test fun explicitRetryReopensOnlySelectedWorkerWithoutRefundingUnknownUsage() = runTest {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val stage = CombinedResearchStages.taskId("extract")
        assertTrue(budget.reserve(context.forResearchTarget("fast", stage), "unknown", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        budget.unknown("unknown", "Remote result unknown")
        budget.finishResearchStage(stage, "fast", "PARTIAL")
        budget.finishResearchStage(stage, "slow", "COMPLETED")
        budget.finishStage("contributor:fast", "FAILED")
        budget.finishStage("contributor:slow", "COMPLETED")
        assertTrue(budget.reopenContributor("fast"))
        assertEquals(setOf("slow"), budget.snapshot().researchWorkerOutcomes.getValue(stage).keys)
        assertEquals(setOf("unknown"), budget.snapshot().unknownAttemptIds)
        assertEquals(512L, budget.snapshot().charged.generatedTokens)
        assertTrue(budget.reserve(context.forResearchTarget("fast", stage), "new-authorized", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        assertTrue(budget.reserve(context.copy(profileUid = "slow").forResearchTarget("fast", stage), "closed-peer", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Denied)
    }

    @Test fun legacySnapshotWithoutWorkerMapStillRejectsAlreadyTerminalContributor() = runTest {
        val context = context()
        val initial = CombinedTurnBudgetStore.inMemory().open(context).snapshot().copy(stageOutcomes = mapOf("contributor:fast" to "FAILED"))
        val wire = Json.encodeToString(initial)
        assertFalse(wire.contains("researchWorkerOutcomes"))
        val restored = CombinedTurnBudget(Json.decodeFromString(wire))
        val stage = CombinedResearchStages.taskId("extract")
        assertTrue(restored.reserve(context.forResearchTarget("fast", stage), "fast", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Denied)
        assertTrue(restored.reserve(context.copy(profileUid = "slow").forResearchTarget("slow", stage), "slow", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
    }

    @Test fun activePeerKeepsSharedPoolProtectedAgainstOptionalWork() = runTest {
        val context = context()
        val initial = CombinedTurnBudgetStore.inMemory().open(context).snapshot()
        val protectedOutput = initial.plan.baseline.generatedTokens + initial.plan.finalizationReserve.generatedTokens
        val plan = initial.plan.copy(hardLimits = CombinedHardLimits(generatedTokens = protectedOutput), optionalAllowance = CombinedResourceQuota(1000, 8192))
        val budget = CombinedTurnBudget(initial.copy(plan = plan))
        val stage = CombinedResearchStages.taskId("extract")
        val optional = context.forRole(CombinedRunRole.RESEARCH, "fast")
        budget.finishResearchStage(stage, "fast", "COMPLETED")
        assertTrue(budget.reserve(optional, "early-optional", CombinedResourceQuota(1, 512)) is CombinedBudgetAdmission.Denied)
        budget.finishResearchStage(stage, "slow", "COMPLETED")
        assertTrue(budget.reserve(optional, "later-optional", CombinedResourceQuota(1, 512)) is CombinedBudgetAdmission.Granted)
    }

    @Test fun firstWorkerCompletionCannotCloseAnotherWorkersExtractionStage() = runTest {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val stage = CombinedResearchStages.taskId("extract")
        val slow = context.forRole(CombinedRunRole.RESEARCH, "slow", stage)
        val originalReserve = budget.snapshot().plan.finalizationReserve
        budget.finishResearchStage(stage, "fast", "COMPLETED")
        budget.finishStage("contributor:fast", "COMPLETED")
        assertFalse(stage in budget.snapshot().stageOutcomes)
        assertTrue(budget.reserve(slow, "slow-extract", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        budget.finishResearchStage(stage, "slow", "COMPLETED")
        assertEquals("COMPLETED", budget.snapshot().stageOutcomes[stage])
        assertTrue(budget.reserve(slow, "late-extract", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Denied)
        assertEquals(originalReserve, budget.snapshot().plan.finalizationReserve)
    }

    @Test fun serializationRetainsPerWorkerOwnershipAndUnknownPhysicalReservations() = runTest {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val stage = CombinedResearchStages.taskId("extract")
        val fast = context.forRole(CombinedRunRole.RESEARCH, "fast", stage)
        assertTrue(budget.reserve(fast, "unknown", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        budget.unknown("unknown", "Remote outcome unknown")
        budget.finishResearchStage(stage, "fast", "PARTIAL", "INTERRUPTED")
        val restored = CombinedTurnBudget(Json.decodeFromString(Json.encodeToString(budget.snapshot())))
        assertEquals(setOf("unknown"), restored.snapshot().unknownAttemptIds)
        assertTrue(restored.reserve(context.forRole(CombinedRunRole.RESEARCH, "slow", stage), "slow", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        restored.finishResearchStage(stage, "slow", "COMPLETED")
        assertTrue(restored.snapshot().stageOutcomes.getValue(stage).startsWith("PARTIAL"))
        assertEquals(1024L, restored.snapshot().charged.generatedTokens)
    }

    @Test fun failedContributorSettlesOnlyItsShareAndLateCompletionCannotRewriteIt() = runTest {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val stage = CombinedResearchStages.taskId("extract")
        budget.finishStage("contributor:fast", "INTERRUPTED", "PROCESS_EXIT")
        assertFalse(stage in budget.snapshot().stageOutcomes)
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.RESEARCH, "slow", stage), "slow", CombinedResourceQuota(500, 512)) is CombinedBudgetAdmission.Granted)
        budget.finishResearchStage(stage, "slow", "COMPLETED")
        val settled = budget.snapshot()
        budget.finishResearchStage(stage, "fast", "COMPLETED")
        assertEquals(settled, budget.snapshot())
        assertTrue(settled.stageOutcomes.getValue(stage).startsWith("PARTIAL"))
    }

    @Test fun concurrentWorkerCompletionClosesSharedStageExactlyOnce() = runTest {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val stage = CombinedResearchStages.taskId("review")
        listOf("fast", "slow").map { worker -> async { budget.finishResearchStage(stage, worker, "COMPLETED") } }.awaitAll()
        assertEquals(setOf("fast", "slow"), budget.snapshot().researchWorkerOutcomes.getValue(stage).keys)
        assertEquals("COMPLETED", budget.snapshot().stageOutcomes[stage])
        assertFalse(stage in budget.snapshot().pendingWork)
    }
}
