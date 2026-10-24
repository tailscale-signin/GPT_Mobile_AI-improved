package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTaskBudgetTest {
    @Test fun nineParticipantsShareOneNinetySourceAnalysisGoalAndFallbackUsesTheSamePool() = runBlocking {
        val uids = (1..9).map { "profile-$it" }
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), uids, uids.associateWith { "model" }, "benefits of hydrogen inhalation", acceptedResearch = CombinedResearchRequirement(50, 90, "WEB_ONLY"))
        val plan = CombinedPolicyResolver.resolve(requested)
        assertEquals(90L, plan.researchAssignment?.sourceGoal)
        assertEquals(uids, plan.researchAssignment?.eligibleWorkerUids)
        val stages = plan.workItems.filter { it.role == CombinedRunRole.RESEARCH }
        assertEquals(90L, stages.filter { it.id.endsWith(":extract") }.sumOf { it.remainingUnits })
        assertEquals(90L, stages.filter { it.id.endsWith(":review") }.sumOf { it.remainingUnits })
        assertEquals(2L, stages.single { it.id.endsWith(":plan") }.remainingUnits)
        assertFalse(stages.any { it.id.endsWith(":independent_review") })
        val context = CombinedExecutionContext(requested, plan, "shared-research", profileUid = uids.first())
        val item = requireNotNull(CombinedResearchStages.item(context, uids.first(), "research-extraction"))
        val fallbackItem = requireNotNull(CombinedResearchStages.item(context, uids.last(), "research-extraction"))
        assertEquals(item, fallbackItem)
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        for (uid in listOf(uids.first(), uids.last())) {
            val result = budget.reserve(context.forRole(CombinedRunRole.RESEARCH, uid, item.id), "extract-$uid", CombinedResourceQuota(100, 100))
            assertTrue(result is CombinedBudgetAdmission.Granted)
            assertEquals(item.id, (result as CombinedBudgetAdmission.Granted).reservation.ownerKey)
        }
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.RESEARCH, "unaccepted-profile", item.id), "unaccepted", CombinedResourceQuota(100, 100)) is CombinedBudgetAdmission.Denied)
        assertTrue(plan.baselineByOwner.keys.count { it.startsWith("contributor:") } == 9)
    }

    @Test fun legacyPerProfileStagePlansStillResolveWithoutCreatingTaskPools() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a"), mapOf("a" to "model"), "Task")
        val id = CombinedResearchStages.id("a", "extract")
        val plan = CombinedPolicyResolver.resolve(requested).copy(workItems = listOf(CombinedWorkItem(id, CombinedRunRole.RESEARCH, assignedOwnerKey = id)))
        val context = CombinedExecutionContext(requested, plan, "legacy", profileUid = "a")
        assertEquals(id, CombinedResearchStages.item(context, "a", "research-extraction")?.id)
        assertFalse(CombinedResearchStages.isTaskStage(id))
    }

    @Test fun failuresAreTerminalWithoutBeingCountedAsSuccessAndWaitingUsesNoAttempt() = runBlocking {
        val uids = (1..9).map { "profile-$it" }
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), uids, uids.associateWith { "model" }, "Task")
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "outcomes", profileUid = uids.first())
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        uids.forEach { budget.waitingForResource("contributor:$it", "BENCHMARK_TRIAL") }
        assertEquals(9, budget.snapshot().workAccounting?.waitingForResource)
        assertTrue(budget.snapshot().reservations.isEmpty())
        assertEquals(0, budget.snapshot().workAccounting?.failed)
        uids.forEachIndexed { index, uid -> budget.finishStage("contributor:$uid", if (index < 7) "FAILED" else "BUDGET_BLOCKED") }
        val accounting = requireNotNull(budget.snapshot().workAccounting)
        assertEquals(9, accounting.terminal)
        assertEquals(7, accounting.failed)
        assertEquals(2, accounting.budgetBlocked)
        assertEquals(0, accounting.succeeded)
        assertEquals(0, accounting.waitingForResource)
    }

    @Test fun legacyCompletedWorkWithoutAnOutcomeIsExplicitlyUnknown() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a"), mapOf("a" to "model"), "Task")
        val plan = CombinedPolicyResolver.resolve(requested)
        val snapshot = CombinedBudgetSnapshot(rootBudgetId = "legacy", chatId = 1, requestedPolicyDigest = requested.requestedPolicyDigest, effectivePlanDigest = plan.effectivePlanDigest, plan = plan, completedWork = setOf("contributor:a")).withWorkAccounting()
        assertEquals(1, snapshot.workAccounting?.legacyTerminalUnknown)
        assertEquals(0, snapshot.workAccounting?.succeeded)
    }

    @Test fun fortyFiveFailedTerminalStagesExportZeroSuccessfulWorkItems() = runBlocking {
        val uids = (1..45).map { "p$it" }
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), uids, uids.associateWith { "model" }, "Task")
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "forty-five-failures", profileUid = uids.first())
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        uids.forEach { budget.finishStage("contributor:$it", "FAILED", "legacy admission timeout") }
        val snapshot = budget.snapshot()
        assertEquals(45, snapshot.workAccounting?.terminal)
        assertEquals(45, snapshot.workAccounting?.failed)
        assertEquals(0, snapshot.workAccounting?.succeeded)
        val exported = kotlinx.serialization.json.Json.encodeToJsonElement(CombinedBudgetSnapshot.serializer(), snapshot) as kotlinx.serialization.json.JsonObject
        val counts = exported.getValue("workAccounting") as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(45), counts["failed"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(0), counts["succeeded"])
    }
}
