package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubEngineeringStateMachineTest {
    @Test
    fun approvalIsRequiredWhenPolicyRequestsIt() {
        val waiting = GitHubEngineeringStateMachine.advance(
            EngineeringState.PATCH_READY,
            EngineeringEvidence.APPROVAL_REQUIRED,
        )
        assertEquals(EngineeringState.AWAITING_APPROVAL, waiting)
        assertEquals(
            EngineeringState.COMMITTING,
            GitHubEngineeringStateMachine.advance(waiting, EngineeringEvidence.APPROVAL_GRANTED),
        )
    }

    @Test
    fun ciTransportSuccessDoesNotCompleteTask() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringStateMachine.advance(
                EngineeringState.CI_PENDING,
                EngineeringEvidence.POLICY_COMPLETION_CONFIRMED,
            )
        }
    }

    @Test
    fun hardwareVerificationIsAnExplicitGate() {
        val awaiting = GitHubEngineeringStateMachine.advance(
            EngineeringState.CI_VERIFIED,
            EngineeringEvidence.HARDWARE_REQUIRED,
        )
        assertEquals(EngineeringState.AWAITING_HARDWARE_VERIFICATION, awaiting)
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringStateMachine.advance(awaiting, EngineeringEvidence.POLICY_COMPLETION_CONFIRMED)
        }
        val verified = GitHubEngineeringStateMachine.advance(awaiting, EngineeringEvidence.HARDWARE_RECEIPT_VERIFIED)
        assertEquals(
            EngineeringState.COMPLETED,
            GitHubEngineeringStateMachine.advance(verified, EngineeringEvidence.POLICY_COMPLETION_CONFIRMED),
        )
    }

    @Test
    fun staleBranchHeadTransitionsToConflict() {
        assertEquals(
            EngineeringState.CONFLICTED,
            GitHubEngineeringStateMachine.advance(EngineeringState.COMMITTING, EngineeringEvidence.REF_CONFLICT),
        )
    }

    @Test
    fun completedTasksCannotBeMutated() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubEngineeringStateMachine.advance(EngineeringState.COMPLETED, EngineeringEvidence.INSPECTION_STARTED)
        }
    }
}
