package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shrinks advertised schemas, never the authorized executable set. A new catalog is created per run. */
class ProgressiveToolCatalog(definitions: List<AgentToolDefinition>, task: String, initialNames: Set<String>? = null, private val acquireCold: (suspend (String, List<String>) -> List<AgentToolDefinition>)? = null) : AgentTool {
    // Local schema selection is not an external evidence operation. Cold acquisitions
    // retain their own wrapped child admission rather than charging this facade too.
    override val coordinatesChildWork = true
    private val advanced = Regex("(?iu)\\b(research|study|studies|papers|repository|github)\\b").containsMatchIn(task)
    private val toolAllowance = if (advanced) 16 else INITIAL_TOOLS
    private val schemaAllowance = if (advanced) 6000 else SCHEMA_TOKEN_ALLOWANCE
    private val taskText = task
    private val candidates = definitions.filter { it.modelVisible }.associateBy { it.name }.toMutableMap()
    private val requiredCapabilities = if (Regex("(?i)\\b(research|search|sources|papers|studies|latest|news|verify|article|citations)\\b").containsMatchIn(task)) setOf(AgentToolCapability.WEB_SEARCH, AgentToolCapability.PAGE_READER) else emptySet()
    private val active = window(ranked(task).filter { initialNames == null || it.name in initialNames }).map { it.name }.toMutableSet()
    private val discovered = active.toMutableSet()

