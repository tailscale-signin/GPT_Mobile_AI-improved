package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A writer change is a sealed successor, never an edit to a previously admitted snapshot. */
internal object CombinedSuccessorSnapshot {
    fun prepare(original: CombinedInputSnapshot, predecessorRunId: String, writer: PlatformV2): CombinedInputSnapshot {
        original.bindAcceptedStage()
        val policy = requireNotNull(original.combined) { "COMBINED_SUCCESSOR_POLICY_UNAVAILABLE" }
        require(original.version >= 6 && predecessorRunId.isNotBlank() && writer.enabled && writer.uid != original.profileUid && writer.uid in policy.requested.participantUids) { "COMBINED_SUCCESSOR_WRITER_NOT_ACCEPTED" }
        require(original.writerAuthorityRevisions[writer.uid] == ConfiguredDispatchAuthority.profileRevision(writer)) { "COMBINED_SUCCESSOR_AUTHORITY_CHANGED" }
        require(original.contributionsTransferable && original.evidence.records.all { it.id in original.transferableEvidenceIds }) { "COMBINED_SUCCESSOR_TRANSFER_DENIED" }
        require(original.sources.none { it.localOnly } || writer.compatibleType == ClientType.LITERT_LM) { "COMBINED_SUCCESSOR_LOCAL_ONLY" }
        val acceptedWriter = writer.copy(model = policy.requested.requestedModels[writer.uid] ?: writer.model, temperature = policy.requested.temperatureFor(writer.uid, writer.temperature))
        val revision = UUID.randomUUID().toString()
        return original.copy(
            profileUid = writer.uid,
            model = acceptedWriter.model,
            temperature = acceptedWriter.temperature,
            systemPrompt = acceptedWriter.systemPrompt.orEmpty(),
            configKey = benchmarkConfigKey(acceptedWriter),
            combined = policy.forRole(CombinedRunRole.SYNTHESIS, writer.uid),
            revisionId = revision,
            prompt = revisionPrompt(original.prompt, revision),
            snapshotId = UUID.randomUUID().toString(),
            predecessorSnapshotId = original.snapshotId,
            predecessorRunId = predecessorRunId,
            inputDigest = "",
            resumeFromRunId = null,
            committedPrefix = "",
            deliveryResumeFromRevisionId = null
        ).seal()
    }

    fun validate(original: CombinedInputSnapshot, successor: CombinedInputSnapshot, predecessorRunId: String) {
        original.bindAcceptedStage()
        successor.bindAcceptedStage()
        require(successor.predecessorSnapshotId == original.snapshotId && successor.predecessorRunId == predecessorRunId && successor.snapshotId != original.snapshotId && successor.profileUid != original.profileUid) { "COMBINED_SUCCESSOR_LINEAGE_CHANGED" }
        require(successor.profileUid in original.combined!!.requested.participantUids && original.writerAuthorityRevisions.containsKey(successor.profileUid)) { "COMBINED_SUCCESSOR_WRITER_NOT_ACCEPTED" }
        require(original.contributionsTransferable && original.evidence.records.all { it.id in original.transferableEvidenceIds }) { "COMBINED_SUCCESSOR_TRANSFER_DENIED" }
        require(successor.prompt == revisionPrompt(original.prompt, successor.revisionId)) { "COMBINED_SUCCESSOR_PROMPT_CHANGED" }
        require(successor.copy(profileUid = original.profileUid, model = original.model, temperature = original.temperature, systemPrompt = original.systemPrompt, configKey = original.configKey, combined = original.combined, revisionId = original.revisionId, prompt = original.prompt, snapshotId = original.snapshotId, predecessorSnapshotId = original.predecessorSnapshotId, predecessorRunId = original.predecessorRunId, inputDigest = original.inputDigest, resumeFromRunId = original.resumeFromRunId, committedPrefix = original.committedPrefix, deliveryResumeFromRevisionId = original.deliveryResumeFromRevisionId) == original) { "COMBINED_SUCCESSOR_CORPUS_CHANGED" }
    }

    private fun revisionPrompt(prompt: String, revisionId: String): String {
        val record = Json.parseToJsonElement(prompt) as JsonObject
        return JsonObject(record + ("target_revision_id" to JsonPrimitive(revisionId))).toString()
    }
}
