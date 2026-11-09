package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubEngineeringPublicationTest {
    private val base = "a".repeat(40)
    private val sha = "b".repeat(40)
    private val task = EngineeringTaskRow("task", "owner", "repo", "work", base, state = "CI_PENDING", updatedAtEpochMs = 1)
    private val run = EngineeringRunRow("task", "connection", "[]", "patch", "saved message", 1, "main", approvedActor = "actor", approvedAtEpochMs = 1)
    private val operation = EngineeringGitOperationRow("op", "task", "idempotency", base, stage = "REF_UPDATED", commitSha = sha, remoteRefSha = sha, updatedAtEpochMs = 2)

    @Test fun verifiedRefUpdateCanRestoreOnePublicationNotificationAfterProcessDeath() {
        val proof = requireNotNull(GitHubEngineeringPublication.verified(task, run, listOf(operation)))
        assertEquals(JsonPrimitive("PUBLISHED"), proof["approval_state"])
        assertEquals(JsonPrimitive(true), proof["remote_state_verified"])
        assertEquals(JsonPrimitive(sha), proof["sha"])
        assertNotNull(GitHubEngineeringPublication.verified(task.copy(state = "CI_FAILED"), run, listOf(operation)))
    }

    @Test fun pendingCancelledForeignOrUnverifiedPublicationCannotResumeTheChat() {
        for (state in listOf("AWAITING_APPROVAL", "COMMITTING", "CANCELLED", "CONFLICTED")) {
            assertNull(GitHubEngineeringPublication.verified(task.copy(state = state), run, listOf(operation)))
        }
        assertNull(GitHubEngineeringPublication.verified(task, run.copy(approvalRevoked = true), listOf(operation)))
        assertNull(GitHubEngineeringPublication.verified(task, run.copy(taskId = "other"), listOf(operation)))
        assertNull(GitHubEngineeringPublication.verified(task, run, listOf(operation.copy(stage = "COMMIT_CREATED"))))
        assertNull(GitHubEngineeringPublication.verified(task, run, listOf(operation.copy(remoteRefSha = base))))
        assertNull(GitHubEngineeringPublication.verified(task, run, listOf(operation.copy(expectedHeadSha = sha))))
        assertNull(GitHubEngineeringPublication.verified(task, run, listOf(operation.copy(taskId = "other"))))
    }
}
