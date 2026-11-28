package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep app control prose outside an immutable saved-page body and its journal digest. */
internal object SavedPageInstruction {
    fun append(result: AgentToolResult, instruction: String): AgentToolResult? {
        val receipt = result.executionReceipt ?: return null
        if (receipt["saved_view_cursor_issued"] != JsonPrimitive(true) || receipt["saved_view_body_bytes"] == null) return null
        val body = GitHubReplayRecovery.envelope(result.content) ?: return null
        if (body["schema"] != JsonPrimitive("github.operation.v1") || body["replay_compacted"] == JsonPrimitive(true)) return null
        val addition = buildJsonObject {
            put("schema", "github.runner-instruction.v1")
            put("instruction", instruction)
        }
        if (addition in result.assistMetadata) return result
        return result.copy(assistMetadata = result.assistMetadata + addition)
    }
}
