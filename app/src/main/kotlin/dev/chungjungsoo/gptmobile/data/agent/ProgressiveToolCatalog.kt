package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var advanced = advancedTask(task)
    private val toolAllowance get() = if (advanced) 16 else INITIAL_TOOLS
    private val schemaAllowance get() = if (advanced) MAX_SCHEMA_TOKEN_ALLOWANCE else SCHEMA_TOKEN_ALLOWANCE
    private val taskText = task
    private val candidates = definitions.filter { it.modelVisible }.associateBy { it.name }.toMutableMap()
    private val taskRequired = definitions.filter { definition ->
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task)
    }
    private val requiredCapabilities = if (Regex("(?i)\\b(research|search|sources|papers|studies|latest|news|verify|article|citations)\\b").containsMatchIn(task)) setOf(AgentToolCapability.WEB_SEARCH, AgentToolCapability.PAGE_READER) else emptySet()
    private val omissions = mutableMapOf<String, String>()
    private val active = window(taskRequired + ranked(task).filter { initialNames == null || it.name in initialNames }).map { it.name }.toMutableSet()
    private val discovered = active.toMutableSet()

    // A catalog lives for one run: repeated discovery should not refetch the same cold source.
    private val acquiredQueryKeys = mutableSetOf<String>()
    private val acquisitionMutex = Mutex()
    private data class Observation(val contract: String, val outcome: String, val at: Long, val errorCode: String?, val succeeded: Int, val failed: Int, val unknown: Int)
    private val observations = mutableMapOf<String, Observation>()

    private fun contractFingerprint(tool: AgentToolDefinition): String = fingerprint(tool.contractVersion.toString() + ":" + tool.inputSchema.toString())

    @Synchronized
    internal fun recordObservation(name: String, result: AgentToolResult, observedAtEpochMillis: Long = System.currentTimeMillis()) {
        if (!result.dispatched || result.sharedResult) return
        val candidate = candidates[name] ?: return
        val contract = contractFingerprint(candidate)
        val previous = observations[name]?.takeIf { it.contract == contract }
        val outcome = when {
            result.errorCode in ToolResultEnvelope.unknownOutcomeCodes -> "unknown"
            result.isError -> "failed"
            else -> "succeeded"
        }
        fun count(old: Int?, matches: Boolean) = ((old ?: 0).toLong() + if (matches) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        observations[name] = Observation(
            contract,
            outcome,
            observedAtEpochMillis,
            result.errorCode?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) },
            count(previous?.succeeded, outcome == "succeeded"),
            count(previous?.failed, outcome == "failed"),
            count(previous?.unknown, outcome == "unknown")
        )
    }
    override val definition = AgentToolDefinition(
        NAME,
        "List authorized tools with empty arguments, or search by task or exact names. Call this when the currently advertised tools cannot perform a task. Found tools become available next round. This cannot grant permissions or install tools.",
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
                    put(
                        "inventory_cursor",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", Int.MAX_VALUE)
                        }
                    )
                    put("inventory_version", buildJsonObject { put("type", "string") })
                }
            )
            put("additionalProperties", false)
        }
    )

    @Synchronized
    fun selected(allowed: List<AgentToolDefinition>): List<AgentToolDefinition> {
        val definitions = allowed.filter { it.modelVisible }.associateBy { it.name }
        // Preserve task/discovery priority when the outer context planner has less room.
        return (active + NAME).mapNotNull(definitions::get)
    }

    @Synchronized
    internal fun selectionOmissions(allowed: List<AgentToolDefinition>): Map<String, String> {
        val allowedNames = allowed.mapTo(HashSet()) { it.name }
        return allowed.filterNot { it.name in active || it.name == NAME }.associate { tool ->
            tool.name to if (!tool.modelVisible) "HIDDEN_COMPATIBILITY_SCHEMA" else omissions[tool.name] ?: "DEFERRED_NOT_REQUESTED"
        } + active.filterNot { it in allowedNames }.associateWith { "CONTEXT_OR_AUTHORIZATION_FILTER" }
    }

    internal fun recordSelection(allowed: List<AgentToolDefinition>, selected: List<AgentToolDefinition>) {
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "SELECTED_SCHEMA · authorized=${allowed.size} · advertised=${selected.size} · names=${selected.joinToString { it.name }} · estimatedTokens=${selected.sumOf { schemaTokens(it).toLong() }} · measurement=UTF8_ESTIMATE")
        selectionOmissions(allowed).forEach { (name, reason) ->
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=catalog · name=$name · reason=$reason · schemaAllowance=$schemaAllowance")
        }
    }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12).map(::resolveName)
        if (("inventory_cursor" in arguments || "inventory_version" in arguments) && (query.isNotBlank() || names.isNotEmpty())) {
            return AgentToolResult(callId, ToolResultContent.Text("Use inventory_cursor alone to continue the tool inventory; query/names start a separate discovery."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if (acquireCold != null && "inventory_cursor" !in arguments) {
            // Cache only successful acquisitions. Offline or empty catalogs remain retryable.
            // Never suspend while holding the selection monitor.
            val key = query.trim().lowercase(java.util.Locale.ROOT) + "|" + names.sorted().joinToString("|")
            acquisitionMutex.withLock {
                if (key !in acquiredQueryKeys) {
                    val acquired = acquireCold.invoke(query, names).filter { it.modelVisible }
                    if (acquired.isNotEmpty()) {
                        synchronized(this) { acquired.forEach { candidates[it.name] = it } }
                        if (acquiredQueryKeys.size >= 128) acquiredQueryKeys.clear()
                        acquiredQueryKeys += key
                    }
                }
            }
        }
        return selectResult(callId, arguments)
    }

    private fun workflowLinks(tool: AgentToolDefinition): JsonArray = JsonArray(
        tool.workflowLinks.sortedWith(compareBy({ it.action }, { it.sourceTool }, { it.sourceAction }, { it.purpose })).map { link ->
            buildJsonObject {
                put("action", link.action)
                put("source_tool", link.sourceTool)
                put("source_action", link.sourceAction)
                put("purpose", link.purpose)
                put("source_available", link.sourceTool in candidates)
                put("relation", "optional_evidence")
            }
        }
    )

    private fun selectResult(callId: String, arguments: JsonObject): AgentToolResult = synchronized(this) {
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12).map(::resolveName)
        if (query.isBlank() && names.isEmpty()) {
            val inventory = candidates.values.sortedBy { it.name }
            val cursor = (arguments["inventory_cursor"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            if (cursor !in 0..inventory.size) return@synchronized AgentToolResult(callId, ToolResultContent.Text("Invalid inventory_cursor; use the exact continuation_call from the preceding inventory page."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            val version = fingerprint(
                JsonArray(
                    inventory.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("schema_fingerprint", fingerprint(tool.inputSchema.toString()))
                            put("invocation_contract_version", tool.contractVersion.toString())
                            put("dependencies", JsonArray(tool.dependsOn.sorted().map(::JsonPrimitive)))
                            put("workflow_links", workflowLinks(tool))
                        }
                    }
                ).toString()
            )
            val expectedVersion = (arguments["inventory_version"] as? JsonPrimitive)?.content
            if ((cursor > 0 && expectedVersion == null) || (expectedVersion != null && expectedVersion != version)) {
                return@synchronized AgentToolResult(callId, ToolResultContent.Text("Tool inventory changed or its version is missing; restart inventory at the first page."), true, errorCode = "STALE_INVENTORY", dispatched = false)
            }
            val items = inventory.drop(cursor).take(12)
            val next = cursor + items.size
            return@synchronized AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "authorized_inventory",
                            JsonArray(
                                items.map { tool ->
                                    buildJsonObject {
                                        val schema = fingerprint(tool.inputSchema.toString())
                                        val observation = observations[tool.name]?.takeIf { it.contract == contractFingerprint(tool) }
                                        put("name", tool.name)
                                        put("description", tool.description.take(160))
                                        put("currently_advertised", tool.name in active)
                                        put(
                                            "operational_status",
                                            if (observation == null) {
                                                "not tested"
                                            } else {
                                                "last execution ${observation.outcome}"
                                            }
                                        )
                                        observation?.let {
                                            put("observed_at_epoch_ms", it.at)
                                            val now = System.currentTimeMillis()
                                            put(
                                                "observation_freshness",
                                                if (it.at > now) {
                                                    "clock changed"
                                                } else if (now - it.at > 300_000) {
                                                    "stale"
                                                } else {
                                                    "recent"
                                                }
                                            )
                                            if (it.at <= now) put("observed_age_ms", now - it.at)
                                            put("observed_success_count", it.succeeded)
                                            put("observed_failure_count", it.failed)
                                            put("observed_unknown_count", it.unknown)
                                            it.errorCode?.let { code -> put("observed_error_code", code) }
                                        }
                                        put("schema_fingerprint", schema)
                                        put("invocation_contract_version", tool.contractVersion.toString())
                                        put("invocation_contract_supported", tool.contractVersion.supported)
                                        val dependencies = tool.dependsOn.sorted()
                                        put("dependency_metadata", if (dependencies.isEmpty() && tool.workflowLinks.isEmpty()) "not declared" else "declared")
                                        put("declared_dependencies", JsonArray(dependencies.map(::JsonPrimitive)))
                                        put("unavailable_dependencies", JsonArray(dependencies.filterNot(candidates::containsKey).map(::JsonPrimitive)))
                                        put("workflow_links", workflowLinks(tool))
                                        omissions[tool.name]?.let { put("omission_reason", it) }
                                    }
                                }
                            )
                        )
                        put("authorized_tool_count", inventory.size)
                        put("inventory_version", version)
                        put("contract_version", 1)
                        put("returned", items.size)
                        put("has_more", next < inventory.size)
                        if (next < inventory.size) {
                            put(
                                "continuation_call",
                                buildJsonObject {
                                    put("tool", NAME)
                                    put(
                                        "arguments",
                                        buildJsonObject {
                                            put("inventory_cursor", next)
                                            put("inventory_version", version)
                                        }
                                    )
                                }
                            )
                        }
                        put("permissions_changed", false)
                        put("note", "Observations describe dispatched calls in this run, not a live probe or a guarantee of current health. Workflow links are optional evidence relationships and never grant access. Tools outside this authorized catalog are not executable.")
                    }
                ),
                false,
                dispatched = false
            )
        }
        val exact = candidates[resolveName(query.trim())]
        // Discovery may reveal repository intent after an ordinary first message.
        // This raises only the schema window; runtime context and grants still apply.
        if (advancedTask(query) || names.any { it in candidates && it.contains("github", true) } || exact?.name?.contains("github", true) == true) advanced = true
        val undiscovered = if (query.isNotBlank()) ranked(query) else emptyList()
        val found = (names.mapNotNull(candidates::get) + listOfNotNull(exact) + undiscovered).distinctBy { it.name }.take(12)
        val previousActive = active.toSet()
        val pinned = requiredCapabilities.mapNotNull { capability ->
            (active.mapNotNull(candidates::get) + ranked(taskText)).firstOrNull { capability in it.capabilities }
        }
        val explicit = names.mapNotNull(candidates::get) + listOfNotNull(exact)
        val retained = ranked(taskText).filter { it.name in active }
        val selected = (window(explicit + pinned + found + taskRequired + retained + active.mapNotNull(candidates::get))).distinctBy { it.name }
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
            put("selection_policy", "Explicitly requested capabilities receive priority; other authorized capabilities remain discoverable. Availability still requires the next request's context admission. Executable permissions are unchanged.")
            put("availability_note", "Unknown names are not in the authorized catalog. Enable/configure the relevant connection or permission before discovery; schema admission alone cannot grant access.")
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

    @Synchronized
    private fun resolveName(name: String): String {
        candidates.keys.firstOrNull { it.equals(name, true) }?.let { return it }
        val canonical = when (name.lowercase()) {
            "github_pull_request", "github_pull_request__github_api" -> "github_pr"
            else -> name
        }
        return candidates.keys.singleOrNull { it == canonical || it.startsWith(canonical + "__") } ?: name
    }

    private fun window(ordered: List<AgentToolDefinition>): List<AgentToolDefinition> {
        var tokens = 256L
        val selected = mutableListOf<AgentToolDefinition>()
        omissions.clear()
        for (tool in ordered.distinctBy { it.name }) {
            val cost = schemaTokens(tool)
            val reason = when {
                cost + 256 > schemaAllowance -> "SCHEMA_TOO_LARGE"
                selected.size >= toolAllowance -> "TOOL_COUNT_WINDOW"
                tokens + cost > schemaAllowance -> "SCHEMA_WINDOW_BUDGET"
                else -> null
            }
            if (reason == null) {
                tokens += cost
                selected += tool
            } else {
                omissions[tool.name] = reason
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
                github.isNotEmpty() && "github" in name -> 900
                tool.capabilities.any { it in requiredCapabilities } -> 500
                github.isNotEmpty() && needle == taskText.lowercase(java.util.Locale.ROOT).trim() -> 0
                needle.isNotEmpty() && needle in name -> 100
                else -> words.sumOf { word -> (if (word in name) 8 else 0) + (if (word in description) 1 else 0) }
            }
            tool to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<AgentToolDefinition, Int>> { it.second }.thenBy { it.first.name }).map { it.first }
    }

    companion object {
        private fun fingerprint(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        const val NAME = "discover_available_tools"
        const val INITIAL_TOOLS = 8
        private val STOP_WORDS = setOf("the", "and", "for", "this", "that", "with", "please", "can", "could", "use", "help", "what", "how", "all", "work", "continue")

        // Leaves room for the discovery schema and source content in the parent request planner.
        const val SCHEMA_TOKEN_ALLOWANCE = 2_000
        const val MAX_SCHEMA_TOKEN_ALLOWANCE = 6_000
        private fun advancedTask(task: String): Boolean =
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task).isNotEmpty() || Regex("(?iu)\\b(research|study|studies|papers)\\b").containsMatchIn(task)
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
                catalog.recordSelection(tools, selected)
            },
            exchanges
        )
}
