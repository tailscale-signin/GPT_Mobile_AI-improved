package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A provider wait retains the exact receipt journal, independently of its new coroutine guard. */
internal object GitHubProviderToolCheckpoint {
    data class Resolved(val binding: String, val evidenceDigest: String?, val journalDigest: String? = null)

    /** Preserve the original journal and fence its first restoration against the saved provider evidence. */
    suspend fun resolve(directory: File, chatId: Int, scope: String, authorityRevision: String, expected: GitHubProviderCheckpoint?, checkpoint: JsonObject?, persistent: Boolean, handoffTaskBinding: String? = null): Resolved {
        val retained = binding(scope, authorityRevision, expected, checkpoint)
        if (expected == null) {
            if (!persistent || handoffTaskBinding == null || checkpoint?.get("state") != JsonPrimitive("TOOL_HANDOFF")) return Resolved(retained, null)
            val context = checkpoint["context"] as? JsonObject
            val original = (context?.get("tool_checkpoint_binding") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (context?.get("task_binding") != JsonPrimitive(handoffTaskBinding) ||
                original == null ||
                original == retained ||
                !original.startsWith("$scope:") ||
                original.length !in (scope.length + 2)..4096 ||
                !scope.startsWith("$chatId:")
            ) {
                return Resolved(retained, null)
            }
            val journal = ToolBatchCheckpoint.forTurn(directory, chatId, original, persistent = true)
            val digest = journal.settledHandoffDigest(checkpoint) ?: return Resolved(retained, null)
            return Resolved(original, null, digest)
        }
        val saved = requireNotNull(checkpoint)
        val context = saved["context"] as? JsonObject
        val digest = (saved["provider_evidence_digest"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (context == null || context["task_binding"] != JsonPrimitive(expected.binding) || digest == null || !digest.matches(Regex("[a-f0-9]{64}"))) {
            val reason = if (context?.get("task_binding") != JsonPrimitive(expected.binding)) "TASK_BINDING_CHANGED" else "EVIDENCE_DIGEST_INVALID"
            throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED", ProviderRecoveryFailure.interruption(saved), ProviderRecoveryFailure.checkpointCorrelation(expected, saved, reason))
        }
        val protectedDigest = GitHubProviderReplayEvidence.protectedJournalDigest(saved)
        val completeJournal = GitHubProviderReplayEvidence.journalBindingKeys.any { it in saved }
        if (completeJournal && protectedDigest == null) {
            val validLegacyBoundary = GitHubProviderReplayEvidence.hasProtectedJournalBoundary(saved)
            throw ProviderRecoveryFailure(
                if (validLegacyBoundary) "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE" else "PROVIDER_WAIT_CHECKPOINT_CHANGED",
                ProviderRecoveryFailure.interruption(saved),
                ProviderRecoveryFailure.checkpointCorrelation(expected, saved, if (validLegacyBoundary) "TOOL_RESULT_SEMANTICS_UNPROVEN" else "EVIDENCE_DIGEST_INVALID")
            )
        }
        val legacy = if (persistent && context["tool_checkpoint_binding"] == null) {
            ToolBatchCheckpoint.legacyProviderBinding(directory, chatId, scope, expected, saved)
        } else {
            null
        }
        val selected = legacy ?: retained
        val journalDigest = if (completeJournal || !persistent) {
            protectedDigest
        } else {
            val journal = ToolBatchCheckpoint.forTurn(directory, chatId, selected, persistent = true)
            try {
                journal.reconciledProviderJournalDigest(saved)
            } catch (failure: ProviderRecoveryFailure) {
                throw ProviderRecoveryFailure(failure.code, ProviderRecoveryFailure.interruption(saved), failure.correlation)
            }
        }
        if (!completeJournal && persistent && journalDigest == null) {
            throw ProviderRecoveryFailure("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", ProviderRecoveryFailure.interruption(saved), ProviderRecoveryFailure.checkpointCorrelation(expected, saved, GitHubProviderReplayEvidence.unprovenReason(saved)))
        }
        return Resolved(selected, digest, journalDigest)
    }

    fun binding(scope: String, authorityRevision: String, expected: GitHubProviderCheckpoint?, checkpoint: JsonObject?): String {
        val current = "$scope:$authorityRevision"
        if (expected == null) return current
        if (checkpoint == null || !expected.matches(checkpoint)) throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED", correlation = ProviderRecoveryFailure.checkpointCorrelation(expected, checkpoint))
        val context = checkpoint["context"] as? JsonObject
        val retained = (context?.get("tool_checkpoint_binding") as? JsonPrimitive)?.contentOrNull ?: return current
        if (!(context["task_binding"] == JsonPrimitive(expected.binding) && retained.startsWith("$scope:") && retained.length <= 4096)) {
            val reason = if (context["task_binding"] != JsonPrimitive(expected.binding)) "TASK_BINDING_CHANGED" else "TOOL_BINDING_CHANGED"
            throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED", ProviderRecoveryFailure.interruption(checkpoint), ProviderRecoveryFailure.checkpointCorrelation(expected, checkpoint, reason))
        }
        return retained
    }
}
