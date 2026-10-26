package dev.chungjungsoo.gptmobile.data.agent.tool

/**
 * Durable Git Data API operation snapshot. Persist each successful stage before starting the next.
 * An ambiguous network failure requires remote reconciliation, never blind replay.
 */
internal enum class GitSagaStage {
    PENDING,
    BLOBS_STAGED,
    TREE_CREATED,
    COMMIT_CREATED,
    REF_UPDATED,
    CONFLICTED,
}

internal data class GitSagaSnapshot(
    val operationId: String,
    val expectedHeadSha: String,
    val stage: GitSagaStage = GitSagaStage.PENDING,
    val blobManifestDigest: String? = null,
    val treeSha: String? = null,
    val commitSha: String? = null,
    val remoteRefSha: String? = null,
) {
    init {
        require(operationId.isNotBlank())
        require(expectedHeadSha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")))
    }
}

internal sealed interface GitSagaReconciliation {
    data class AlreadyApplied(val snapshot: GitSagaSnapshot) : GitSagaReconciliation
    data class ReadyToUpdateRef(val snapshot: GitSagaSnapshot) : GitSagaReconciliation
    data object VerifyCommitObject : GitSagaReconciliation
    data class Conflict(val actualHeadSha: String) : GitSagaReconciliation
    data object InspectRemoteCommit : GitSagaReconciliation
}

/** Pure decision logic; caller must verify the fetched remote commit parent/tree before ref update. */
internal object GitHubGitSaga {
    fun reconcile(snapshot: GitSagaSnapshot, remoteHeadSha: String): GitSagaReconciliation {
        val commit = snapshot.commitSha ?: return GitSagaReconciliation.InspectRemoteCommit
        if (remoteHeadSha == commit) {
            // Ref identity does not establish the remote commit parent or tree.
            return GitSagaReconciliation.VerifyCommitObject
        }
        if (remoteHeadSha != snapshot.expectedHeadSha) return GitSagaReconciliation.Conflict(remoteHeadSha)
        return GitSagaReconciliation.VerifyCommitObject
    }


    fun reconcileVerified(
        snapshot: GitSagaSnapshot,
        remoteHeadSha: String,
        fetchedCommitSha: String,
        fetchedParentSha: String,
        fetchedTreeSha: String,
    ): GitSagaReconciliation {
        val commit = snapshot.commitSha ?: return GitSagaReconciliation.InspectRemoteCommit
        if (fetchedCommitSha != commit ||
            fetchedParentSha != snapshot.expectedHeadSha ||
            fetchedTreeSha != snapshot.treeSha
        ) {
            return GitSagaReconciliation.Conflict(remoteHeadSha)
        }
        if (remoteHeadSha == commit) {
            return GitSagaReconciliation.AlreadyApplied(
                snapshot.copy(stage = GitSagaStage.REF_UPDATED, remoteRefSha = remoteHeadSha),
            )
        }
        if (remoteHeadSha != snapshot.expectedHeadSha) return GitSagaReconciliation.Conflict(remoteHeadSha)
        return GitSagaReconciliation.ReadyToUpdateRef(snapshot)
    }

    fun recordTree(snapshot: GitSagaSnapshot, treeSha: String): GitSagaSnapshot {
        require(snapshot.stage == GitSagaStage.BLOBS_STAGED)
        require(treeSha.isNotBlank())
        return snapshot.copy(stage = GitSagaStage.TREE_CREATED, treeSha = treeSha)
    }

    fun recordCommit(snapshot: GitSagaSnapshot, commitSha: String): GitSagaSnapshot {
        require(snapshot.stage == GitSagaStage.TREE_CREATED && snapshot.treeSha != null)
        require(commitSha.isNotBlank())
        return snapshot.copy(stage = GitSagaStage.COMMIT_CREATED, commitSha = commitSha)
    }
}
