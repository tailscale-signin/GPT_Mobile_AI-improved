package dev.chungjungsoo.gptmobile.data.agent.tool

/** Recovery decisions do not execute GitHub writes. */
internal sealed interface EngineeringRecoveryAction {
    data object InspectRemote : EngineeringRecoveryAction
    data object ResumeStaging : EngineeringRecoveryAction
    data object Finished : EngineeringRecoveryAction
    data object Blocked : EngineeringRecoveryAction
}

internal object GitHubEngineeringRecovery {
    fun plan(snapshot: GitSagaSnapshot): EngineeringRecoveryAction = when (snapshot.stage) {
        GitSagaStage.PENDING, GitSagaStage.BLOBS_STAGED, GitSagaStage.TREE_CREATED ->
            EngineeringRecoveryAction.ResumeStaging
        GitSagaStage.COMMIT_CREATED, GitSagaStage.REF_UPDATED ->
            EngineeringRecoveryAction.InspectRemote
        GitSagaStage.CONFLICTED -> EngineeringRecoveryAction.Blocked
    }
}