    // A catalog lives for one run: repeated discovery should not refetch the same cold source.
    private val acquiredQueryKeys = mutableSetOf<String>()
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
    fun selected(allowed: List<AgentToolDefinition>): List<AgentToolDefinition> = allowed.filter { it.modelVisible && (it.name in active || it.name == NAME) }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12)
        if ((query.isNotBlank() || names.isNotEmpty()) && acquireCold != null) {
            // Cache only successful acquisitions. Offline or empty catalogs remain retryable.
            // Never suspend while holding the selection monitor.
            val key = query.trim().lowercase(java.util.Locale.ROOT) + "|" + names.sorted().joinToString("|")
            val alreadyFetched = synchronized(this) { key in acquiredQueryKeys }
            if (!alreadyFetched) {
                val acquired = acquireCold.invoke(query, names)
                if (acquired.isNotEmpty()) {
                    synchronized(this) {
                        acquired.filter { it.modelVisible }.forEach { candidates[it.name] = it }
                        acquiredQueryKeys += key
                    }
                }
            }
        }
        return selectResult(callId, arguments)
    }

    private fun selectResult(callId: String, arguments: JsonObject): AgentToolResult = synchronized(this) {
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12)
        if (query.isBlank() && names.isEmpty()) return@synchronized AgentToolResult(callId, ToolResultContent.Text("Provide a task query or exact tool names."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        val exact = candidates.values.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }
        val undiscovered = if (query.isNotBlank()) ranked(query) else emptyList()
        val found = (names.mapNotNull(candidates::get) + listOfNotNull(exact) + undiscovered).distinctBy { it.name }.take(12)
        val previousActive = active.toSet()
        val pinned = requiredCapabilities.mapNotNull { capability ->
            (active.mapNotNull(candidates::get) + ranked(taskText)).firstOrNull { capability in it.capabilities }
        }
        val explicit = names.mapNotNull(candidates::get) + listOfNotNull(exact)
        val retained = ranked(taskText).filter { it.name in active }
        val selected = window(pinned + explicit + retained + found + active.mapNotNull(candidates::get))
        active.clear()
        active.addAll(selected.map { it.name })
        val admitted = found.filter { it.name in active }
        discovered.addAll(admitted.map { it.name })
        val result = buildJsonObject {
            put(
                "available_next_round",
                JsonArray(
                    admitted.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description.take(800))
                        }
                    }
                )
            )
            put("schema_budget_excluded", JsonArray(found.filterNot { it.name in active }.map { JsonPrimitive(it.name) }))
            put("schema_token_allowance", schemaAllowance)
            put("selection_policy", "Current-task capabilities remain available; explicit relevant requests may replace lower-priority schemas. Executable permissions are unchanged.")
            put("unknown_names", JsonArray(names.filterNot(candidates::containsKey).map(::JsonPrimitive)))
            put("authorized_tool_count", candidates.size)
            put("remaining_tool_count", candidates.size - discovered.size)
            put("advertised_tool_count", active.size)
            put("catalog_unchanged", previousActive == active)
            put("newly_advertised", JsonArray(active.filterNot { it in previousActive }.map(::JsonPrimitive)))
            if (previousActive == active) put("next_step", "No new schemas became available. Use an advertised tool or produce a partial answer; do not repeat this identical discovery.")
        }
        AgentToolResult(callId, ToolResultContent.Json(result), false, dispatched = false)
    }

    private fun window(ordered: List<AgentToolDefinition>): List<AgentToolDefinition> {
        var tokens = 256L
        val selected = mutableListOf<AgentToolDefinition>()
        for (tool in ordered.distinctBy { it.name }) {
            if (selected.size >= toolAllowance) break
            val cost = schemaTokens(tool)
            if (tokens + cost <= schemaAllowance) {
                tokens += cost
                selected += tool
            }
        }
        return selected
    }

    private fun ranked(query: String): List<AgentToolDefinition> {
        val needle = query.lowercase(java.util.Locale.ROOT).trim()
        val words = Regex("[\\p{L}\\p{N}]+").findAll(needle).map { it.value }.filter { it.length > 2 && it !in STOP_WORDS }.toSet()
        val github = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(query)
        return candidates.values.map { tool ->
            val name = tool.name.lowercase(java.util.Locale.ROOT)
            val description = tool.description.take(1024).lowercase(java.util.Locale.ROOT)
            val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(name)
            val score = when {
                name == needle -> 10000
                operation != null -> if (operation in github) 1000 - github.indexOf(operation) else 0
                github.isNotEmpty() && needle == taskText.lowercase(java.util.Locale.ROOT).trim() -> 0
                tool.capabilities.any { it in requiredCapabilities } -> 500
                needle.isNotEmpty() && needle in name -> 100
                else -> words.sumOf { word -> (if (word in name) 8 else 0) + (if (word in description) 1 else 0) }
            }
            tool to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<AgentToolDefinition, Int>> { it.second }.thenBy { it.first.name }).map { it.first }
    }

    companion object {
        const val NAME = "discover_available_tools"
        const val INITIAL_TOOLS = 8
        private val STOP_WORDS = setOf("the", "and", "for", "this", "that", "with", "please", "can", "could", "use", "help", "what", "how", "all", "work", "continue")

        // Leaves room for the discovery schema and source content in the parent request planner.
        const val SCHEMA_TOKEN_ALLOWANCE = 2_000
        internal fun schemaTokens(tool: AgentToolDefinition): Int = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(tool.name + tool.description + tool.inputSchema.toString())
        fun create(definitions: List<AgentToolDefinition>, task: String, initialNames: Set<String>? = null): ProgressiveToolCatalog? =
            if (definitions.any { it.name == NAME } || definitions.none { !it.modelVisible || dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null } && definitions.size <= INITIAL_TOOLS && definitions.sumOf { schemaTokens(it).toLong() } <= SCHEMA_TOKEN_ALLOWANCE) null else ProgressiveToolCatalog(definitions, task, initialNames)
    }
}

internal fun AgentProviderSession.withToolCatalog(catalog: ProgressiveToolCatalog): AgentProviderSession = object : AgentProviderSession {
    override val handlesToolsInternally = this@withToolCatalog.handlesToolsInternally
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> =
        this@withToolCatalog.streamRound(
            catalog.selected(tools).also { selected ->
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "SELECTED_SCHEMA · authorized=${tools.size} · advertised=${selected.size} · estimatedTokens=${selected.sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() }} · measurement=UTF8_ESTIMATE")
            },
            exchanges
        )
}
