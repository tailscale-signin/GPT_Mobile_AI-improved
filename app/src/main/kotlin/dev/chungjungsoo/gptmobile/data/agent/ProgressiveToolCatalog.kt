package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shrinks advertised schemas, never the authorized executable set. A new catalog is created per run. */
internal class ProgressiveToolCatalog(definitions: List<AgentToolDefinition>, task: String) : AgentTool {
    private val candidates = definitions.associateBy { it.name }
    private val active = ranked(task).take(INITIAL_TOOLS).map { it.name }.toMutableSet()
    override val definition = AgentToolDefinition(
        NAME,
        "Search other authorized tools by task or exact names. Call this when the currently advertised tools cannot perform a task. Found tools become available next round. This cannot grant permissions or install tools.",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("maxLength", 4096)
                        }
                    )
                    put(
                        "names",
                        buildJsonObject {
                            put("type", "array")
                            put("maxItems", 12)
                            put("items", buildJsonObject { put("type", "string") })
                        }
                    )
                }
            )
            put("additionalProperties", false)
        }
    )

    @Synchronized
    fun selected(allowed: List<AgentToolDefinition>): List<AgentToolDefinition> = allowed.filter { it.name in active || it.name == NAME }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = synchronized(this) {
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12)
        if (query.isBlank() && names.isEmpty()) return@synchronized AgentToolResult(callId, ToolResultContent.Text("Provide a task query or exact tool names."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val found = (names.mapNotNull(candidates::get) + if (query.isNotBlank()) ranked(query) else emptyList()).distinctBy { it.name }.take(12)
        active.addAll(found.map { it.name })
        val result = buildJsonObject {
            put(
                "available_next_round",
                JsonArray(
                    found.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description.take(800))
                        }
                    }
                )
            )
            put("unknown_names", JsonArray(names.filterNot(candidates::containsKey).map(::JsonPrimitive)))
            put("authorized_tool_count", candidates.size)
        }
        AgentToolResult(callId, ToolResultContent.Json(result), false, dispatched = false)
    }

    private fun ranked(query: String): List<AgentToolDefinition> {
        val needle = query.lowercase(java.util.Locale.ROOT).trim()
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase(java.util.Locale.ROOT)).map { it.value }.filter { it.length > 2 }.toSet()
        return candidates.values.sortedByDescending { tool ->
            val name = tool.name.lowercase(java.util.Locale.ROOT)
            val description = tool.description.take(2048).lowercase(java.util.Locale.ROOT)
            (
                if (name == needle) {
                    1000
                } else if (needle.isNotEmpty() && needle in name) {
                    100
                } else {
                    0
                }
                ) + words.sumOf { word -> (if (word in name) 5 else 0) + (if (word in description) 1 else 0) }
        }
    }

    companion object {
        const val NAME = "discover_available_tools"
        const val INITIAL_TOOLS = 24
        fun create(definitions: List<AgentToolDefinition>, task: String): ProgressiveToolCatalog? =
            if (definitions.size <= INITIAL_TOOLS || definitions.any { it.name == NAME }) null else ProgressiveToolCatalog(definitions, task)
    }
}

internal fun AgentProviderSession.withToolCatalog(catalog: ProgressiveToolCatalog): AgentProviderSession = object : AgentProviderSession {
    override val handlesToolsInternally = this@withToolCatalog.handlesToolsInternally
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> =
        this@withToolCatalog.streamRound(catalog.selected(tools), exchanges)
}
