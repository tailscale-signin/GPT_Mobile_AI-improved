package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A provider wait retains the exact receipt journal, independently of its new coroutine guard. */
internal object GitHubProviderToolCheckpoint {
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
