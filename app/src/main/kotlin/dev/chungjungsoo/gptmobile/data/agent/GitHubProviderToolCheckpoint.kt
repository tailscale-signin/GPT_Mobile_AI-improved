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
        check(checkpoint != null && expected.matches(checkpoint)) { "PROVIDER_WAIT_CHECKPOINT_CHANGED" }
        val context = checkpoint["context"] as? JsonObject
        val retained = (context?.get("tool_checkpoint_binding") as? JsonPrimitive)?.contentOrNull ?: return current
        check(context["task_binding"] == JsonPrimitive(expected.binding) && retained.startsWith("$scope:") && retained.length <= 4096) {
            "PROVIDER_WAIT_CHECKPOINT_CHANGED"
        }
        return retained
    }
}
