package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringAuthorizationTest {
    private fun fixture(): Pair<EngineeringTaskRow, EngineeringRunRow> {
        val task = EngineeringTaskRow("task", "owner", "repo", "fix/branch", "a".repeat(40), state = EngineeringState.COMMITTING.name, updatedAtEpochMs = 100)
        val run = EngineeringRunRow("task", "connection", "[]", "", "Fix", 100, "main", approvedActor = "42", approvedAtEpochMs = 100, approvalExpiresAtEpochMs = 200)
        return task to run.copy(patchDigest = GitHubEngineeringAuthorization.digest(task, run))
    }

    @Test fun approvalBindsExactPatchTaskAccountBranchAndRevision() {
        val (task, run) = fixture()
        GitHubEngineeringAuthorization.requireApproval(task, run, "42", 150)
        for (changed in listOf(task.copy(taskId = "different"), task.copy(workingBranch = "main"), task.copy(baseSha = "b".repeat(40)), task.copy(attempt = 2))) {
            assertTrue(runCatching { GitHubEngineeringAuthorization.requireApproval(changed, run, "42", 150) }.isFailure)
        }
        for (changed in listOf(run.copy(patchJson = "changed"), run.copy(commitMessage = "different"), run.copy(connectionUid = "other"), run.copy(approvalRevoked = true))) {
            assertTrue(runCatching { GitHubEngineeringAuthorization.requireApproval(task, changed, "42", 150) }.isFailure)
        }
        assertTrue(runCatching { GitHubEngineeringAuthorization.requireApproval(task, run, "43", 150) }.isFailure)
        assertTrue(runCatching { GitHubEngineeringAuthorization.requireApproval(task, run, "42", 200) }.isFailure)
        assertTrue(runCatching { GitHubEngineeringAuthorization.requireApproval(task, run, "42", 99) }.isFailure)
    }

    @Test fun patchValidationRejectsSecretsDuplicatePathsAndOversizedBodies() {
        assertTrue(runCatching { GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("a.kt", "old", "ghp_" + "a".repeat(32)))) }.isFailure)
        assertTrue(runCatching { GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("a", "old", "new"), EngineeringFilePatch("a", "old", "new"))) }.isFailure)
        assertTrue(runCatching { GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("a", "old", "x".repeat(2 * 1024 * 1024 + 1)))) }.isFailure)
        val json = GitHubEngineeringAuthorization.patchJson(listOf(EngineeringFilePatch("a", "old", "new")))
        assertFalse(json.isBlank())
    }
}
