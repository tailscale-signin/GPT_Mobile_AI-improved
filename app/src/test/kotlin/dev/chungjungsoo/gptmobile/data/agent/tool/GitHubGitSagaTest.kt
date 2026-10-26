package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubGitSagaTest {
    private val base = "a".repeat(40)
    private val commit = "b".repeat(40)
    private val tree = "c".repeat(40)

    @Test
    fun processDeathAfterCommitAllowsRefUpdateWithoutNewCommit() {
        val snapshot = GitSagaSnapshot(
            operationId = "operation-1",
            expectedHeadSha = base,
            stage = GitSagaStage.COMMIT_CREATED,
            treeSha = tree,
            commitSha = commit
        )
        val result = GitHubGitSaga.reconcile(snapshot, base)
        assertEquals(GitSagaReconciliation.VerifyCommitObject, result)
        assertTrue(GitHubGitSaga.reconcileVerified(snapshot, base, commit, base, tree) is GitSagaReconciliation.ReadyToUpdateRef)
    }

    @Test
    fun mismatchedRemoteCommitIsNotAllowedToUpdateRef() {
        val snapshot = GitSagaSnapshot(
            operationId = "operation-1",
            expectedHeadSha = base,
            stage = GitSagaStage.COMMIT_CREATED,
            treeSha = tree,
            commitSha = commit
        )
        assertTrue(
            GitHubGitSaga.reconcileVerified(snapshot, base, commit, "d".repeat(40), tree)
                is GitSagaReconciliation.Conflict
        )
    }

    @Test
    fun processDeathAfterRefUpdateRecognizesRemoteSuccess() {
        val snapshot = GitSagaSnapshot(
            operationId = "operation-1",
            expectedHeadSha = base,
            stage = GitSagaStage.COMMIT_CREATED,
            treeSha = tree,
            commitSha = commit
        )
        val result = GitHubGitSaga.reconcile(snapshot, commit)
        assertEquals(GitSagaReconciliation.VerifyCommitObject, result)
        val verified = GitHubGitSaga.reconcileVerified(snapshot, commit, commit, base, tree)
        assertTrue(verified is GitSagaReconciliation.AlreadyApplied)
        assertEquals(GitSagaStage.REF_UPDATED, (verified as GitSagaReconciliation.AlreadyApplied).snapshot.stage)
    }

    @Test
    fun branchAtCommitStillRejectsMismatchedRemoteTree() {
        val snapshot = GitSagaSnapshot(
            operationId = "operation-1",
            expectedHeadSha = base,
            stage = GitSagaStage.COMMIT_CREATED,
            treeSha = tree,
            commitSha = commit
        )
        assertEquals(GitSagaReconciliation.VerifyCommitObject, GitHubGitSaga.reconcile(snapshot, commit))
        assertTrue(
            GitHubGitSaga.reconcileVerified(snapshot, commit, commit, base, "d".repeat(40))
                is GitSagaReconciliation.Conflict
        )
    }

    @Test
    fun advancedBranchIsConflict() {
        val snapshot = GitSagaSnapshot(
            operationId = "operation-1",
            expectedHeadSha = base,
            stage = GitSagaStage.COMMIT_CREATED,
            treeSha = tree,
            commitSha = commit
        )
        assertTrue(GitHubGitSaga.reconcile(snapshot, "d".repeat(40)) is GitSagaReconciliation.Conflict)
    }

    @Test
    fun missingCommitRequiresRemoteInspection() {
        assertEquals(
            GitSagaReconciliation.InspectRemoteCommit,
            GitHubGitSaga.reconcile(GitSagaSnapshot("operation-1", base), base)
        )
    }

    @Test
    fun commitCannotBeRecordedBeforeTree() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubGitSaga.recordCommit(GitSagaSnapshot("operation-1", base), commit)
        }
    }
}
