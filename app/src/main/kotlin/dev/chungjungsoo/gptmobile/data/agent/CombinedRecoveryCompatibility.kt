package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A new output revision may reuse the same immutable, authorized input corpus. */
internal fun sameCombinedRecoveryCorpus(first: CombinedInputSnapshot, second: CombinedInputSnapshot): Boolean = runCatching {
    if (first.version < 6 || second.version < 6) return@runCatching first.copy(resumeFromRunId = null, committedPrefix = "") == second.copy(resumeFromRunId = null, committedPrefix = "")
    first.bindAcceptedStage()
    second.bindAcceptedStage()
    fun normalize(value: CombinedInputSnapshot): CombinedInputSnapshot {
        val prompt = Json.parseToJsonElement(value.prompt) as JsonObject
        return value.copy(
            prompt = JsonObject(prompt - "target_revision_id").toString(),
            revisionId = "", snapshotId = "", inputDigest = "", predecessorSnapshotId = null,
            predecessorRunId = null, resumeFromRunId = null, committedPrefix = "", deliveryResumeFromRevisionId = null
        )
    }
    normalize(first) == normalize(second)
}.getOrDefault(false)

/** Direct reader calls have durable tool reservations but no model-invocation row. */
internal fun hasCommittedVerifierRead(reservations: Map<String, CombinedReservation>, acceptedProfiles: List<String>, callId: String, startedAt: Long?, completedAt: Long?): Boolean {
    if (!callId.startsWith("verifier-read:") || startedAt == null || completedAt == null || completedAt < startedAt) return false
    return acceptedProfiles.any { uid ->
        reservations["tool:VERIFIER:$uid::CLIENT:$callId"]?.let { row ->
            row.role == CombinedRunRole.VERIFIER && row.status == CombinedReservationStatus.SETTLED && row.createdAtMillis / 1000 in startedAt..completedAt
        } == true
    }
}
