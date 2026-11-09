package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One local native status/reconciliation read per saved approval and explicit continuation. */
internal object GitHubApprovalContinuation {
    fun next(requestKey: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, initialReceiptIds: Set<String>): ProviderEvent.ToolCall? {
        val tool = tools.firstOrNull { it.modelVisible && it.name.substringBefore("__") == "github_context" } ?: return null
        for (pending in GitHubPendingApproval.snapshot(receipts).filterIsInstance<JsonObject>()) {
            if (pending.text("receipt_id") !in initialReceiptIds) continue
            val id = pending.text("engineering_task_id")
            val callId = "approval-status-" + GitHubEvidenceBook.digest("$requestKey:$id").take(24)
            if (receipts.values.any { it.text("call_id") == callId || it.text("call_id").startsWith("$callId-") }) continue
            val args = buildJsonObject {
                put("action", "recovery")
                put("engineering_task_id", id)
            }
            if (ToolArgumentValidator.errors(args, tool.inputSchema).isEmpty()) return ProviderEvent.ToolCall(callId, tool.name, args)
        }
        return null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
