package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptRejected
import dev.chungjungsoo.gptmobile.data.localruntime.AdmissionOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchDispatchLifecycleTest {
    private val root = "1:2"
    private val task = AcceptedResearchTask(
        taskId = researchHash("task:$root"), rootWorkId = root, chatId = 1, originatingUserMessageId = 2,
        taskText = "Compare the evidence", intent = "RESEARCH", participantUids = listOf("p"), primaryUid = "p", settingsRevision = "settings", sourceAuthorityRevision = "authority", acceptedAt = 1
    )

    @Test fun aQueuedOldAttemptCannotDispatchAfterAnExplicitResume() = runTest {
        val store = mockk<ResearchSessionStore>()
        val original = ResearchExecutionRecord.accept(task)
        val replacement = original.transition(original.attempt.attemptId, 0, ResearchAttemptState.FAILED_NO_EVIDENCE, 2).resume("retry", 3)
        coEvery { store.execution(root, true) } returns replacement
        val lifecycle = ResearchDispatchLifecycle(store, root, original.attempt, true, "run", "p")
        var dispatches = 0
        val error = runCatching {
            withContext(lifecycle + PhysicalAttemptBoundary { dispatches++ }) { PhysicalAttemptBoundary.dispatch() }
        }.exceptionOrNull()
        assertTrue(error is PhysicalAttemptRejected)
        assertEquals(0, dispatches)
        coVerify(exactly = 1) { store.execution(root, true) }
    }

    @Test fun cancellationDuringAResourceWaitRejectsTheSubsequentAcquisition() = runTest {
        val store = mockk<ResearchSessionStore>()
        val original = ResearchExecutionRecord.accept(task)
        val cancelled = original.transition(original.attempt.attemptId, 0, ResearchAttemptState.CANCELLED, 2)
        coEvery { store.execution(root, false) } returns cancelled
        val lifecycle = ResearchDispatchLifecycle(store, root, original.attempt, false, "run", "p")
        val error = runCatching { lifecycle.admission(AdmissionOutcome.Waiting("run", setOf("local_runtime"), listOf("benchmark"), true, 90_000)) }.exceptionOrNull()
        assertTrue(error is PhysicalAttemptRejected)
        coVerify(exactly = 1) { store.execution(root, false) }
    }
}
