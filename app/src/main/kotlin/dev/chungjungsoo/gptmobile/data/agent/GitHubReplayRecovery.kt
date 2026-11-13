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
    /** Keep the recovery allowance and consumed pages outside the evictable provider replay. */
    class State(calls: Collection<ProviderEvent.ToolCall> = emptyList()) {
        private val automaticCalls = mutableSetOf<String>()
        private val readPages = mutableSetOf<String>()
        private val remoteReads = mutableSetOf<String>()

        init {
            record(calls)
        }

        fun record(calls: Collection<ProviderEvent.ToolCall>) {
            calls.forEach { call ->
                if (call.callId.startsWith("saved-result-consume-") || call.callId.startsWith("remote-result-consume-")) automaticCalls += call.callId
                remoteReads += remoteKey(call.name, call.arguments)
                (call.arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cursor ->
                    readPages += pageKey(call.name, cursor)
                }
            }
        }

        fun exhausted() = automaticCalls.size >= 32
        fun hasRead(name: String, cursor: String) = pageKey(name, cursor) in readPages
        fun hasRemoteRead(name: String, args: JsonObject) = remoteKey(name, args) in remoteReads

        private fun pageKey(name: String, cursor: String) = "$name:${cursor.split(':').take(2).joinToString(":")}"
        private fun remoteKey(name: String, args: JsonObject) = remoteReadKey(name, args)
    }

    /** Advance only a native-issued, same-scope workflow page. No write or invented cursor is admitted. */
    fun nextRemoteRead(exchanges: List<AgentToolExchange>, tools: Collection<AgentToolDefinition>, state: State? = null): ProviderEvent.ToolCall? {
        if (state?.exhausted() == true) return null
        val calls = exchanges.flatMap { it.calls }
        if (calls.count { it.callId.startsWith("remote-result-consume-") } >= 32) return null
        fun canonical(name: String, args: JsonObject) = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(name, args)
        fun scope(args: JsonObject): JsonObject {
            val normalized = (args - "page").toMutableMap()
            normalized.putIfAbsent("per_page", JsonPrimitive(30))
            for (key in listOf("owner", "repo")) {
                (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { normalized[key] = JsonPrimitive(it.content.lowercase(java.util.Locale.ROOT)) }
            }
            return JsonObject(normalized)
        }
        for (exchange in exchanges.asReversed()) {
            for (result in exchange.results.asReversed()) {
                if (result.isError) continue
                val issuer = exchange.calls.firstOrNull { it.callId == result.callId } ?: continue
                val original = canonical(issuer.name, issuer.arguments)
                if (GitHubOperationTool.operationName(issuer.name) != "github_ci" || original["action"] != JsonPrimitive("workflows")) continue
                val source = envelope(result.content)
                val recipes = listOfNotNull(source?.get("continuation_call") as? JsonObject) +
                    listOf(source?.get("remote_continuations"), result.executionReceipt?.get("retained_remote_continuations"))
                        .flatMap { (it as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>().mapNotNull { it["call"] as? JsonObject }
                val bound = workflowScope(original, result.executionReceipt)
                for (recipe in recipes) {
                    val name = (recipe["tool"] as? JsonPrimitive)?.content ?: continue
                    val args = (recipe["arguments"] as? JsonObject)?.let { canonical(name, it) } ?: continue
                    val page = (args["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                    val priorPage = (original["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                    if (name != issuer.name || page != priorPage + 1 || scope(args) != scope(bound) || "view_cursor" in args) continue
                    val tool = tools.firstOrNull { it.name == name && it.modelVisible } ?: continue
                    if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty() ||
                        state?.hasRemoteRead(name, args) == true ||
                        calls.any { it.name == name && scope(canonical(name, it.arguments)) == scope(args) && it.arguments["page"] == args["page"] }
                    ) {
                        continue
                    }
                    val key = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(remoteReadKey(name, args)).take(24)
                    return ProviderEvent.ToolCall("remote-result-consume-$key", name, args)
                }
            }
        }
        return null
    }

    private fun workflowScope(original: JsonObject, receipt: JsonObject?): JsonObject {
        if (receipt?.get("schema") != JsonPrimitive("github.dispatch.v1")) return original
        val resolved = receipt["resolved_arguments"] as? JsonObject ?: return original
        val resource = receipt["resource_context"] as? JsonObject ?: return original
        if (resolved["action"] != JsonPrimitive("list_workflows")) return original
        val defaults = buildMap {
            for (key in listOf("owner", "repo", "ref")) {
                if (key in original) continue
                val selected = resource[if (key == "ref") "selected_ref" else key] as? JsonPrimitive ?: continue
                val actual = resolved[key] ?: selected.takeIf { key == "ref" }
                val value = (actual as? JsonPrimitive)?.takeIf { it.isString } ?: continue
                val matches = if (key == "ref") value.content == selected.content else value.content.equals(selected.content, ignoreCase = true)
                if (matches) put(key, value)
            }
        }
        return JsonObject(original + defaults)
    }

    private fun remoteReadKey(name: String, args: JsonObject): String {
        val canonical = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(name, args)
        val normalized = if (GitHubOperationTool.operationName(name) == "github_ci" && canonical["action"] == JsonPrimitive("workflows")) {
            canonical + mapOf("page" to (canonical["page"] ?: JsonPrimitive(1)), "per_page" to (canonical["per_page"] ?: JsonPrimitive(30)))
        } else {
            canonical
        }
        return name + normalized.toSortedMap().toString()
    }

    /** Consume only an exact local read cursor issued by native replay recovery. */
    fun nextSavedRead(exchanges: List<AgentToolExchange>, tools: Collection<AgentToolDefinition>, maxReplayResultTokens: Int = 512, state: State? = null): ProviderEvent.ToolCall? {
        val calls = exchanges.flatMap { it.calls }
        val maximumBytes = (maxReplayResultTokens.toLong().coerceAtLeast(0) * 3).coerceAtMost(8192).toInt()
        if (state?.exhausted() == true || calls.count { it.callId.startsWith("saved-result-consume-") } >= 32) return null
        val recoverySources = exchanges.flatMap { exchange ->
            exchange.results.mapNotNull { result ->
                val call = exchange.calls.firstOrNull { it.callId == result.callId } ?: return@mapNotNull null
                // The evidence observer has saved the complete source before this
                // replay is built. Display fragments are not additional evidence.
                if (result.executionReceipt?.get("retained_original_complete") == JsonPrimitive(true)) return@mapNotNull null
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
                        state?.hasRead(name, cursor) == true ||
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
        val controls = source.filterKeys { it in setOf("schema", "operation", "status", "local_page_complete", "continuation_call", "continuation_options", "remote_continuations", "correction_call", "source_recovery_call", "previous_call_id", "metadata", "recovery_required", "agent_instruction", "reason", "semantic_summary", "retained_original_complete", "source_result_sha256", "receipt_reference") }
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
        if (JsonObject(result).toString().length > maxChars && consumed && source["semantic_summary"] is JsonObject) {
            val summary = buildJsonObject {
                put("schema", "github.evidence-summary.v1")
                source["operation"]?.let { put("operation", it) }
                put("semantic_summary", requireNotNull(source["semantic_summary"]))
                source["receipt_reference"]?.let { put("receipt_reference", it) }
                put("previously_delivered", true)
            }
            if (summary.toString().length <= maxChars) return ToolResultContent.Json(summary)
        }
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
        if (previous.isError) return currentGitHubInvocationReceipt(previous.copy(callId = call.callId, dispatched = false, sharedResult = true, retainedContent = null, traceContent = null))
        val original = envelope(previous.content)
        return currentGitHubInvocationReceipt(
            AgentToolResult(
                call.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.repeat.v1")
                        put("operation", operation)
                        put("status", "OBSERVATION_REUSED")
                        put("message", message)
                        put("previous_call_id", previous.callId)
                        (previous.executionReceipt?.get("semantic_summary") ?: original?.get("semantic_summary"))?.let { put("semantic_summary", it) }
                        previous.executionReceipt?.get("retained_original_complete")?.let { put("retained_original_complete", it) }
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
                sharedResult = true,
                executionReceipt = previous.executionReceipt
            )
        )
    }

    private val deterministicErrors = setOf("INVALID_ARGUMENTS", "SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
}
