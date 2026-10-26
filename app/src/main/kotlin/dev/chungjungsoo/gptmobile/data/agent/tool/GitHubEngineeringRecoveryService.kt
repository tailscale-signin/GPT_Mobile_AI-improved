package dev.chungjungsoo.gptmobile.data.agent.tool

/** Enumerates durable unfinished work without performing remote writes or assuming authorization. */
internal data class EngineeringRecoveryItem(
    val taskId: String,
    val operationId: String,
    val action: EngineeringRecoveryAction,
)

internal class GitHubEngineeringRecoveryService(
    private val ledger: GitHubEngineeringRoomLedger,
) {
    suspend fun pending(): List<EngineeringRecoveryItem> = buildList {
        for (task in ledger.activeTasks()) {
            for (row in ledger.operations(task.taskId)) {
                val snapshot = GitSagaSnapshot(
                    operationId = row.operationId,
                    expectedHeadSha = row.expectedHeadSha,
                    stage = GitSagaStage.valueOf(row.stage),
                    blobManifestDigest = row.blobManifestDigest,
                    treeSha = row.treeSha,
                    commitSha = row.commitSha,
                    remoteRefSha = row.remoteRefSha,
                )
                val action = GitHubEngineeringRecovery.plan(snapshot)
                if (action != EngineeringRecoveryAction.Finished) {
                    add(EngineeringRecoveryItem(task.taskId, row.operationId, action))
                }
            }
        }
    }
}
