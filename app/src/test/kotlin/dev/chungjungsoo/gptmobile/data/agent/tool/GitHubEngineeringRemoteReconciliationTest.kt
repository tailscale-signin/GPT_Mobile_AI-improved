package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringRemoteReconciliationTest {
    private val base = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val commit = "c".repeat(40)
    private val evidence = RemoteCommitEvidence(commit, base, tree)
    private val snapshot = GitSagaSnapshot(
        operationId = "op",
        expectedHeadSha = base,
        stage = GitSagaStage.COMMIT_CREATED,
        treeSha = tree,
        commitSha = commit,
    )

    @Test
    fun requiresApprovalBeforeRefUpdate() {
        assertEquals(
            EngineeringRefDecision.RequiresApproval("op"),
            EngineeringRemoteReconciliation.decide(snapshot, base, evidence, false),
        )
    }

    @Test
    fun permitsOnlyVerifiedApprovedUpdate() {
        assertEquals(
            EngineeringRefDecision.Ready(base, commit),
            EngineeringRemoteReconciliation.decide(snapshot, base, evidence, true),
        )
    }

    @Test
    fun mismatchedTreeIsConflict() {
        val altered = evidence.copy(treeSha = "d".repeat(40))
        assertTrue(
            EngineeringRemoteReconciliation.decide(snapshot, base, altered, true)
                is EngineeringRefDecision.Diverged,
        )
    }

    @Test
    fun alreadyAppliedDoesNotNeedSecondWrite() {
        assertEquals(
            EngineeringRefDecision.Applied(commit),
            EngineeringRemoteReconciliation.decide(snapshot, commit, evidence, false),
        )
    }
}
