package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Resume a stopped local batch only from a fresh status for the same authorized revision. */
internal object PhoneIndexContinuation {
    private val scopePattern = Regex("[a-f0-9]{64}")
    private val cursorPattern = Regex("phone-index:[a-f0-9]{32}:[0-9]{1,10}")

    fun next(exchanges: List<AgentToolExchange>, tools: List<AgentToolDefinition>): ProviderEvent.ToolCall? {
        val results = exchanges.flatMap { it.results }.associateBy { it.callId }
        val calls = exchanges.flatMap { it.calls }
        val stopped = calls.lastOrNull { call ->
            val result = results[call.callId] ?: return@lastOrNull false
            val receipt = result.executionReceipt ?: return@lastOrNull false
            call.name.substringBefore("__") == "repository_graph" &&
                call.arguments["action"] == JsonPrimitive("index") &&
                call.arguments["map_source"] in setOf(null, JsonPrimitive("phone")) &&
                result.errorCode in setOf(PhoneIndexInterruption.CODE, "READ_DEADLINE") &&
                receipt["local_work_stopped"] == JsonPrimitive(true) &&
                receipt["requires_reconciliation"] == JsonPrimitive(false) &&
                receipt["map_source"] == JsonPrimitive("phone") &&
                receipt["call_id"] == JsonPrimitive(call.callId)
        } ?: return null
        val receipt = results.getValue(stopped.callId).executionReceipt ?: return null
        val scope = (receipt["scope_hash"] as? JsonPrimitive)?.content?.takeIf(scopePattern::matches) ?: return null
        val revision = receipt["source_head_sha"] ?: return null
        val tool = tools.singleOrNull { it.name == stopped.name && it.modelVisible } ?: return null
        val id = GitHubRepositoryStateStore.digest("$scope|${stopped.callId}").take(24)
        // A failed resume needs a new user decision. Never create a recovery chain or poll the same status again.
        if (stopped.callId.startsWith("phone-index-resume-")) return null
        val statusId = "phone-index-status-$id"
        val statusCall = calls.firstOrNull { it.callId == statusId }
        val args = buildJsonObject {
            put("action", if (statusCall == null) "status" else "index")
            put("map_source", "phone")
            if (statusCall != null) {
                val status = results[statusId]?.takeUnless { it.isError } ?: return null
                val observed = status.executionReceipt ?: return null
                if (observed["schema"] != JsonPrimitive(RepositoryGraphTool.RECEIPT_SCHEMA) ||
                    observed["call_id"] != JsonPrimitive(statusId) ||
                    observed["action"] != JsonPrimitive("status") ||
                    observed["map_source"] != JsonPrimitive("phone") ||
                    observed["phase"] != JsonPrimitive("COMPLETED") ||
                    observed["scope_hash"] != JsonPrimitive(scope) ||
                    observed["source_head_sha"] != revision
                ) {
                    return null
                }
                // A phone map returns its graph directly. Its invocation receipt, above,
                // carries the schema and the authorized account/revision binding.
                val body = when (val content = status.content) {
                    is ToolResultContent.Json -> content.value as? JsonObject
                    is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
                    is ToolResultContent.ResourceLinks -> null
                } ?: return null
                val progress = body["graph"] as? JsonObject ?: return null
                val cursor = (body["index_cursor"] as? JsonPrimitive)?.content?.takeIf(cursorPattern::matches) ?: return null
                if (body["map_source"] != JsonPrimitive("phone") ||
                    progress["source"] != JsonPrimitive("phone") ||
                    progress["commit_sha"] != revision ||
                    body["has_more"] != JsonPrimitive(true) ||
                    (stopped.arguments["options"] as? JsonObject)?.get("index_cursor") == JsonPrimitive(cursor)
                ) {
                    return null
                }
                put("options", buildJsonObject { put("index_cursor", cursor) })
            }
        }
        val nextId = if (statusCall == null) statusId else "phone-index-resume-$id"
        if (calls.any { it.callId == nextId } || ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty()) return null
        return ProviderEvent.ToolCall(nextId, tool.name, args)
    }
}
