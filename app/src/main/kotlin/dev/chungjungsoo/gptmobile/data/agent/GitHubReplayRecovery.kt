package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep executable continuation arguments intact when replaying a large GitHub result. */
internal object GitHubReplayRecovery {
    /** Consume only an exact local read cursor issued by native replay recovery. */
    fun nextSavedRead(exchanges: List<AgentToolExchange>, tools: Collection<AgentToolDefinition>, maxReplayResultTokens: Int = 512): ProviderEvent.ToolCall? {
        val calls = exchanges.flatMap { it.calls }
        val maximumBytes = (maxReplayResultTokens.toLong().coerceAtLeast(0) * 3).coerceAtMost(8192).toInt()
        if (calls.count { it.callId.startsWith("saved-result-consume-") } >= 32) return null
        val recoverySources = exchanges.flatMap { exchange ->
            exchange.results.mapNotNull { result ->
                val call = exchange.calls.firstOrNull { it.callId == result.callId } ?: return@mapNotNull null
                val source = envelope(result.content) ?: return@mapNotNull null
                if (result.isError ||
                    source["schema"] != JsonPrimitive("github.repeat.v1") &&
                    !(source["replay_compacted"] == JsonPrimitive(true) && source["recovery_required"] == JsonPrimitive(true)) &&
                    !call.callId.startsWith("saved-result-consume-")
                ) {
                    return@mapNotNull null
                }
                call to source
            }
        }
        for ((issuer, source) in recoverySources.asReversed()) {
            // A consumed page's recovery handle points back to that same page.
            // Advance its issued continuation directly; never consume every page twice.
            val fields = if (issuer.callId.startsWith("saved-result-consume-")) listOf("continuation_call") else listOf("recovery_call", "continuation_call")
            for (field in fields) {
                val primary = source[field] as? JsonObject ?: continue
                val primaryCursor = ((primary["arguments"] as? JsonObject)?.get("view_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val options = (source[field.removeSuffix("_call") + "_options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val recipes = (options + primary).filter { recipe ->
                    val cursor = ((recipe["arguments"] as? JsonObject)?.get("view_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@filter false
                    val parts = cursor.split(':')
                    val bytes = parts.getOrNull(2)?.toIntOrNull() ?: return@filter false
                    parts.size == 3 && bytes in 1024..maximumBytes && parts.take(2) == primaryCursor.split(':').take(2)
                }.sortedByDescending { recipe -> ((recipe["arguments"] as JsonObject)["view_cursor"] as JsonPrimitive).content.substringAfterLast(':').toInt() }
                for (recipe in recipes) {
                    val name = (recipe["tool"] as? JsonPrimitive)?.content ?: continue
                    val arguments = recipe["arguments"] as? JsonObject ?: continue
                    val cursor = (arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    if (arguments.keys != setOf("view_cursor") || ':' !in cursor || name != issuer.name || GitHubOperationTool.operationName(name) == null) continue
                    val tool = tools.firstOrNull { it.name == name && it.modelVisible } ?: continue
                    if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty() ||
                        calls.any {
                            val prior = (it.arguments["view_cursor"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                            it.name == name && prior?.split(':')?.take(2) == cursor.split(':').take(2)
                        }
                    ) {
                        continue
                    }
                    val key = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("$name:$cursor").take(24)
                    return ProviderEvent.ToolCall("saved-result-consume-$key", name, arguments)
                }
            }
        }
        return null
    }

    fun envelope(content: ToolResultContent): JsonObject? = when (content) {
        is ToolResultContent.Json -> content.value as? JsonObject
        is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
        else -> null
    }?.takeIf { (it["schema"] as? JsonPrimitive)?.content?.startsWith("github.") == true }

    fun compact(content: ToolResultContent, maxChars: Int, consumed: Boolean = false): ToolResultContent? {
        val source = envelope(content) ?: return null
        val controls = source.filterKeys { it in setOf("schema", "operation", "status", "local_page_complete", "continuation_call", "continuation_options", "remote_continuations", "correction_call", "source_recovery_call", "previous_call_id", "metadata", "recovery_required", "agent_instruction", "reason") }
        val result = controls.toMutableMap()
        result["replay_compacted"] = JsonPrimitive(true)
        result["previously_delivered"] = JsonPrimitive(consumed)
        // The omitted page may never have reached the model. Recover that exact
        // page before offering any cursor that skips past its unread records.
        (source["recovery_call"] as? JsonObject)?.takeUnless { consumed }?.let { recovery ->
            result["continuation_call"] = recovery
            result.remove("continuation_options")
            source["recovery_options"]?.let { result["continuation_options"] = it }
            result.remove("remote_continuations")
            result["local_page_complete"] = JsonPrimitive(false)
            result["recovery_required"] = JsonPrimitive(true)
        }
        // Optional larger pages must not crowd out the small, exact recovery call.
        if (JsonObject(result).toString().length > maxChars) result.remove("continuation_options")
        // Never splice the middle of a cursor or a JSON argument object.
        if (JsonObject(result).toString().length > maxChars) {
            return ToolResultContent.Text("GitHub result omitted from replay: its continuation exceeds the available replay budget. Do not invent a cursor or repeat a mutation; inspect retained evidence or request a narrower immutable read.")
        }
        val preview = (source["data"] ?: source["fragments"] ?: source["message"])?.toString().orEmpty()
        val available = ((maxChars - JsonObject(result).toString().length - 32) / 6).coerceAtLeast(0)
        if (available > 0) result["unverified_preview"] = JsonPrimitive(preview.take(available))
        return ToolResultContent.Json(JsonObject(result))
    }

    fun repeated(call: ProviderEvent.ToolCall, exchanges: List<AgentToolExchange>, message: String): AgentToolResult? {
        val operation = GitHubOperationTool.operationName(call.name) ?: return null
        val key = toolRepeatKey(call)
        val previous = exchanges.asReversed().firstNotNullOfOrNull { exchange ->
            val id = exchange.calls.lastOrNull { toolRepeatKey(it) == key }?.callId
            exchange.results.lastOrNull { it.callId == id && (!it.isError || !it.dispatched && it.errorCode in deterministicErrors) }
        } ?: return null
        if (previous.isError) return previous.copy(callId = call.callId, dispatched = false, sharedResult = true, retainedContent = null, traceContent = null)
        val original = envelope(previous.content)
        return AgentToolResult(
            call.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.repeat.v1")
                    put("operation", operation)
                    put("status", "ALREADY_COMPLETED")
                    put("message", message)
                    put("previous_call_id", previous.callId)
                    original?.get("continuation_call")?.let { put("continuation_call", it) }
                    original?.get("remote_continuations")?.let { put("remote_continuations", it) }
                    original?.get("recovery_call")?.let { put("recovery_call", it) }
                    original?.get("recovery_options")?.let { put("recovery_options", it) }
                    original?.get("continuation_options")?.let { put("continuation_options", it) }
                    original?.get("source_recovery_call")?.let { put("source_recovery_call", it) }
                    put("retained_result_preview", previous.content.toString().take(1200))
                    put("recovery", "Use the exact continuation_call above when present. The earlier call already completed; no action was repeated. Use a different unread range for additional source evidence.")
                }
            ),
            isError = false,
            dispatched = false,
            sharedResult = true
        )
    }

    private val deterministicErrors = setOf("INVALID_ARGUMENTS", "SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
}
