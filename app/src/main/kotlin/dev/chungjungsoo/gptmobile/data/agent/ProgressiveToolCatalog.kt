package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Selects authorized schemas per run; GitHub mode keeps every visible GitHub schema attached. */
class ProgressiveToolCatalog(
    definitions: List<AgentToolDefinition>,
    task: String,
    initialNames: Set<String>? = null,
    private val acquireCold: (suspend (String, List<String>) -> List<AgentToolDefinition>)? = null,
    private val acquisitionState: (() -> ToolCatalogAcquisition)? = null,
    val githubMode: Boolean = false
) : AgentTool {
    // Local schema selection is not an external evidence operation. Cold acquisitions
    // retain their own wrapped child admission rather than charging this facade too.
    override val coordinatesChildWork = true
    private var advanced = advancedTask(task)
    private val integrationPreset = task.lineSequence().any { it.trim() == "[github-workflow-preset:test_integration]" }
    private val githubUpfront = integrationPreset ||
        githubMode &&
        (
            dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(task) ||
                dev.chungjungsoo.gptmobile.data.github.GitHubTaskCoverage.matches(task)
            )
    private val conversational = !githubUpfront && CurrentRequestContract.from(task).conversational
    private val githubInitialTools = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task).let { requested ->
        val core = setOf("github_context", "github_read", "github_search")
        val editing = if ("github_write" in requested || "github_stage_patch" in requested) {
            setOf("github_stage_patch", "github_commit", "github_decide")
        } else {
            emptySet()
        }
        requested + core + editing
    }
    private val toolAllowance get() = if (advanced) 16 else INITIAL_TOOLS
    private val schemaAllowance get() = if (advanced) MAX_SCHEMA_TOKEN_ALLOWANCE else SCHEMA_TOKEN_ALLOWANCE
    private val taskText = task
    private val candidates = definitions.filter { it.modelVisible }.associateBy { it.name }.toMutableMap()
    private data class ExecutionBinding(val source: AgentToolDefinition, val executable: AgentToolDefinition)
    private val executionBindings = mutableMapOf<String, ExecutionBinding>()
    private val deferredEngineering = setOf("github_write", "github_commit", "github_stage_patch", "github_release", "github_watch_checks")
    private val taskRequired = definitions.filter { definition ->
        !conversational &&
            (
                definition.name == "read_recovery_context" ||
                    AgentToolCapability.USER_DECISION in definition.capabilities ||
                    (
                        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in
                            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task) &&
                            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) !in deferredEngineering
                        )
                )
    }
    private val requiredCapabilities = if (Regex("(?i)\\b(research|search|sources|papers|studies|latest|news|verify|article|citations)\\b").containsMatchIn(task)) setOf(AgentToolCapability.WEB_SEARCH, AgentToolCapability.PAGE_READER) else emptySet()
    private val omissions = mutableMapOf<String, String>()
    private val active = (
        if (conversational) {
            emptyList()
        } else if (githubUpfront) {
            candidates.values.filter(::isGitHubTool) + window(taskRequired)
        } else if (githubMode) {
            candidates.values.filter { it.name.substringBefore("__") in githubInitialTools } + window(taskRequired)
        } else {
            window(taskRequired + ranked(task).filter { (initialNames == null || it.name in initialNames) && dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) !in deferredEngineering })
        }
        ).map { it.name }.toMutableSet()
    private val discovered = active.toMutableSet()
    private val advertisedContracts = mutableSetOf<String>()

    // A catalog lives for one run: repeated discovery should not refetch the same cold source.
    private val acquiredQueryKeys = mutableMapOf<String, String>()
    private val unknownNameVersions = linkedMapOf<String, String>()
    private val acquisitionMutex = Mutex()
    private data class Observation(val contract: String, val outcome: String, val at: Long, val errorCode: String?, val succeeded: Int, val failed: Int, val unknown: Int)
    private val observations = mutableMapOf<String, Observation>()
    private val observedCalls = linkedSetOf<String>()
    private var listedInventoryVersion: String? = null
    private val listedInventory = mutableSetOf<String>()
    private var lastSelection: String? = null
    private var unchangedSelections = 0
    private var inventoryRestartIssued = false

    @Volatile private var acquisitionCode: String? = if (acquireCold == null) null else "ACQUISITION_PENDING"

    @Volatile private var failedAcquisitionRevision: String? = null

    internal fun acquisitionReadinessCode(): String? {
        if (acquisitionMutex.isLocked) return acquisitionCode
        val state = acquisitionState?.invoke() ?: return acquisitionCode
        return if (failedAcquisitionRevision == state.revision) acquisitionCode else state.readinessCode
    }

    private fun contractFingerprint(tool: AgentToolDefinition): String = fingerprint(tool.contractVersion.toString() + ":" + ToolContractFingerprint.schema(tool.inputSchema))

    @Synchronized
    internal fun recordObservation(name: String, result: AgentToolResult, observedAtEpochMillis: Long = System.currentTimeMillis()) {
        if (!result.dispatched || result.sharedResult) return
        val candidate = candidates[resolveName(name)] ?: return
        val contract = contractFingerprint(candidate)
        if (!observedCalls.add("${candidate.name}:$contract:${result.callId}")) return
        while (observedCalls.size > 8192) observedCalls.remove(observedCalls.first())
        val previous = observations[candidate.name]?.takeIf { it.contract == contract }
        val outcome = when {
            result.errorCode in ToolResultEnvelope.unknownOutcomeCodes -> "unknown"
            result.isError -> "failed"
            else -> "succeeded"
        }
        fun count(old: Int?, matches: Boolean) = ((old ?: 0).toLong() + if (matches) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        observations[candidate.name] = Observation(
            contract,
            outcome,
            observedAtEpochMillis,
            result.errorCode?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) },
            count(previous?.succeeded, outcome == "succeeded"),
            count(previous?.failed, outcome == "failed"),
            count(previous?.unknown, outcome == "unknown")
        )
    }

    private fun coverage(): JsonObject {
        val current = candidates.values.mapNotNull { tool -> observations[tool.name]?.takeIf { it.contract == contractFingerprint(tool) } }
        return buildJsonObject {
            put("schema", "tools.discovery-coverage.v1")
            put("scope", "current_run_authorized_contracts")
            put("authorized_tools", candidates.size)
            put("inventory_entries_listed", listedInventory.size)
            put("schemas_ever_advertised", candidates.values.count { "${it.name}:${contractFingerprint(it)}" in advertisedContracts })
            put("schemas_selected_for_next_round", active.size)
            put("distinct_tools_dispatched", current.size)
            put("dispatched_calls", current.sumOf { it.succeeded.toLong() + it.failed + it.unknown })
            put("successful_calls", current.sumOf { it.succeeded.toLong() })
            put("failed_calls", current.sumOf { it.failed.toLong() })
            put("unknown_outcome_calls", current.sumOf { it.unknown.toLong() })
            put("acceptance_status", "NOT_MEASURED")
            put("interpretation", "Inventory entries and advertised schemas are not executed tests. Only dispatched receipts increment execution counts; acceptance requires its own criterion-linked evidence.")
        }
    }
    override val definition = AgentToolDefinition(
        NAME,
        "List authorized tools with empty arguments, or search by task or exact names. Omit inventory_version on the first page; later pages must use the exact returned continuation_call. Inventory versions are opaque, never invent one. Found tools become available next round. This cannot grant permissions or install tools.",
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
                    put(
                        "inventory_version",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Opaque version from the previous inventory page. Omit for the first page; never use a guessed version.")
                        }
                    )
                }
            )
            put("additionalProperties", false)
        }
    )

    @Synchronized
    fun selected(allowed: List<AgentToolDefinition>): List<AgentToolDefinition> {
        val definitions = allowed.filter { it.modelVisible }.associateBy { it.name }
        // Preserve task/discovery priority when the outer context planner has less room.
        val githubNames = if (githubUpfront) definitions.values.filter(::isGitHubTool).map { it.name } else emptyList()
        return (githubNames + active + NAME).distinct().mapNotNull(definitions::get)
    }

    /** Reflect trusted execution wrappers without adding names or changing discovery selection. */
    @Synchronized
    internal fun bindExecutionContracts(definitions: Collection<AgentToolDefinition>) {
        definitions.filter { it.modelVisible && it.name != NAME }.forEach { definition ->
            val existing = candidates[definition.name] ?: return@forEach
            val source = executionBindings[definition.name]?.source ?: existing
            executionBindings[definition.name] = ExecutionBinding(source, definition)
            candidates[definition.name] = definition
        }
    }

    private fun acquiredDefinition(definition: AgentToolDefinition): AgentToolDefinition {
        val binding = executionBindings[definition.name] ?: return definition
        val source = binding.source
        if (contractFingerprint(source) == contractFingerprint(definition) &&
            source.capabilities == definition.capabilities &&
            source.dependsOn == definition.dependsOn &&
            source.workflowLinks == definition.workflowLinks &&
            source.modelVisible == definition.modelVisible
        ) {
            return binding.executable
        }
        // A new remote contract needs fresh execution binding. Do not conceal a
        // changed schema behind an earlier local recovery wrapper.
        executionBindings.remove(definition.name)
        return definition
    }

    /** The caller must authorize and bind a new tool before registering its exact schema. */
    @Synchronized
    internal fun admitAuthorizedTool(definition: AgentToolDefinition): Boolean {
        if (conversational || !definition.modelVisible || definition.name == NAME) return false
        val existing = candidates[definition.name]
        if (existing != null && existing != definition) return false
        candidates[definition.name] = definition
        val explicitRequired = explicitlyRequiredToolNames(taskText, candidates.keys).mapNotNull { candidates[resolveName(it)] }
        val selected = (
            (if (githubUpfront) candidates.values.filter(::isGitHubTool) else emptyList()) +
                window(explicitRequired + taskRequired + definition + active.mapNotNull(candidates::get))
            ).distinctBy { it.name }
        active.clear()
        active.addAll(selected.map { it.name })
        if (definition.name in active) discovered.add(definition.name)
        // Every later selection still intersects this window with the caller's allowed tools.
        return definition.name in active
    }

    @Synchronized
    internal fun selectionOmissions(allowed: List<AgentToolDefinition>): Map<String, String> {
        val allowedNames = allowed.mapTo(HashSet()) { it.name }
        // `allowed` may already be the context-planner window. Include deferred catalog
        // candidates as well, so the trace never reports an empty omission summary merely
        // because filtering happened in an earlier admission stage.
        return (candidates.values + allowed).distinctBy { it.name }
            .filterNot { it.name == NAME || it.name in active && it.name in allowedNames }
            .associate { tool ->
                tool.name to when {
                    !tool.modelVisible -> "HIDDEN_COMPATIBILITY_SCHEMA"
                    tool.name in active && tool.name !in allowedNames -> "NOT_ADMITTED_TO_ROUND"
                    else -> omissions[tool.name] ?: "DEFERRED_NOT_REQUESTED"
                }
            }
    }

    @Synchronized
    internal fun recordSelection(allowed: List<AgentToolDefinition>, selected: List<AgentToolDefinition>) {
        selected.filter { it.name in candidates }.forEach { advertisedContracts += "${it.name}:${contractFingerprint(it)}" }
        val omitted = selectionOmissions(allowed)
        val identity = fingerprint(selected.joinToString { it.name + contractFingerprint(it) } + omitted.toSortedMap())
        if (identity == lastSelection) {
            unchangedSelections++
            if (unchangedSelections % 32 == 0) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "UNCHANGED_SELECTION · selection=$identity · repeatedRounds=$unchangedSelections · advertised=${selected.size} · omitted=${omitted.size}")
            return
        }
        lastSelection = identity
        unchangedSelections = 0
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ToolCatalog", "SELECTED_SCHEMA · authorized=${(candidates.keys + allowed.map { it.name }).size} · admittedToRound=${allowed.size} · advertised=${selected.size} · names=${selected.joinToString { it.name }} · omitted=${omitted.size} · omissionReasons=${omitted.values.groupingBy { it }.eachCount().toSortedMap()} · estimatedTokens=${selected.sumOf { schemaTokens(it).toLong() }} · selectionTargetTokens=$schemaAllowance · selectionTargetScope=OPTIONAL_SCHEMA_WINDOW · wireContextLimit=HARD · measurement=UTF8_ESTIMATE")
        ToolCatalogDiagnostics.recordOmissions("catalog", omitted, schemaAllowance.toLong(), inventoryNames = (candidates.keys + allowed.map { it.name }).toSet())
    }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12).map(::resolveName)
        if (("inventory_cursor" in arguments || "inventory_version" in arguments) && (query.isNotBlank() || names.isNotEmpty())) {
            return AgentToolResult(callId, ToolResultContent.Text("Use inventory_cursor alone to continue the tool inventory; query/names start a separate discovery."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if (acquireCold != null && "inventory_cursor" !in arguments) {
            // Cache exact unknown names per catalog version. Broad/empty discovery remains retryable.
            // Never suspend while holding the selection monitor.
            val key = query.trim().lowercase(java.util.Locale.ROOT) + "|" + names.sorted().joinToString("|")
            acquisitionMutex.withLock {
                val version = synchronized(this) { fingerprint(candidates.values.sortedBy { it.name }.joinToString { it.name + contractFingerprint(it) }) }
                val acquisitionRevision = acquisitionState?.invoke()?.revision.orEmpty()
                val lookupVersion = "$version:$acquisitionRevision"
                if (acquiredQueryKeys[key] != acquisitionRevision && unknownNameVersions[key] != lookupVersion) {
                    failedAcquisitionRevision = null
                    acquisitionCode = "ACQUISITION_PENDING"
                    val acquired = try {
                        acquireCold.invoke(query, names).filter { it.modelVisible }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        acquisitionCode = "ACQUISITION_TIMED_OUT"
                        failedAcquisitionRevision = acquisitionState?.invoke()?.revision
                        throw timeout
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        acquisitionCode = "ACQUISITION_FAILED"
                        failedAcquisitionRevision = acquisitionState?.invoke()?.revision
                        throw failure
                    }
                    if (acquired.isNotEmpty()) {
                        synchronized(this) {
                            acquired.forEach { candidates[it.name] = acquiredDefinition(it) }
                            if (githubUpfront) active.addAll(acquired.filter(::isGitHubTool).map { it.name })
                        }
                        if (acquiredQueryKeys.size >= 128) acquiredQueryKeys.clear()
                        // Cache the revision whose snapshot we requested. A producer
                        // can finish between returning that snapshot and this write;
                        // stamping its newer revision would hide the late result.
                        acquiredQueryKeys[key] = acquisitionRevision
                    } else if (query.isBlank() && names.isNotEmpty() && names.none { synchronized(this) { it in candidates } }) {
                        unknownNameVersions[key] = lookupVersion
                        while (unknownNameVersions.size > 128) unknownNameVersions.remove(unknownNameVersions.keys.first())
                    }
                    // A short interactive wait can return before the application-owned
                    // producer. Its later result changes the revision and can be picked
                    // up in this same run without repeating unchanged discovery.
                    acquisitionCode = acquisitionState?.invoke()?.readinessCode
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
            val previousActive = active.toSet()
            // Cold inventory must seed an executable window, otherwise an empty call can
            // discover tools forever while every subsequent request advertises only us.
            if (active.isEmpty() && !conversational) {
                active.addAll(
                    window(
                        ranked(taskText).filter {
                            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) !in deferredEngineering
                        }
                    ).map { it.name }
                )
                discovered.addAll(active)
            }
            val inventory = candidates.values.sortedBy { it.name }
            val cursorValue = arguments["inventory_cursor"]
            val cursor = if (cursorValue == null) 0 else (cursorValue as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
            if (cursor == null) return@synchronized AgentToolResult(callId, ToolResultContent.Text("inventory_cursor must be the exact integer returned in continuation_call."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            if (cursor !in 0..inventory.size) return@synchronized AgentToolResult(callId, ToolResultContent.Text("Invalid inventory_cursor; use the exact continuation_call from the preceding inventory page."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            val version = fingerprint(
                JsonArray(
                    inventory.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("semantic_schema_fingerprint", ToolContractFingerprint.schema(tool.inputSchema))
                            put("invocation_contract_version", tool.contractVersion.toString())
                            put("dependencies", JsonArray(tool.dependsOn.sorted().map(::JsonPrimitive)))
                            put("workflow_links", workflowLinks(tool))
                        }
                    }
                ).toString()
            )
            val expectedVersion = (arguments["inventory_version"] as? JsonPrimitive)?.content
            if ((cursor > 0 && expectedVersion == null) || (expectedVersion != null && expectedVersion != version)) {
                val offerRestart = !inventoryRestartIssued
                inventoryRestartIssued = true
                return@synchronized AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "tools.inventory-restart.v1")
                            put("error_code", "STALE_INVENTORY")
                            put("message", if (offerRestart) "The inventory version is missing or stale. Use the exact correction_call once to restart this read." else "Inventory restart was already offered for this run. Stop repeating stale arguments and retain the partial catalog coverage.")
                            put("permissions_changed", false)
                            put("restart_offered", offerRestart)
                            if (offerRestart) {
                                put(
                                    "correction_call",
                                    buildJsonObject {
                                        put("tool", NAME)
                                        put("arguments", JsonObject(emptyMap()))
                                    }
                                )
                            }
                            put("coverage", coverage())
                        }
                    ),
                    true,
                    errorCode = "STALE_INVENTORY",
                    dispatched = false,
                    executionReceipt = buildJsonObject {
                        put("failed_stage", "inventory_pagination")
                        put("catalog_pagination_invalidated", true)
                        put("retryable", false)
                        put("requires_argument_correction", offerRestart)
                        put("permissions_changed", false)
                    }
                )
            }
            // GitHub mode mounts the complete authorized GitHub catalog at admission.
            // Its compact inventory is listed in one bounded batch when possible.
            val items = inventory.drop(cursor).take(if (githubUpfront) 256 else 12)
            val next = cursor + items.size
            if (listedInventoryVersion != version) {
                listedInventoryVersion = version
                listedInventory.clear()
            }
            listedInventory.addAll(items.map { it.name })
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
                                        put("semantic_schema_fingerprint", ToolContractFingerprint.schema(tool.inputSchema))
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
                        put("available_next_round", JsonArray(active.map { name -> buildJsonObject { put("name", name) } }))
                        put("catalog_unchanged", previousActive == active)
                        put("newly_advertised", JsonArray(active.filterNot { it in previousActive }.map(::JsonPrimitive)))
                        put("inventory_version", version)
                        put("contract_version", 1)
                        put("returned", items.size)
                        put("inventory_offset", cursor)
                        put("inventory_remaining_after_page", inventory.size - next)
                        put("inventory_entries_not_yet_listed", inventory.size - listedInventory.size)
                        put("coverage", coverage())
                        acquisitionState?.invoke()?.let { put("connection_discovery", it.receipt()) }
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
                        put("mount_policy", if (githubUpfront) "AUTHORIZED_GITHUB_UPFRONT" else "PROGRESSIVE_WINDOW")
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
        val selected = ((if (githubUpfront) candidates.values.filter(::isGitHubTool) else emptyList()) + window(explicit + pinned + found + taskRequired + retained + active.mapNotNull(candidates::get))).distinctBy { it.name }
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
            put("schema_allowance_scope", "OPTIONAL_SCHEMA_WINDOW")
            put("schema_allowance_is_wire_limit", false)
            put("required_schema_policy", if (githubUpfront) "AUTHORIZED_GITHUB_AND_RECOVERY_SCHEMAS_UPFRONT" else "REQUEST_REQUIRED_SCHEMAS_FIRST")
            put("wire_schema_estimate_tokens", (active.mapNotNull(candidates::get) + definition).sumOf { schemaTokens(it).toLong() })
            put("wire_context_budget_enforced", true)
            put("selection_policy", "Explicitly requested capabilities receive priority; other authorized capabilities remain discoverable. Availability still requires the next request's context admission. Executable permissions are unchanged.")
            val incompleteDiscovery = acquisitionState?.invoke()?.readinessCode != null
            put("availability_note", if (incompleteDiscovery) "Some selected connections are pending or unavailable. A name missing from this snapshot is not proof that its server has no such tool. Ready authorized tools remain available; discovery does not grant permissions." else "Unknown names are not in the authorized catalog. Enable/configure the relevant connection or permission before discovery; schema admission alone cannot grant access.")
            put("unknown_names", JsonArray(names.filterNot(candidates::containsKey).map(::JsonPrimitive)))
            if (names.any { it !in candidates }) put("unknown_name_recovery", if (incompleteDiscovery) "Inspect connection_discovery. Retry after the relevant connection's state changes; do not repeat discovery while the same acquisition or recovery backoff is pending." else "These names are absent from this authorized catalog. Choose exact names from discovery; do not repeat unchanged unknown-name requests. Retry only after the catalog changes.")
            put("authorized_tool_count", candidates.size)
            put("remaining_tool_count", candidates.size - discovered.count { it in candidates })
            put("advertised_tool_count", active.size)
            put("coverage", coverage())
            acquisitionState?.invoke()?.let { put("connection_discovery", it.receipt()) }
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
        for (tool in (listOfNotNull(candidates["read_recovery_context"]) + ordered).distinctBy { it.name }) {
            // Mandatory GitHub schemas are admitted against the actual provider context,
            // never the optional progressive count/token window.
            if (githubUpfront && isGitHubTool(tool)) continue
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
                // A GitHub discovery can be more specific than the original turn. Generic
                // words such as "status" must not pull in Health or unrelated web tools.
                github.isNotEmpty() -> 0
                tool.capabilities.any { it in requiredCapabilities } -> 500
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
        internal fun isGitHubTool(tool: AgentToolDefinition): Boolean = AgentToolCapability.GITHUB in tool.capabilities || tool.name.split("__").any { it == "github" || it.startsWith("github_") || it == "repository_graph" || it == "gitmcp" }
        fun create(definitions: List<AgentToolDefinition>, task: String, initialNames: Set<String>? = null, githubMode: Boolean = false): ProgressiveToolCatalog? =
            if (definitions.any { it.name == NAME } || definitions.none { !it.modelVisible || dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null } && definitions.size <= INITIAL_TOOLS && definitions.sumOf { schemaTokens(it).toLong() } <= SCHEMA_TOKEN_ALLOWANCE) null else ProgressiveToolCatalog(definitions, task, initialNames, githubMode = githubMode)
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
