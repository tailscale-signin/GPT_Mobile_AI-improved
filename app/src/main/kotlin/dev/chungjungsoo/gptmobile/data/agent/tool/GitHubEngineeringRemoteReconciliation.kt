package dev.chungjungsoo.gptmobile.data.agent.tool

internal data class RemoteCommitEvidence(val sha: String, val parentSha: String, val treeSha: String)

internal object EngineeringRemoteReconciliation {
    fun verify(snapshot: GitSagaSnapshot, head: String, evidence: RemoteCommitEvidence): GitSagaReconciliation =
        GitHubGitSaga.reconcileVerified(snapshot, head, evidence.sha, evidence.parentSha, evidence.treeSha)
}
