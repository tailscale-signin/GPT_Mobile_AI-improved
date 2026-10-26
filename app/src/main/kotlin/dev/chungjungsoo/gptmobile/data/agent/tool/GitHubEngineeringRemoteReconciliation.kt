package dev.chungjungsoo.gptmobile.data.agent.tool

internal data class RemoteCommitEvidence(val sha: String, val parentSha: String, val treeSha: String)

internal object EngineeringRemoteReconciliation {
    fun verify(snapshot: GitSagaSnapshot, head: String, evidence: RemoteCommitEvidence): GitSagaReconciliation =
        GitHubGitSaga.reconcileVerified(snapshot, head, evidence.sha, evidence.parentSha, evidence.treeSha)
}

internal sealed interface EngineeringRefDecision {
    data class Applied(val sha: String) : EngineeringRefDecision
    data class RequiresApproval(val operationId: String) : EngineeringRefDecision
    data class Ready(val expectedHeadSha: String, val commitSha: String) : EngineeringRefDecision
    data class Diverged(val actualHeadSha: String) : EngineeringRefDecision
    data object Unverified : EngineeringRefDecision
}

internal fun EngineeringRemoteReconciliation.decide(
    snapshot: GitSagaSnapshot,
    remoteHead: String,
    evidence: RemoteCommitEvidence,
    approved: Boolean,
): EngineeringRefDecision {
    if (snapshot.stage != GitSagaStage.COMMIT_CREATED &&
        snapshot.stage != GitSagaStage.REF_UPDATED
    ) return EngineeringRefDecision.Unverified
    return when (val result = verify(snapshot, remoteHead, evidence)) {
        is GitSagaReconciliation.AlreadyApplied ->
            EngineeringRefDecision.Applied(requireNotNull(result.snapshot.commitSha))
        is GitSagaReconciliation.ReadyToUpdateRef ->
            if (approved) EngineeringRefDecision.Ready(snapshot.expectedHeadSha, requireNotNull(snapshot.commitSha))
            else EngineeringRefDecision.RequiresApproval(snapshot.operationId)
        is GitSagaReconciliation.Conflict -> EngineeringRefDecision.Diverged(result.actualHeadSha)
        GitSagaReconciliation.InspectRemoteCommit,
        GitSagaReconciliation.VerifyCommitObject -> EngineeringRefDecision.Unverified
    }
}
