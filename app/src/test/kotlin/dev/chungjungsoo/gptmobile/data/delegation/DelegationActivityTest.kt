package dev.chungjungsoo.gptmobile.data.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DelegationActivityTest {
    @Test
    fun historyIsBoundedAndDeclaresOmittedEvents() {
        var activity = DelegationActivity(runId = "run", chatId = 1, policyRevision = "revision", primaryUid = "primary")
        repeat(250) { activity = activity.append(DelegationEvent(DelegationEventKind.STEP_QUEUED)) }
        assertEquals(200, activity.events.size)
        assertEquals(50, activity.omittedEvents)
        assertNull(activity.finishedAt)
        assertNull(activity.events.first().inputTokens)
    }

    @Test
    fun helperOutputDoesNotMarkTheParentRunComplete() {
        val run = DelegationActivity(runId = "run", chatId = 1, policyRevision = "revision", primaryUid = "primary")
            .append(DelegationEvent(DelegationEventKind.STEP_FINISHED, status = DelegationStepStatus.RETURNED_OUTPUT))
        assertEquals(DelegationStepStatus.RUNNING, run.status)
        val ended = run.append(DelegationEvent(DelegationEventKind.RUN_TERMINAL, atMs = 100, status = DelegationStepStatus.CANCELED))
        assertEquals(DelegationStepStatus.CANCELED, ended.status)
        assertEquals(100L, ended.finishedAt)
    }

    @Test fun partialOutputAndFailedDeliveryStaySeparateAndTerminalStateCannotBeOverwritten() {
        val failed = DelegationActivity(runId = "run", chatId = 1, policyRevision = "policy", primaryUid = "writer")
            .append(DelegationEvent(DelegationEventKind.RUN_TERMINAL, role = DelegationRole.PRIMARY, status = DelegationStepStatus.FAILED, stageState = DelegationStageState(StageExecution.FAILED, OutputQuality.PARTIAL)))
        assertEquals(false, failed.stageStates.getValue("primary").delivered)
        assertEquals(OutputQuality.PARTIAL, failed.stageStates.getValue("primary").output)
        assertEquals(failed, failed.append(DelegationEvent(DelegationEventKind.RUN_TERMINAL, role = DelegationRole.PRIMARY, status = DelegationStepStatus.RETURNED_OUTPUT)))
    }
}
