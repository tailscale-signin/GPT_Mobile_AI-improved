package dev.chungjungsoo.gptmobile.data.workspace

/** Publication outcomes are decided under the same transaction as record ownership. */
internal sealed interface ProtectedCheckpointWriteResult {
    data object Stored : ProtectedCheckpointWriteResult
    data object AlreadySaved : ProtectedCheckpointWriteResult
    data object Rejected : ProtectedCheckpointWriteResult
    data class Conflict(val failure: WorkspaceEvidenceConflict) : ProtectedCheckpointWriteResult

    /** Existing callers retain their Boolean contract; conflicts keep their application cause. */
    fun requireSaved(): Boolean = when (this) {
        Stored, AlreadySaved -> true
        Rejected -> false
        is Conflict -> throw failure
    }
}
