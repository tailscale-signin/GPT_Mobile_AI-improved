package dev.chungjungsoo.gptmobile.data.agent

internal data class ToolBatchPlan(
    val executable: List<ProviderEvent.ToolCall>,
    val deferred: List<ProviderEvent.ToolCall>,
    val perToolSuppressedIds: Set<String>,
    val hostSuppressedIds: Set<String>,
    val disabledNames: Set<String>,
    val acceptedByName: Map<String, Int>
)

/** Pure dispatch planning, separated from the suspending execution/UI coordinator. */
internal fun planToolBatch(
    calls: List<ProviderEvent.ToolCall>,
    remaining: Int,
    availableNames: Set<String>,
    usedByName: Map<String, Int>,
    blockedScopes: Set<String>,
    sameToolLimit: Int,
    scope: (ProviderEvent.ToolCall) -> String
): ToolBatchPlan {
    val executable = mutableListOf<ProviderEvent.ToolCall>()
    val deferred = mutableListOf<ProviderEvent.ToolCall>()
    val suppressed = mutableSetOf<String>()
    val hostSuppressed = mutableSetOf<String>()
    val disabled = mutableSetOf<String>()
    val accepted = mutableMapOf<String, Int>()
    calls.forEachIndexed { index, call ->
        val repeatKey = toolRepeatKey(call)
        val sourceRead = repeatKey != call.name
        val used = (usedByName[repeatKey] ?: 0) + (accepted[repeatKey] ?: 0)
        val repeatLimit = if (sourceRead || Regex("(?i)context[ _-]*retrieve|search[ _-]*nodes|read[ _-]*graph|open[ _-]*nodes").containsMatchIn(call.name)) 3 else sameToolLimit
        when {
            index >= remaining -> deferred += call
            call.name !in availableNames -> {
                deferred += call
                suppressed += call.callId
            }
            scope(call) in blockedScopes -> {
                deferred += call
                hostSuppressed += call.callId
            }
            used >= repeatLimit -> {
                deferred += call
                suppressed += call.callId
                if (!sourceRead) disabled += call.name
            }
            else -> {
                executable += call
                accepted[repeatKey] = (accepted[repeatKey] ?: 0) + 1
            }
        }
    }
    return ToolBatchPlan(executable, deferred, suppressed, hostSuppressed, disabled, accepted)
}

/** Reading independent pages is useful research, not a repeated invocation loop. */
internal fun toolRepeatKey(call: ProviderEvent.ToolCall): String {
    val name = call.name.substringAfterLast("__")
    if (name !in setOf("read_url", "firecrawl_scrape", "tavily_extract", "web_fetch_exa", "fetch", "read_doc")) return call.name
    fun canonical(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is kotlinx.serialization.json.JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { kotlinx.serialization.json.JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is kotlinx.serialization.json.JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(call.arguments).toByteArray())
    return call.name + ":" + hash.joinToString("") { "%02x".format(it) }
}
