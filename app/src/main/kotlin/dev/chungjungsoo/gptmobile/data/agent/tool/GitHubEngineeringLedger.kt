package dev.chungjungsoo.gptmobile.data.agent.tool

/**
 * Immutable ledger projection. Storage adapters must atomically persist the event and the
 * resulting task state, and enforce a unique (taskId, sequence) constraint.
 */
internal data class EngineeringLedgerEvent(
    val taskId: String,
    val sequence: Long,
    val attempt: Int,
    val previousState: EngineeringState,
    val evidence: EngineeringEvidence,
    val resultingState: EngineeringState,
    val evidenceDigest: String,
) {
    init {
        require(taskId.isNotBlank())
        require(sequence > 0)
        require(attempt > 0)
        require(evidenceDigest.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Evidence digest must be SHA-256 hex, not raw evidence content."
        }
    }
}

internal data class EngineeringLedgerSnapshot(
    val taskId: String,
    val sequence: Long = 0,
    val attempt: Int = 1,
    val state: EngineeringState = EngineeringState.CREATED,
)

/** Pure projection; neither evidence origin nor authenticity is established here. */
internal object GitHubEngineeringLedger {
    fun append(
        snapshot: EngineeringLedgerSnapshot,
        event: EngineeringLedgerEvent,
    ): EngineeringLedgerSnapshot {
        require(event.taskId == snapshot.taskId) { "Cross-task ledger event" }
        require(event.sequence == snapshot.sequence + 1) { "Missing or duplicate ledger sequence" }
        require(event.attempt == snapshot.attempt) { "Unexpected attempt" }
        require(event.previousState == snapshot.state) { "Stale ledger state" }
        val expected = GitHubEngineeringStateMachine.advance(snapshot.state, event.evidence)
        require(event.resultingState == expected) { "Forged resulting state" }
        return snapshot.copy(sequence = event.sequence, state = expected)
    }
}
