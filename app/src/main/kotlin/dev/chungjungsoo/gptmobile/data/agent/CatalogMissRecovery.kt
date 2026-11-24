package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One local discovery per missing capability and catalog revision, within the original turn budget. */
internal object CatalogMissRecovery {
    private const val PREFIX = "catalog-recovery-"
    private val nativeReads = setOf("web_search", "read_url")
    private val readCapabilities = setOf(setOf(AgentToolCapability.WEB_SEARCH), setOf(AgentToolCapability.PAGE_READER))
    private val hash = Regex("[a-f0-9]{64}")

    fun isRecovery(id: String) = id.startsWith(PREFIX)

    fun next(exchanges: List<AgentToolExchange>, definitions: List<AgentToolDefinition>, attempted: Set<String>): ProviderEvent.ToolCall? {
        if (exchanges.none { exchange -> exchange.results.any { it.errorCode == "TOOL_NOT_AVAILABLE" && !it.dispatched } }) return null
        val tools = definitions.filter { it.modelVisible }.associateBy { it.name }
        val catalog = tools[ProgressiveToolCatalog.NAME] ?: return null
        val results = exchanges.flatMap { it.results }.associateBy { it.callId }
        val calls = exchanges.flatMap { it.calls }
        val issued = calls.associateBy { it.callId }
        for (miss in calls) {
            val result = results[miss.callId] ?: continue
            val receipt = result.executionReceipt ?: continue
            if (result.dispatched ||
                !result.isError ||
                result.errorCode != "TOOL_NOT_AVAILABLE" ||
                receipt["call_id"] != JsonPrimitive(miss.callId) ||
                receipt["reason_code"] != JsonPrimitive("UNKNOWN_TOOL") ||
                receipt["schema"] != JsonPrimitive("gptmobile.round-tool-catalog.v1") ||
                receipt["discovery_available"] != JsonPrimitive(true)
            ) {
                continue
            }
            val revision = (receipt["catalog_revision_hash"] as? JsonPrimitive)?.content?.takeIf(hash::matches) ?: continue
            val key = GitHubRepositoryStateStore.digest("$revision|${miss.name}").take(32)
            val lookupId = "${PREFIX}lookup-$key"
            val readId = "${PREFIX}read-$key"
            val suggested = (receipt["suggested_tool"] as? JsonPrimitive)?.content
            val target = suggested?.takeIf { it in nativeReads } ?: miss.name
            val query = buildJsonObject {
                put("query", target.take(160))
                put("catalog_only", true)
            }
            val lookup = issued[lookupId]
            if (lookup == null) {
                if (lookupId !in attempted && ToolArgumentValidator.errors(query, catalog.inputSchema).isEmpty()) {
                    return ProviderEvent.ToolCall(lookupId, catalog.name, query)
                }
                continue
            }
            if (lookup.name != catalog.name || lookup.arguments != query || readId in attempted || readId in issued) continue
            val discovery = results[lookupId]?.takeUnless { it.isError } ?: continue
            val available = ((discovery.content as? ToolResultContent.Json)?.value as? JsonObject)?.get("available_next_round") as? JsonArray ?: continue
            val admitted = tools[target] ?: continue
            if (available.none { (it as? JsonObject)?.get("name") == JsonPrimitive(target) }) continue
            // Only bounded reading tools may reuse the original arguments automatically.
            if (admitted.capabilities !in readCapabilities) continue
            if (ToolArgumentValidator.errors(miss.arguments, admitted.inputSchema).isNotEmpty() || ToolArgumentValidator.errors(miss.arguments, admitted.contractSchema).isNotEmpty()) continue
            if (calls.any { it.name == target && it.arguments == miss.arguments && results[it.callId]?.dispatched == true }) continue
            return ProviderEvent.ToolCall(readId, admitted.name, miss.arguments)
        }
        return null
    }
}
