package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAttemptTest {
    private fun task() = AcceptedResearchTask(
        taskId = researchHash("task:root"), rootWorkId = "root", chatId = 1, originatingUserMessageId = 2,
        taskText = "benefits of hydrogen inhalation", intent = "REQUIRED", participantUids = listOf("a", "b"),
        primaryUid = "a", settingsRevision = "settings-1", sourceAuthorityRevision = "web-only-1", acceptedAt = 10,
        acceptedPolicyJson = "{\"contentScope\":\"WEB_ONLY\",\"sourceGoal\":90}", sourceScope = "WEB_ONLY", targetSources = 90,
        requestedModels = mapOf("a" to "model-a", "b" to "model-b")
    )

    @Test fun queueingAndSiblingDispatchStayWithinOneNonterminalAttempt() {
        val first = ResearchExecutionRecord.accept(task())
        val fence = first.attempt
        val waiting = first.transition(fence.attemptId, fence.generation, ResearchAttemptState.WAITING_RESOURCE, 20, reason = "BENCHMARK_TRIAL", runId = "a-run", profileUid = "a")
        val secondWaiting = waiting.transition(fence.attemptId, fence.generation, ResearchAttemptState.WAITING_RESOURCE, 21, reason = "BENCHMARK_TRIAL", runId = "b-run", profileUid = "b")
        assertFalse(secondWaiting.attempt.state.terminal)
        assertTrue(secondWaiting.attempt.dispatchedRunIds.isEmpty())
        assertEquals(listOf("a", "b"), secondWaiting.receipts.map { it.profileUid })
        val active = secondWaiting.transition(fence.attemptId, fence.generation, ResearchAttemptState.SEARCHING, 100, runId = "a-run", profileUid = "a", dispatched = true, queueMillis = 90_000)
        val sibling = active.transition(fence.attemptId, fence.generation, ResearchAttemptState.WAITING_RESOURCE, 101, runId = "b-run", profileUid = "b", reason = "LOCAL_RUNTIME")
        assertEquals(ResearchAttemptState.SEARCHING, sibling.attempt.state)
        assertEquals(ResearchNextAction.NONE, sibling.scheduling.action)
        assertEquals(90_000L, sibling.attempt.queueMillis)
        val dispatched = sibling.transition(fence.attemptId, fence.generation, ResearchAttemptState.ANALYZING, 102, runId = "b-run", profileUid = "b", dispatched = true)
        assertEquals(setOf("a-run", "b-run"), dispatched.attempt.dispatchedRunIds)
        assertEquals(fence.attemptId, dispatched.attempt.attemptId)
        assertEquals(first.accepted, dispatched.accepted)
    }

    @Test fun terminalOutcomeIsExactlyOnceAndRetriesAreIdempotentAndGenerationFenced() {
        val first = ResearchExecutionRecord.accept(task())
        val old = first.attempt
        val failed = first.transition(old.attemptId, old.generation, ResearchAttemptState.FAILED_NO_EVIDENCE, 20, reason = "NO_READABLE_SOURCES")
        assertEquals(failed, failed.transition(old.attemptId, old.generation, ResearchAttemptState.CANCELLED, 21))
        val next = failed.resume("retry-tap", 30)
        assertEquals(1L, next.attempt.generation)
        assertEquals(ResearchAttemptState.FAILED_NO_EVIDENCE, next.previousAttempts.single().terminalOutcome)
        assertEquals(next, next.resume("retry-tap", 40))
        assertEquals(next, next.transition(old.attemptId, old.generation, ResearchAttemptState.COMPLETED, 50, checkpointId = "stale-delivery"))
        val again = next.transition(next.attempt.attemptId, 1, ResearchAttemptState.FAILED_DELIVERY, 60).resume("second-tap", 70)
        assertEquals(again, again.resume("retry-tap", 80))
        assertEquals(first.accepted, again.accepted)
    }

    @Test fun unknownDispatchedRequestRequiresReconciliationBeforeRetry() {
        val first = ResearchExecutionRecord.accept(task())
        val fence = first.attempt
        val interrupted = first.transition(fence.attemptId, 0, ResearchAttemptState.SEARCHING, 20, runId = "sent", profileUid = "a", dispatched = true)
            .transition(fence.attemptId, 0, ResearchAttemptState.INTERRUPTED, 30)
        assertEquals(ResearchNextAction.RECONCILE_REQUESTS, interrupted.attempt.nextAction)
        assertTrue(runCatching { interrupted.resume("tap", 40) }.isFailure)
        val resumed = interrupted.resume("tap", 40, reconciled = true)
        assertEquals(1L, resumed.attempt.generation)
        assertEquals(interrupted.attempt, resumed.previousAttempts.single())
        assertEquals(setOf("sent"), resumed.previousAttempts.single().dispatchedRunIds)
        assertEquals(ResearchAttemptState.INTERRUPTED, resumed.previousAttempts.single().terminalOutcome)
        val receipt = resumed.receipts.last()
        assertEquals("UNKNOWN_REMOTE_REQUESTS_REVIEWED_NEW_ATTEMPT_AUTHORIZED", receipt.code)
        assertEquals("tap", receipt.actionId)
        assertEquals(fence.attemptId, receipt.attemptId)
        assertEquals(0L, receipt.generation)
        assertFalse(receipt.dispatched)
        assertEquals(resumed, resumed.resume("tap", 50, reconciled = true))
    }

    @Test fun completionNeedsRetainedDeliveryAndNeverChangesAcceptedIntent() {
        val record = ResearchExecutionRecord.accept(task())
        assertTrue(runCatching { record.transition(record.attempt.attemptId, 0, ResearchAttemptState.COMPLETED, 20) }.isFailure)
        val delivered = record.transition(record.attempt.attemptId, 0, ResearchAttemptState.PARTIAL, 20, checkpointId = "delivery-receipt", readableSources = 4)
        assertEquals(ResearchAttemptState.PARTIAL, delivered.attempt.terminalOutcome)
        assertEquals("REQUIRED", delivered.accepted.intent)
        assertEquals("WEB_ONLY", delivered.accepted.sourceScope)
        assertEquals(90, delivered.accepted.targetSources)
    }
}
