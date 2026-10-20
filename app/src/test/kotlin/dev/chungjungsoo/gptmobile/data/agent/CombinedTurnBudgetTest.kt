package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedTurnBudgetTest {
    @Test
    fun concurrentWorkersShareOneAtomicToolCeilingAndDuplicateIdsDoNotExecute() = runBlocking {
        val context = context(CombinedHardLimits(toolOperations = 7), renewable = false)
        val store = CombinedTurnBudgetStore.inMemory()
        val budget = store.open(context)
        val admissions = coroutineScope {
            (1..40).map { index -> async { budget.reserve(context, "attempt-$index", CombinedResourceQuota(toolOperations = 1)) } }.awaitAll()
        }
        assertEquals(7, admissions.count { it is CombinedBudgetAdmission.Granted })
        assertEquals(7L, budget.snapshot().charged.toolOperations)
        assertTrue(budget.reserve(context, "attempt-1", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
    }

    @Test
    fun unknownAttemptRemainsReservedAcrossSerializationAndCancellationBlocksFinalization() = runBlocking {
        val context = context(CombinedHardLimits(toolOperations = 2))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertTrue(budget.reserve(context, "unknown", CombinedResourceQuota(toolOperations = 2)) is CombinedBudgetAdmission.Granted)
        budget.unknown("unknown", "Connection closed after dispatch")
        val restored = CombinedTurnBudget(Json.decodeFromString(Json.encodeToString(budget.snapshot())))
        assertEquals(2L, restored.snapshot().charged.toolOperations)
        assertTrue(restored.reserve(context, "retry", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
        restored.cancel()
        assertTrue(restored.reserve(context.forRole(CombinedRunRole.SYNTHESIS), "final", CombinedResourceQuota(1, 1)) is CombinedBudgetAdmission.Denied)
        assertEquals(setOf("unknown"), restored.snapshot().unknownAttemptIds)
    }

    @Test
    fun fullScaleRenewsOnlyForFiniteUnfinishedWorkAndMeasuredProgress() = runBlocking {
        val context = context(optionalTools = 2, renewable = true).copy(workItemId = "required-pages")
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertTrue(budget.reserve(context, "batch-one", CombinedResourceQuota(toolOperations = 2)) is CombinedBudgetAdmission.Granted)
        assertTrue(budget.reserve(context, "without-progress", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
        budget.progress("required-pages", "inspected-page-1")
        assertTrue(budget.reserve(context, "renewed", CombinedResourceQuota(toolOperations = 2)) is CombinedBudgetAdmission.Granted)
        assertEquals(2L, budget.snapshot().optionalTranches)
        budget.completeWork("required-pages")
        assertTrue(budget.reserve(context, "no-unfinished-work", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
    }

    @Test
    fun baselineAndFinalCapacityCannotBeConsumedByOptionalCallsOrContextEstimateExpansion() = runBlocking {
        val context = context(CombinedHardLimits(totalTokens = 35), baseline = CombinedResourceQuota(10, 10), final = CombinedResourceQuota(5, 5))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertTrue(budget.reserve(context, "compiled-too-large", CombinedResourceQuota(30, 1)) is CombinedBudgetAdmission.Denied)
        assertTrue(budget.reserve(context, "first", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Granted)
        assertTrue(budget.reserve(context, "optional", CombinedResourceQuota(6, 0)) is CombinedBudgetAdmission.Denied)
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.SYNTHESIS), "final", CombinedResourceQuota(5, 5)) is CombinedBudgetAdmission.Granted)
        assertEquals(30L, budget.snapshot().charged.let { it.inputTokens + it.generatedTokens })
    }

    @Test
    fun settlementRefundsOnlyKnownUsageAndRetainsUnknownCost() = runBlocking {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.reserve(context, "known", CombinedResourceQuota(10, 10), 100)
        budget.settle("known", CombinedUsage(CombinedResourceQuota(8, 3), measuredInput = true, measuredOutput = true, costMicros = 60))
        assertEquals(11L, budget.snapshot().charged.let { it.inputTokens + it.generatedTokens })
        assertEquals(60L, budget.snapshot().costMicros)
        budget.reserve(context, "unknown-cost", CombinedResourceQuota(toolOperations = 1))
        assertEquals(null, budget.snapshot().costMicros)
        budget.releaseNotDispatched("unknown-cost")
        assertEquals(60L, budget.snapshot().costMicros)
    }

    @Test
    fun moneyCeilingProtectsFinalizationAndUnknownQuotesBlockBeforeDispatch() = runBlocking {
        val raw = context(CombinedHardLimits(costMicros = 100))
        val plan = sealPlan(requireNotNull(raw.plan).copy(costReservesByOwner = mapOf("contributor:a" to 20, "finalization" to 60)))
        val context = raw.copy(plan = plan)
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertTrue(budget.reserve(context, "unknown", CombinedResourceQuota(1, 1)) is CombinedBudgetAdmission.Denied)
        assertTrue(budget.reserve(context, "first", CombinedResourceQuota(1, 1), 20) is CombinedBudgetAdmission.Granted)
        assertTrue(budget.reserve(context, "steals-final", CombinedResourceQuota(20, 20), 30) is CombinedBudgetAdmission.Denied)
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.SYNTHESIS), "final", CombinedResourceQuota(1, 1), 60) is CombinedBudgetAdmission.Granted)
    }

    @Test
    fun independentStoreInstancesShareAdmissionAndDeletionCannotRecreatePrivateJournal() = runBlocking {
        val directory = Files.createTempDirectory("combined-budget-test").toFile()
        try {
            val context = context(CombinedHardLimits(toolOperations = 1)).copy(chatId = 42)
            val firstStore = CombinedTurnBudgetStore.forDirectory(directory)
            val secondStore = CombinedTurnBudgetStore.forDirectory(directory)
            val first = firstStore.open(context)
            val second = secondStore.open(context)
            assertTrue(first === second)
            assertTrue(first.reserve(context, "one", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Granted)
            assertTrue(second.reserve(context, "two", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
            firstStore.deleteConversation(42)
            first.unknown("one", "Late completion after deletion")
            assertTrue(first.snapshot().cancelled)
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "json" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun persistenceFailureCannotPublishAdmissionAndChatRebindingKeepsAcceptedDigest() = runBlocking {
        val context = context()
        val snapshot = CombinedTurnBudgetStore.inMemory().open(context).snapshot()
        val budget = CombinedTurnBudget(snapshot, persist = { error("disk full") })
        assertTrue(runCatching { budget.reserve(context, "not-sent", CombinedResourceQuota(toolOperations = 1)) }.isFailure)
        assertTrue(budget.snapshot().reservations.isEmpty())
        val durable = CombinedTurnBudgetStore.inMemory().open(context)
        durable.rebindConversation(7)
        assertEquals(context.requested.requestedPolicyDigest, durable.snapshot().requestedPolicyDigest)
        assertEquals(7, durable.snapshot().chatId)
    }

    @Test
    fun unknownSideEffectCannotBeRetriedByAnotherWorkerOrAfterRecovery() = runBlocking {
        val context = context()
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertTrue(budget.reserve(context, "action", CombinedResourceQuota(toolOperations = 1), effectFingerprint = "same-approved-action") is CombinedBudgetAdmission.Granted)
        budget.unknown("action", "Connection lost after request")
        val recovered = CombinedTurnBudget(Json.decodeFromString(Json.encodeToString(budget.snapshot())))
        assertTrue(recovered.reserve(context.forRole(CombinedRunRole.RESEARCH, profileUid = "b"), "action-retry", CombinedResourceQuota(toolOperations = 1), effectFingerprint = "same-approved-action") is CombinedBudgetAdmission.Denied)
        assertEquals(1L, recovered.snapshot().charged.toolOperations)
    }

    @Test
    fun sameRoleContinuationKeepsConcreteWorkAssignment() {
        val context = context().copy(role = CombinedRunRole.REPAIR, workItemId = "required-correction-v2")
        assertEquals("required-correction-v2", context.forRole(CombinedRunRole.REPAIR).workItemId)
        assertEquals(null, context.forRole(CombinedRunRole.VERIFIER).workItemId)
    }

    @Test
    fun requiredCorrectionUsesExistingReserveAndAdditionalInspectionIsExplicit() = runBlocking {
        val context = context(CombinedHardLimits(totalTokens = 40))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.authorizeRequiredWork(CombinedWorkItem("repair-v1", CombinedRunRole.REPAIR), CombinedResourceQuota(10, 10), "ANSWER_CONTRACT_FAILURE")
        assertTrue(budget.snapshot().baselineAdjustments.isEmpty())
        assertTrue(
            runCatching {
                budget.authorizeRequiredWork(CombinedWorkItem("inspection-v1", CombinedRunRole.SYNTHESIS), CombinedResourceQuota(1, 1), "REQUIRED_COVERAGE_GAP", additional = true)
            }.exceptionOrNull() is CombinedBudgetExceeded
        )
        assertFalse("inspection-v1" in budget.snapshot().pendingWork)
        assertTrue(budget.reserve(context.forRole(CombinedRunRole.REPAIR, workItemId = "repair-v1"), "repair", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Granted)
    }

    @Test
    fun contributorCorpusInspectionIsFundedForItsActualOwner() = runBlocking {
        val context = context(CombinedHardLimits(totalTokens = 60))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.authorizeRequiredWork(
            CombinedWorkItem("corpus-a", CombinedRunRole.CONTRIBUTOR),
            CombinedResourceQuota(10, 10),
            "REQUIRED_COVERAGE_GAP",
            additional = true,
            ownerKey = context.ownerKey
        )
        val admitted = budget.reserve(context.copy(workItemId = "corpus-a"), "inspection", CombinedResourceQuota(20, 20)) as CombinedBudgetAdmission.Granted
        assertEquals(CombinedBudgetPool.BASELINE, admitted.reservation.pool)
        assertEquals("contributor:a", budget.snapshot().baselineAdjustments.single().ownerKey)
    }

    @Test
    fun partialMergeKeepsProtectedReserveUntilExplicitRetryDelivers() = runBlocking {
        val context = context(CombinedHardLimits(totalTokens = 40))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        budget.finishStage("finalization", "PARTIAL")
        assertFalse("finalization" in budget.snapshot().completedWork)
        assertTrue(budget.reserve(context, "optional-cannot-steal-final", CombinedResourceQuota(21, 0)) is CombinedBudgetAdmission.Denied)
        budget.finishStage("contributor:a", "COMPLETED")
        budget.completeWork("required-pages")
        budget.finishStage("finalization", "COMPLETED")
        assertEquals("COMPLETED", budget.snapshot().stageOutcomes["finalization"])
        budget.close()
        assertEquals("COMPLETED", budget.snapshot().terminalOutcome)
    }

    @Test fun laterRequiredRepairUsesCompiledSafetyAllowanceWithoutOptionalFunding() = runBlocking {
        val context = context(final = CombinedResourceQuota(10, 10))
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        val final = context.forRole(CombinedRunRole.SYNTHESIS)
        assertTrue(budget.reserve(final, "first", CombinedResourceQuota(10, 10)) is CombinedBudgetAdmission.Granted)
        val id = "required-repair"
        budget.authorizeRequiredWork(CombinedWorkItem(id, CombinedRunRole.REPAIR), CombinedResourceQuota(1000, 100), "REQUIRED_COVERAGE_GAP")
        val repair = context.forRole(CombinedRunRole.REPAIR, "a", id)
        val result = budget.reserve(repair, "actual-compiled", CombinedResourceQuota(1250, 100))
        assertTrue(result is CombinedBudgetAdmission.Granted)
        assertEquals(CombinedBudgetPool.FINALIZATION, (result as CombinedBudgetAdmission.Granted).reservation.pool)
    }

    private fun context(
        hard: CombinedHardLimits = CombinedHardLimits(),
        optionalTools: Long = 100,
        renewable: Boolean = false,
        baseline: CombinedResourceQuota = CombinedResourceQuota(10, 10),
        final: CombinedResourceQuota = CombinedResourceQuota(10, 10)
    ): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = if (renewable) 100 else 50), listOf("a"), mapOf("a" to "test"), "Compare required pages", acceptedAtMillis = 1)
        val plan = sealPlan(
            CombinedWorkPlan(
                requested = requested,
                baselineByOwner = mapOf("contributor:a" to baseline),
                finalizationReserve = final,
                optionalAllowance = CombinedResourceQuota(100, 100, optionalTools),
                hardLimits = hard,
                workItems = listOf(CombinedWorkItem("contributor:a", CombinedRunRole.CONTRIBUTOR), CombinedWorkItem("required-pages", CombinedRunRole.RESEARCH, 10, kind = CombinedWorkKind.EVIDENCE)),
                renewable = renewable
            )
        )
        return CombinedExecutionContext(requested, plan, java.util.UUID.randomUUID().toString(), profileUid = "a")
    }

    private fun sealPlan(plan: CombinedWorkPlan): CombinedWorkPlan = plan.copy(effectivePlanDigest = combinedDigest(Json.encodeToString(plan.copy(effectivePlanDigest = ""))))
}
