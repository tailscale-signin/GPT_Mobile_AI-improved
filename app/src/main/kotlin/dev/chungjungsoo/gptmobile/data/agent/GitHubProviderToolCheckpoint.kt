package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A provider wait retains the exact receipt journal, independently of its new coroutine guard. */
internal object GitHubProviderToolCheckpoint {
    data class Resolved(val binding: String, val evidenceDigest: String?)

    /** Preserve the original journal and fence its first restoration against the saved provider evidence. */
    suspend fun resolve(directory: File, chatId: Int, scope: String, authorityRevision: String, expected: GitHubProviderCheckpoint?, checkpoint: JsonObject?, persistent: Boolean): Resolved {
        val retained = binding(scope, authorityRevision, expected, checkpoint)
        if (expected == null) return Resolved(retained, null)
        val saved = requireNotNull(checkpoint)
        val context = saved["context"] as? JsonObject
        val digest = (saved["provider_evidence_digest"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (context == null || context["task_binding"] != JsonPrimitive(expected.binding) || digest == null || !digest.matches(Regex("[a-f0-9]{64}"))) {
            throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED", ProviderRecoveryFailure.interruption(saved), ProviderRecoveryFailure.correlation(expected, null, null, null))
        }
        val legacy = if (persistent && context["tool_checkpoint_binding"] == null) {
            ToolBatchCheckpoint.legacyProviderBinding(directory, chatId, scope, expected, saved)
        } else {
            null
        }
        return Resolved(legacy ?: retained, digest)
    }

    fun binding(scope: String, authorityRevision: String, expected: GitHubProviderCheckpoint?, checkpoint: JsonObject?): String {
        val current = "$scope:$authorityRevision"
        if (expected == null) return current
        if (checkpoint == null || !expected.matches(checkpoint)) throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED", correlation = ProviderRecoveryFailure.correlation(expected, null, null, null))
        val context = checkpoint["context"] as? JsonObject
        val retained = (context?.get("tool_checkpoint_binding") as? JsonPrimitive)?.contentOrNull ?: return current
        if (!(context["task_binding"] == JsonPrimitive(expected.binding) && retained.startsWith("$scope:") && retained.length <= 4096)) throw ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED", ProviderRecoveryFailure.interruption(checkpoint), ProviderRecoveryFailure.correlation(expected, null, null, null))
        return retained
    }
}
