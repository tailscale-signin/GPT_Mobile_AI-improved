package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubEngineeringRecoveryTest {
    private val head = "a".repeat(40)

    @Test
    fun commitNeedsInspection() {
        val snapshot = GitSagaSnapshot("op", head, stage = GitSagaStage.COMMIT_CREATED)
        assertEquals(EngineeringRecoveryAction.InspectRemote, GitHubEngineeringRecovery.plan(snapshot))
    }

    @Test
    fun conflictCannotResume() {
        val snapshot = GitSagaSnapshot("op", head, stage = GitSagaStage.CONFLICTED)
        assertEquals(EngineeringRecoveryAction.Blocked, GitHubEngineeringRecovery.plan(snapshot))
    }
}
