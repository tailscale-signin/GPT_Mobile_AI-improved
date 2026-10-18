package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedAcquisitionBoundaryTest {
    @Test
    fun quickRequiredSourcesAreFundedOnceAndChildUsesAssignedReplayBudget() = runBlocking {
        val context = context()
        val plan = requireNotNull(context.plan)
        assertEquals(2L, plan.optionalAllowance.toolOperations)
        assertEquals(20L, plan.baseline.toolOperations)
        assertEquals(0L, plan.baselineByOwner.getValue("contributor:b").toolOperations)
        assertTrue(plan.unfundedRequiredWorkIds.isEmpty())
        val store = CombinedTurnBudgetStore.inMemory()
        val budget = store.open(context)
        val assigned = budget.assignedContext(context)
        val research = assigned.forRole(CombinedRunRole.RESEARCH, "b", assigned.workItemId)
        val admitted = budget.reserve(research, "appraisal", CombinedResourceQuota(5000, 1024)) as CombinedBudgetAdmission.Granted
        assertEquals("contributor:a", admitted.reservation.ownerKey)
        assertEquals(CombinedRunRole.RESEARCH, admitted.reservation.role)
        assertEquals(CombinedBudgetPool.BASELINE, admitted.reservation.pool)
        withContext(CombinedRunScope(research)) {
            requireNotNull(CombinedAcquisitionBoundary.reserve("read-ten", 10, freePublicEndpoint = true, store = store)).finish(10)
        }
        assertEquals(10L, budget.snapshot().charged.toolOperations)
        assertEquals(1L, budget.snapshot().optionalTranches)
    }

    @Test
    fun configuredCeilingIsNotRaisedToSatisfyRequiredSourceCount() = runBlocking {
        val context = context(CombinedHardLimits(toolOperations = 3))
        val plan = requireNotNull(context.plan)
        assertTrue(plan.unfundedRequiredWorkIds.isNotEmpty())
        assertEquals(0L, plan.baseline.toolOperations)
        val budget = CombinedTurnBudgetStore.inMemory().open(context)
        assertTrue(budget.reserve(budget.assignedContext(context), "ten", CombinedResourceQuota(toolOperations = 10)) is CombinedBudgetAdmission.Denied)
        assertEquals(0L, budget.snapshot().charged.toolOperations)
    }

    @Test
    fun closedRootCancelsDetachedWorkBeforeStartAndDuringExecution() = runBlocking {
        val context = context()
        val store = CombinedTurnBudgetStore.inMemory()
        val budget = store.open(context)
        var dispatched = false
        val running = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        budget.registerChild(running)
        budget.cancel()
        assertTrue(running.isCancelled)
        val queued = launch(start = CoroutineStart.LAZY) { dispatched = true }
        budget.registerChild(queued)
        queued.start()
        queued.join()
        assertFalse(dispatched)
        withContext(CombinedRunScope(context)) {
            assertTrue(runCatching { CombinedAcquisitionBoundary.reserve("after-close", 1, store = store) }.exceptionOrNull() is CombinedBudgetExceeded)
        }
        assertTrue(budget.snapshot().reservations.isEmpty())
    }

    @Test
    fun gatewayUnknownRetainsUpperAndReceiptBeyondUpperRetainsActual() = runBlocking {
        val context = context()
        val store = CombinedTurnBudgetStore.inMemory()
        val budget = store.open(context)
        withContext(CombinedRunScope(budget.assignedContext(context))) {
            requireNotNull(CombinedAcquisitionBoundary.reserve("unknown", 4, store = store)).finish()
            val over = requireNotNull(CombinedAcquisitionBoundary.reserve("over", 1, store = store))
            assertTrue(runCatching { over.finish(3) }.exceptionOrNull() is CombinedBudgetExceeded)
        }
        assertEquals(7L, budget.snapshot().charged.toolOperations)
        assertEquals(setOf("unknown"), budget.snapshot().unknownAttemptIds)
        assertEquals(null, budget.snapshot().costMicros)
    }

    @Test
    fun cancellationClosesMemoryAndChildrenEvenWhenDurableJournalFails() = runBlocking {
        val context = context()
        val budget = CombinedTurnBudget(CombinedTurnBudgetStore.inMemory().open(context).snapshot(), persist = { error("disk full") })
        val child = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        budget.registerChild(child)
        assertTrue(runCatching { budget.cancel() }.isFailure)
        assertTrue(child.isCancelled)
        assertTrue(budget.snapshot().cancelled)
        assertTrue(budget.reserve(context, "after-failed-save", CombinedResourceQuota(toolOperations = 1)) is CombinedBudgetAdmission.Denied)
    }

    private fun context(hard: CombinedHardLimits = CombinedHardLimits()): CombinedExecutionContext {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 0), listOf("a", "b"), mapOf("a" to "test", "b" to "test"), "Read 10 sources on battery recycling.", acceptedAtMillis = 1)
        return CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested, hardLimits = hard), java.util.UUID.randomUUID().toString(), profileUid = "a")
    }
}
