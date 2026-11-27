package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubLookupRoute
import dev.chungjungsoo.gptmobile.data.github.GitHubNextActionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Selects fitting GitHub schemas per round while preserving the authorized discovery inventory. */
class ProgressiveToolCatalog(
    definitions: List<AgentToolDefinition>,
    task: String,
    initialNames: Set<String>? = null,
    private val acquireCold: (suspend (String, List<String>) -> List<AgentToolDefinition>)? = null,
    private val acquisitionState: (() -> ToolCatalogAcquisition)? = null,
    val githubMode: Boolean = false,
    // The selected provider's admitted context, not the user's preferred maximum.
    // Null retains the existing window for callers without route-capacity evidence.
    private val contextCeilingTokens: Int? = null
) : AgentTool {
    // Local schema selection is not an external evidence operation. Cold acquisitions
    // retain their own wrapped child admission rather than charging this facade too.
    override val coordinatesChildWork = true
    private var advanced = advancedTask(task)
    private val integrationPreset = task.lineSequence().any { it.trim() == "[github-workflow-preset:test_integration]" }
    internal val scopedGitHubStatus = githubMode && !integrationPreset && dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(task)
    private val lookup = if (githubMode && !integrationPreset) GitHubLookupRoute.from(task) else null
    private val scopedRead = scopedGitHubStatus || lookup != null
    private val githubUpfront = githubMode && !scopedRead || integrationPreset
    private val conversational = !githubUpfront && CurrentRequestContract.from(task).conversational
    private val toolAllowance get() = if (advanced) 16 else INITIAL_TOOLS
    private val schemaAllowance get() = minOf(
        if (advanced) MAX_SCHEMA_TOKEN_ALLOWANCE else SCHEMA_TOKEN_ALLOWANCE,
        contextSchemaAllowance(contextCeilingTokens)
    )
    private val taskText = task
    private val codeResearch = githubMode && dev.chungjungsoo.gptmobile.data.github.GitHubCodeResearch.matches(task)
    private val taskFeatureSelection = ReviewedFeatureSelection(task)
    private val namedProducts = if (githubMode) IndexedFeatureToolSelection(task) else null
    private val candidates = definitions.filter { it.modelVisible }.associateBy { it.name }.toMutableMap()
    private val actionSelections = mutableMapOf<String, Pair<String, String>>()
    private val requirementActions = mutableMapOf<String, Pair<String, String>>()
    private val explicitSelections = linkedSetOf<String>()
    private var stagedRequiredOperations = emptyList<String>()
    private val completedStagedContracts = mutableMapOf<String, String>()
    private var candidateRevision = 0L
    private var requirementFocusKey: Pair<String, Long>? = null
    private fun presented(tool: AgentToolDefinition): AgentToolDefinition {
        val selected = actionSelections[tool.name] ?: requirementActions[tool.name] ?: return tool
        if (selected.first != contractFingerprint(tool)) return tool
        return GitHubActionSchema.project(tool, selected.second) ?: tool
    }
    private fun presentedSchemas(): Map<String, String> = active.mapNotNull(candidates::get)
        .associate { it.name to ToolContractFingerprint.schema(presented(it).inputSchema) }
    private data class ExecutionBinding(val source: AgentToolDefinition, val executable: AgentToolDefinition)
    private val executionBindings = mutableMapOf<String, ExecutionBinding>()
    private val deferredEngineering = setOf("github_write", "github_commit", "github_stage_patch", "github_release", "github_watch_checks")

    // Explicit engineering tasks need stage/commit schemas without a repeated
    // discovery round; all executable authorization and wire-context gates remain.
    // Larger write/release and check-watch contracts stay on-demand.
    private val upfrontEngineering = if (githubMode && !scopedRead && !integrationPreset && !codeResearch) {
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task)
            .intersect(setOf("github_stage_patch", "github_commit"))
    } else {
        emptySet()
    }
    private fun deferInitially(operation: String?): Boolean =
        operation != null && operation in deferredEngineering && operation != lookup?.operation && operation !in upfrontEngineering

    private fun preferredInitialContracts(definitions: List<AgentToolDefinition>, initialNames: Set<String>?): List<AgentToolDefinition> = definitions.groupBy { definition ->
        val name = definition.name
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(name)
            ?.let { it + name.removePrefix(name.substringBefore("__")) } ?: name
    }.values.map { alternatives ->
        // Preserve exact planned schemas, then prefer a compact initial contract
        // when both facades are eligible. Explicit discovery still selects the exact name.
        val initial = alternatives.filter { it.name in initialNames.orEmpty() }.ifEmpty { alternatives }
        initial.firstOrNull { it.name.substringBefore("__").endsWith("_inspect") } ?: initial.first()
    }

    private val plannedOperations = when {
        lookup != null -> setOf(lookup.operation)
        codeResearch -> RepositoryToolHint.readOperations
        else -> dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task)
    }
    private val issueIntake = githubMode && !integrationPreset && dev.chungjungsoo.gptmobile.data.github.GitHubIssueRemediation.requested(task)
    private val taskRequired = definitions.filter { definition ->
        definition.modelVisible &&
            !conversational &&
            (
                definition.name == "read_recovery_context" ||
                    AgentToolCapability.USER_DECISION in definition.capabilities ||
                    lookup?.matchesTool(definition) == true ||
                    (
                        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in plannedOperations &&
                            !deferInitially(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name))
                        )
                )
    }.let { preferredInitialContracts(it, initialNames) }.also { initial ->
        if (lookup != null) {
            for (tool in initial) {
                if (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool.name) == lookup.operation &&
                    GitHubActionSchema.project(tool, lookup.action) != null
                ) {
                    actionSelections[tool.name] = contractFingerprint(tool) to lookup.action
                }
            }
        }
    }
    private val implementationTools = if (upfrontEngineering.isEmpty()) {
        emptyList()
    } else {
        taskRequired.filter {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) in setOf("github_read", "github_stage_patch", "github_commit", "github_ci", "github_pr")
        }.sortedBy { listOf("github_read", "github_stage_patch", "github_commit", "github_ci", "github_pr").indexOf(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name)) }
    }
    private val requiredCapabilities = if (!codeResearch && Regex("(?i)\\b(research|search|sources|papers|studies|latest|news|verify|article|citations)\\b").containsMatchIn(task)) setOf(AgentToolCapability.WEB_SEARCH, AgentToolCapability.PAGE_READER) else emptySet()
    private val omissions = mutableMapOf<String, String>()
    private val active = (
        if (conversational) {
            emptyList()
        } else if (scopedRead) {
            // Keep the first round scoped to the requested reads and preserve authorized discovery.
            window(taskRequired)
        } else if (githubUpfront) {
            val rankedTask = ranked(task)
            window(
                taskRequired.filter { issueIntake && dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) == "github_issue" } + implementationTools + rankedTask.filter {
                    !codeResearch &&
                        !integrationPreset &&
                        (initialNames == null || it.name in initialNames) &&
                        (namedProducts?.selects(it) == true || taskFeatureSelection.route(it.name) != null) &&
                        !taskFeatureSelection.declined(it) &&
                        namedProducts?.excludes(it) != true
                } + taskRequired + rankedTask.filter {
                    (initialNames == null || it.name in initialNames) &&
                        !deferInitially(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name))
                } + if (!codeResearch && taskRequired.none(::isGitHubTool)) {
                    candidates.values.filter {
                        isGitHubTool(it) &&
                            (initialNames == null || it.name in initialNames) &&
                            !deferInitially(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name))
                    }.let { preferredInitialContracts(it, initialNames) }
                } else {
                    emptyList()
                }
            )
        } else {
            window(taskRequired + ranked(task).filter { (initialNames == null || it.name in initialNames) && !deferInitially(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name)) })
        }
        ).map { it.name }.toMutableSet()
    private val discovered = active.toMutableSet()
    private val advertisedContracts = mutableSetOf<String>()
    private var advertisedSelectorContracts = emptyMap<String, Pair<String, String>>()

    // A catalog lives for one run: repeated discovery should not refetch the same cold source.
    private val acquiredQueryKeys = mutableMapOf<String, String>()
    private val unknownNameVersions = linkedMapOf<String, String>()
    private val acquisitionMutex = Mutex()
    private data class Observation(val contract: String, val outcome: String, val at: Long, val errorCode: String?, val succeeded: Int, val failed: Int, val unknown: Int)
    private val observations = mutableMapOf<String, Observation>()
    private data class ActionContract(val name: String, val contract: String, val action: String)
    private val actionMeasurements = mutableMapOf<ActionContract, GitHubNextActionPolicy.RouteMeasurement>()
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

    private fun contractFingerprint(tool: AgentToolDefinition): String = fingerprint(tool.contractVersion.toString() + ":" + ToolContractFingerprint.definition(tool))
    private fun selectionContractChanged(before: AgentToolDefinition?, after: AgentToolDefinition): Boolean =
        before == null ||
            contractFingerprint(before) != contractFingerprint(after) ||
            before.capabilities != after.capabilities ||
            before.dependsOn != after.dependsOn ||
            before.workflowLinks != after.workflowLinks ||
            before.modelVisible != after.modelVisible

    @Synchronized
    internal fun recordObservation(name: String, result: AgentToolResult, observedAtEpochMillis: Long = System.currentTimeMillis(), arguments: JsonObject? = null) {
        val candidate = candidates[resolveName(name)] ?: return
        val contract = contractFingerprint(candidate)
        if (name in stagedRequiredOperations && githubSchemaPhaseComplete(name, result, arguments)) completedStagedContracts[name] = contract
        if (!result.dispatched || result.sharedResult) return
        if (!observedCalls.add("${candidate.name}:$contract:${result.callId}")) return
        while (observedCalls.size > 8192) observedCalls.remove(observedCalls.first())
        val previous = observations[candidate.name]?.takeIf { it.contract == contract }
        val outcome = when {
            result.errorCode in ToolResultEnvelope.unknownOutcomeCodes -> "unknown"
            result.isError -> "failed"
            else -> "succeeded"
        }
        fun count(old: Int?, matches: Boolean) = ((old ?: 0).toLong() + if (matches) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val action = (arguments?.get("action") as? JsonPrimitive)?.content
        if (action != null && action.length <= 64 && GitHubActionSchema.project(candidate, action) != null) {
            val key = ActionContract(candidate.name, contract, action)
            val old = actionMeasurements[key]
            val sample = result.measurement?.durationMs?.takeIf { outcome == "succeeded" && it in 0..3_600_000L }
            val previousLatency = old?.latencyMillis
            val latency = if (sample != null && previousLatency != null) (previousLatency * 3 + sample) / 4 else sample ?: previousLatency
            actionMeasurements[key] = GitHubNextActionPolicy.RouteMeasurement(count(old?.samples, true), count(old?.succeeded, outcome == "succeeded"), latency)
        }
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
        "Discover tools with {} or query/names. For GitHub use names:[tool]+action. Copy continuation_call." + if (githubMode) " repository_hint selects repository reads." else "",
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
                    if (githubMode) put("repository_hint", RepositoryToolHint.schema)
                    put(
                        "names",
                        buildJsonObject {
                            put("type", "array")
                            put("maxItems", 12)
                            put("items", buildJsonObject { put("type", "string") })
                        }
                    )
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                        }
                    )
                    put(
                        "catalog_only",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "No new connections.")
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
                            put("description", "From continuation_call.")
                        }
                    )
                }
            )
            // Discovery by query needs no selector. Action projection needs one named tool.
            put(
                "anyOf",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            // The global action:string constraint makes this
                            // branch valid only when action is absent.
                            put("properties", buildJsonObject { put("action", buildJsonObject { put("type", "null") }) })
                        },
                        buildJsonObject {
                            put("required", JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("names"))))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "names",
                                        buildJsonObject {
                                            put("minItems", 1)
                                            put("maxItems", 1)
                                        }
                                    )
                                }
                            )
                        }
                    )
                )
            )
            put("additionalProperties", false)
        }
    )

    @Synchronized
    internal fun restoreCompletedPhase(name: String) {
        if (name in stagedRequiredOperations) candidates[name]?.let { completedStagedContracts[name] = contractFingerprint(it) }
    }

    @Synchronized
    internal fun stageRequiredOperations(names: List<String>) {
        stagedRequiredOperations = names
    }

    /** Preserve a requirement's selected action across pagination and corrective reads. */
    @Synchronized
    internal fun focusedOperation(allowed: List<AgentToolDefinition>): String? = allowed.firstOrNull { tool ->
        tool.name in explicitSelections || tool.name in requirementActions
    }?.name

    @Synchronized
    fun selected(allowed: List<AgentToolDefinition>): List<AgentToolDefinition> {
        val definitions = allowed.filter { it.modelVisible }.associateBy { it.name }
        // A completed first phase releases its slot for the next requested operation.
        // Intersection with allowed is repeated after every suspension or revocation.
        val next = stagedRequiredOperations.firstOrNull { name ->
            val candidate = candidates[name] ?: return@firstOrNull false
            name in definitions && completedStagedContracts[name] != contractFingerprint(candidate)
        }
        val ordered = if (next == null) active + NAME else listOf(next) + active.filterNot { it in stagedRequiredOperations } + NAME
        return ordered.distinct().mapNotNull(definitions::get).map(::presented)
    }

    /** Select only from registered contracts. Discovery of a missing family has no side effects here. */
    @Synchronized
    internal fun nextAction(checkpoint: JsonObject): GitHubNextActionPolicy.Decision {
        val families = candidates.values.groupBy { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) ?: it.name.substringBefore("__") }
        return GitHubNextActionPolicy.decide(checkpoint, measured = { operation, action ->
            // Mixing two backend aliases would attribute observations to a route that may not be selected.
            val tool = families[operation]?.singleOrNull()
            tool?.let { actionMeasurements[ActionContract(it.name, contractFingerprint(it), action)] }
        }) { operation, action ->
            families[operation].orEmpty().any { tool ->
                action == null ||
                    if (operation == "repository_graph") {
                        val properties = tool.inputSchema["properties"] as? JsonObject
                        val actionSchema = properties?.get("action") as? JsonObject
                        (actionSchema?.get("enum") as? JsonArray)?.contains(JsonPrimitive(action)) == true
                    } else {
                        GitHubActionSchema.project(tool, action) != null
                    }
            }
        }
    }

    /** Reorder existing authorized contracts; discovery and execution admission remain independent. */
    @Synchronized
    internal fun focusRequirement(checkpoint: JsonObject?, mode: GitHubNextActionMode = GitHubNextActionMode.ACTIVE): Boolean {
        if (conversational || scopedRead || checkpoint == null) return false
        val focus = if (mode == GitHubNextActionMode.ACTIVE) nextAction(checkpoint).focus else GitHubRequirementToolFocus.next(checkpoint)
        if (focus == null) return false
        val key = focus.identity to candidateRevision
        if (requirementFocusKey == key) return false
        val previous = presentedSchemas()
        val previousActive = active.toList()
        requirementActions.clear()
        val focused = focus.operations.flatMap { operation ->
            candidates.values.filter { (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) ?: it.name.substringBefore("__")) == operation }
                .let { preferredInitialContracts(it, null) }
        }
        for (tool in focused) {
            if (tool.name in explicitSelections || tool.name in actionSelections) continue
            val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool.name) ?: continue
            val action = focus.actions[operation] ?: continue
            if (GitHubActionSchema.project(tool, action) != null) requirementActions[tool.name] = contractFingerprint(tool) to action
        }
        val explicit = (explicitlyRequiredToolNames(taskText, candidates.keys) + explicitSelections).mapNotNull { candidates[resolveName(it)] }
        val recovery = taskRequired.filter { it.name == "read_recovery_context" || AgentToolCapability.USER_DECISION in it.capabilities }
        // The focused action's declared evidence sources can fit in the same schema window.
        // Resolve exact, already-authorized aliases; prefetch never calls or acquires a tool.
        val sources = focused.flatMap { tool ->
            val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool.name)
            val action = focus.actions[operation] ?: return@flatMap emptyList()
            tool.workflowLinks.filter { it.action == action && it.sourceTool != tool.name }.mapNotNull { candidates[it.sourceTool] }
        }
        val ordered = explicit + recovery + focused + sources + ranked(focus.query) + taskRequired + active.mapNotNull(candidates::get)
        val expanded = mutableListOf<AgentToolDefinition>()
        val included = mutableSetOf<String>()
        fun include(tool: AgentToolDefinition) {
            if (!included.add(tool.name)) return
            tool.dependsOn.mapNotNull(candidates::get).forEach(::include)
            expanded += tool
        }
        ordered.forEach(::include)
        val selected = window(expanded)
        // A missing or oversized focused schema does not erase a working window.
        if (selected.isNotEmpty()) {
            active.clear()
            active.addAll(selected.map { it.name })
            discovered.addAll(active)
        }
        requirementFocusKey = key
        return previousActive != active.toList() || previous != presentedSchemas()
    }

    /** Reflect trusted execution wrappers without adding names or changing discovery selection. */
    @Synchronized
    internal fun bindExecutionContracts(definitions: Collection<AgentToolDefinition>) {
        definitions.filter { it.modelVisible && it.name != NAME }.forEach { definition ->
            val existing = candidates[definition.name] ?: return@forEach
            val source = executionBindings[definition.name]?.source ?: existing
            executionBindings[definition.name] = ExecutionBinding(source, definition)
            if (selectionContractChanged(existing, definition)) candidateRevision++
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
        if (existing == null) candidateRevision++
        candidates[definition.name] = definition
        val explicitRequired = explicitlyRequiredToolNames(taskText, candidates.keys).mapNotNull { candidates[resolveName(it)] }
        val selected = window(explicitRequired + definition + implementationTools + taskRequired + active.mapNotNull(candidates::get))
            .distinctBy { it.name }
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
        advertisedSelectorContracts = selected.filter { it.modelVisible }.mapNotNull { selectedTool ->
            candidates[selectedTool.name]?.takeIf { it.modelVisible && selectedTool == presented(it) }
                ?.let { it.name to (contractFingerprint(it) to contractFingerprint(selectedTool)) }
        }.toMap()
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

    /** Normalize bounded local discovery aliases, unused nulls and exact currently advertised names. */
    @Synchronized
    internal fun canonicalArguments(arguments: JsonObject): JsonObject {
        if (arguments.keys.any { it !in setOf("query", "names", "action", "repository_hint", "catalog_only") }) return arguments
        val normalized = arguments.filterNot { (key, value) -> key in setOf("query", "names") && value == JsonNull }.toMutableMap()
        // A query-only search describes local discovery, not a named tool action.
        // Keep the query unchanged and never turn an explicit acquisition choice into a local one.
        val discoveryQuery = (normalized["query"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
        if (normalized["action"] == JsonPrimitive("search") &&
            "names" !in normalized &&
            arguments.keys.all { it in setOf("query", "names", "action", "catalog_only") } &&
            discoveryQuery != null &&
            normalized["catalog_only"] in setOf(null, JsonPrimitive(true))
        ) {
            normalized.remove("action")
            normalized["catalog_only"] = JsonPrimitive(true)
        }
        // Providers sometimes put an inventory selector in the action slot.
        // A catalog action with one tool can be a real backend action; preserve it.
        // Multi-name catalog discovery stays local and keeps every requested name.
        val names = normalized["names"] as? JsonArray
        if (normalized["action"] == JsonPrimitive("read") && names?.size == 1) {
            val name = (names.single() as? JsonPrimitive)?.takeIf { it.isString }?.content
            val candidate = name?.let { candidates[resolveName(it)] }
            if (candidate?.modelVisible == true &&
                dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(candidate.name) == "github_read" &&
                GitHubActionSchema.project(candidate, "ranges") != null
            ) {
                normalized["action"] = JsonPrimitive("ranges")
            }
        }
        val inventoryAction = normalized["action"] == JsonPrimitive("catalog_only") ||
            normalized["action"] == JsonPrimitive("catalog") &&
            names != null &&
            names.size != 1
        if (inventoryAction &&
            names != null &&
            normalized["catalog_only"] in setOf(null, JsonPrimitive(true))
        ) {
            normalized.remove("action")
            if (names.isEmpty()) normalized.remove("names")
            normalized["catalog_only"] = JsonPrimitive(true)
        }
        val name = (normalized["names"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name != null) {
            val candidate = candidates[name]?.takeIf { it.modelVisible && name.length in 1..160 } ?: return arguments
            if (advertisedSelectorContracts[name] != (contractFingerprint(candidate) to contractFingerprint(presented(candidate)))) return arguments
            normalized["names"] = JsonArray(listOf(JsonPrimitive(name)))
        }
        val corrected = JsonObject(normalized)
        return corrected.takeIf { ToolArgumentValidator.errors(it, definition.inputSchema).isEmpty() } ?: arguments
    }

    /** Split an invalid local action selection only when every exact tool supports that action. */
    @Synchronized
    internal fun singleActionSelectors(arguments: JsonObject): List<JsonObject> {
        if (arguments.keys.any { it !in setOf("action", "names", "query", "catalog_only") } ||
            arguments["catalog_only"] !in setOf(null, JsonPrimitive(true))
        ) {
            return emptyList()
        }
        val action = (arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
        val names = arguments["names"] as? JsonArray ?: return emptyList()
        if (names.size !in 2..12 || names.distinct().size != names.size) return emptyList()
        return names.map { name ->
            val literal = (name as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
            val candidate = candidates[literal] ?: return emptyList()
            if (GitHubActionSchema.project(candidate, action) == null) return emptyList()
            val selector = JsonObject(arguments + ("names" to JsonArray(listOf(name))) + ("catalog_only" to JsonPrimitive(true)))
            if (ToolArgumentValidator.errors(selector, definition.inputSchema).isNotEmpty()) return emptyList()
            selector
        }
    }

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val arguments = canonicalArguments(arguments)
        val invalid = ToolArgumentValidator.errors(arguments, definition.inputSchema)
        if (invalid.isNotEmpty()) return ToolArgumentValidator.rejection(callId, definition.name, arguments, definition.inputSchema, invalid, catalog = this)
        val query = (arguments["query"] as? JsonPrimitive)?.content.orEmpty().take(4096)
        val repositoryHint = RepositoryToolHint.parse(arguments)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12).map(::resolveName)
        if ("repository_hint" in arguments && (!githubMode || repositoryHint == null)) {
            return AgentToolResult(callId, ToolResultContent.Text("Repository hints require a selected GitHub chat and at least one identifier. Use feature_ids, symbols or paths, each with at most 12 unique nonblank strings of at most 256 characters."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if (("inventory_cursor" in arguments || "inventory_version" in arguments) && (query.isNotBlank() || names.isNotEmpty() || "action" in arguments || repositoryHint != null)) {
            return AgentToolResult(callId, ToolResultContent.Text("Use inventory_cursor alone to continue the tool inventory; query/names start a separate discovery."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if ("action" in arguments && names.size != 1) {
            return AgentToolResult(callId, ToolResultContent.Text("Search with query alone. To select an action, pass exactly one returned tool in names together with action."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
        }
        if (acquireCold != null && repositoryHint == null && "inventory_cursor" !in arguments && arguments["catalog_only"] != JsonPrimitive(true)) {
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
                            acquired.forEach {
                                val definition = acquiredDefinition(it)
                                if (selectionContractChanged(candidates[it.name], definition)) candidateRevision++
                                candidates[it.name] = definition
                            }
                            if (githubUpfront && !codeResearch) active.addAll(acquired.filter(::isGitHubTool).map { it.name })
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

    private fun invalidInventoryCursor(callId: String) = AgentToolResult(
        callId,
        ToolResultContent.Text("Invalid inventory_cursor; use the exact continuation_call from the preceding inventory page."),
        true,
        errorCode = "INVALID_ARGUMENTS",
        dispatched = false
    )

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
        val repositoryHint = RepositoryToolHint.parse(arguments)
        val names = (arguments["names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.take(12).map(::resolveName)
        // An action switch can change the advertised contract without adding a tool name.
        // Capture the old wire schemas before changing or restoring that selection.
        val previousActive = active.toSet()
        val previousSchemas = presentedSchemas()
        if ("action" in arguments) {
            val action = (arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tool = names.singleOrNull()?.let(candidates::get)
            if (tool == null || action == null || GitHubActionSchema.project(tool, action) == null) {
                val properties = tool?.inputSchema?.get("properties") as? JsonObject
                val actions = (properties?.get("action") as? JsonObject)?.get("enum") as? JsonArray
                val correction = buildJsonObject {
                    put("tool", NAME)
                    put(
                        "arguments",
                        buildJsonObject {
                            if (tool != null) put("names", JsonArray(listOf(JsonPrimitive(tool.name))))
                            put("catalog_only", true)
                        }
                    )
                }
                val content = buildJsonObject {
                    put("message", "Select an action declared by the named tool. Use the local catalog to inspect its contract.")
                    actions?.let { put("available_actions", it) }
                    put("correction_call", correction)
                }
                return@synchronized AgentToolResult(callId, ToolResultContent.Json(content), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            }
            actionSelections[tool.name] = contractFingerprint(tool) to action
        } else {
            names.forEach(actionSelections::remove)
        }
        names.forEach(requirementActions::remove)
        if (query.isBlank() && names.isEmpty() && repositoryHint == null) {
            // Cold inventory must seed an executable window, otherwise an empty call can
            // discover tools forever while every subsequent request advertises only us.
            if (active.isEmpty() && !conversational) {
                active.addAll(
                    window(
                        ranked(taskText).filter {
                            !deferInitially(dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name))
                        }
                    ).map { it.name }
                )
                discovered.addAll(active)
            }
            val inventory = candidates.values.sortedBy { it.name }
            val cursorValue = arguments["inventory_cursor"]
            val cursor = if (cursorValue == null) 0 else (cursorValue as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
            if (cursor == null) return@synchronized AgentToolResult(callId, ToolResultContent.Text("inventory_cursor must be the exact integer returned in continuation_call."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
            if (cursor < 0) return@synchronized invalidInventoryCursor(callId)
            val version = fingerprint(
                JsonArray(
                    inventory.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("semantic_schema_fingerprint", ToolContractFingerprint.definition(tool))
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
                        put("reason_code", if (expectedVersion == null) "INVENTORY_VERSION_MISSING" else "INVENTORY_VERSION_CHANGED")
                        put("retryable", false)
                        put("requires_argument_correction", offerRestart)
                        put("permissions_changed", false)
                    },
                    inventoryPaginationEvidence = ToolInventoryPaginationEvidence.capture(
                        callId,
                        arguments,
                        if (expectedVersion == null) ToolInventoryPaginationEvidence.Reason.MISSING_VERSION else ToolInventoryPaginationEvidence.Reason.VERSION_MISMATCH,
                        offerRestart
                    )
                )
            }
            if (cursor > inventory.size) return@synchronized invalidInventoryCursor(callId)
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
                                        put("semantic_schema_fingerprint", ToolContractFingerprint.definition(tool))
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
                        put("catalog_unchanged", previousActive == active && previousSchemas == presentedSchemas())
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
                        put(
                            "mount_policy",
                            if (githubUpfront) {
                                "AUTHORIZED_GITHUB_ON_DEMAND"
                            } else if (lookup != null) {
                                "SCOPED_GITHUB_LOOKUP"
                            } else if (scopedGitHubStatus) {
                                "SCOPED_GITHUB_STATUS"
                            } else {
                                "PROGRESSIVE_WINDOW"
                            }
                        )
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
        val undiscovered = if (query.isNotBlank() || repositoryHint != null) ranked(query, repositoryHint) else emptyList()
        val selectedFeatures = ReviewedFeatureSelection(query.ifBlank { taskText }, taskText)
        val selectedProducts = if (githubMode) IndexedFeatureToolSelection(query.ifBlank { taskText }, taskText) else null
        val found = (names.mapNotNull(candidates::get) + listOfNotNull(exact) + undiscovered).distinctBy { it.name }.take(12)
        val pinned = requiredCapabilities.mapNotNull { capability ->
            (active.mapNotNull(candidates::get) + ranked(taskText)).firstOrNull { capability in it.capabilities && !selectedFeatures.declined(it) && selectedProducts?.excludes(it) != true }
        }
        val explicit = names.mapNotNull(candidates::get) + listOfNotNull(exact)
        explicitSelections.addAll(explicit.map { it.name })
        val retained = ranked(taskText).filter { it.name in active }
        val eligible = (explicit + implementationTools + pinned + found + taskRequired + retained + active.mapNotNull(candidates::get))
            .filterNot { selectedProducts?.excludes(it) == true }
        val selected = window(eligible).distinctBy { it.name }
        active.clear()
        active.addAll(selected.map { it.name })
        explicitSelections.retainAll(active)
        val admitted = found.filter { it.name in active }
        discovered.addAll(admitted.map { it.name })
        val unchanged = previousActive == active && previousSchemas == presentedSchemas()
        val result = buildJsonObject {
            if (repositoryHint != null) {
                val contextArguments = buildJsonObject {
                    put("action", "task_context_bundle")
                    put("query", repositoryHint.terms.take(2000))
                }
                // A matching name is only a discovery hint. The current admitted
                // schema must support the exact call before it can be proposed.
                admitted.firstOrNull {
                    (it.name == "repository_graph" || it.name.startsWith("repository_graph__")) &&
                        ToolArgumentValidator.errors(contextArguments, presented(it).inputSchema).isEmpty()
                }?.let { graph ->
                    put(
                        "repository_context_call",
                        buildJsonObject {
                            put("name", graph.name)
                            put("arguments", contextArguments)
                        }
                    )
                }
            }
            put(
                "available_next_round",
                JsonArray(
                    admitted.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", presented(tool).description.take(800))
                            selectedFeatures.route(tool.name)?.let { feature ->
                                put("feature_id", feature.id)
                                put("expected_evidence", feature.proof)
                            }
                        }
                    }
                )
            )
            put("schema_budget_excluded", JsonArray(found.filterNot { it.name in active }.map { JsonPrimitive(it.name) }))
            put("schema_token_allowance", schemaAllowance)
            put("schema_allowance_scope", "OPTIONAL_SCHEMA_WINDOW")
            put("schema_allowance_is_wire_limit", false)
            put(
                "required_schema_policy",
                if (githubUpfront && upfrontEngineering.isNotEmpty()) {
                    "GITHUB_ENGINEERING_SCHEMAS_UPFRONT"
                } else if (githubUpfront) {
                    "GITHUB_OPERATION_SCHEMAS_ON_DEMAND"
                } else if (lookup != null) {
                    "SCOPED_GITHUB_LOOKUP_WITH_AUTHORIZED_DISCOVERY"
                } else if (scopedGitHubStatus) {
                    "SCOPED_GITHUB_STATUS_WITH_AUTHORIZED_DISCOVERY"
                } else {
                    "REQUEST_REQUIRED_SCHEMAS_FIRST"
                }
            )
            put("wire_schema_estimate_tokens", (active.mapNotNull(candidates::get) + definition).sumOf { schemaTokens(presented(it)).toLong() })
            put("selected_actions", JsonObject(active.mapNotNull { name -> actionSelections[name]?.takeIf { it.first == candidates[name]?.let(::contractFingerprint) }?.let { name to JsonPrimitive(it.second) } }.toMap()))
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
            put("catalog_unchanged", unchanged)
            put("newly_advertised", JsonArray(active.filterNot { it in previousActive }.map(::JsonPrimitive)))
            if (unchanged) put("next_step", "No new schemas became available. Use an advertised tool or produce a partial answer; do not repeat this identical discovery.")
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
        val admittedFamilies = mutableSetOf<Pair<String, String>>()
        for (tool in (listOfNotNull(candidates["read_recovery_context"]) + ordered).distinctBy { it.name }) {
            val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool.name) ?: tool.name
            val family = operation to tool.name.substringAfter("__", "")
            if (family in admittedFamilies) continue
            // An individual authorized GitHub schema may exceed the soft discovery
            // window. Hard provider context admission still precedes all dispatch.
            val cost = schemaTokens(presented(tool))
            val singleGitHubContract =
                contextCeilingTokens == null &&
                    (githubMode || integrationPreset) &&
                    isGitHubTool(tool) &&
                    selected.all { it.name == "read_recovery_context" }
            val reason = when {
                cost + 256 > schemaAllowance && !singleGitHubContract -> "SCHEMA_TOO_LARGE"
                selected.size >= toolAllowance -> "TOOL_COUNT_WINDOW"
                tokens + cost > schemaAllowance && !singleGitHubContract -> "SCHEMA_WINDOW_BUDGET"
                else -> null
            }
            if (reason == null) {
                tokens += cost
                selected += tool
                admittedFamilies += family
            } else {
                omissions[tool.name] = reason
            }
        }
        return selected
    }

    private fun ranked(query: String, repositoryHint: RepositoryToolHint? = null): List<AgentToolDefinition> {
        val needle = query.lowercase(java.util.Locale.ROOT).trim()
        val words = Regex("[\\p{L}\\p{N}]+").findAll(needle).map { it.value }.filter { it.length > 2 && it !in STOP_WORDS }.toSet()
        val github = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(query) + if (githubMode && repositoryHint != null) RepositoryToolHint.readOperations else emptySet()
        val features = if (query == taskText) taskFeatureSelection else ReviewedFeatureSelection(query, taskText)
        val products = when {
            !githubMode || scopedRead || integrationPreset -> null
            query == taskText -> namedProducts
            GitHubLookupRoute.from(query) != null || dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(query) -> null
            else -> IndexedFeatureToolSelection(query, taskText)
        }
        val sourceOnly = githubMode && (codeResearch || repositoryHint != null || dev.chungjungsoo.gptmobile.data.github.GitHubCodeResearch.matches(query))
        return candidates.values.map { tool ->
            val name = tool.name.lowercase(java.util.Locale.ROOT)
            val description = tool.description.take(1024).lowercase(java.util.Locale.ROOT)
            val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(name)
            val sourceFamily = if (sourceOnly && AgentToolCapability.GITHUB in tool.capabilities && dev.chungjungsoo.gptmobile.data.github.GitHubMcpRepositoryTool.isReadOnlyOperation(name.substringAfterLast("__"))) {
                dev.chungjungsoo.gptmobile.data.agent.tool.GitHubProviderPolicy.equivalent(name)
            } else {
                operation
            }
            val score = when {
                features.declined(tool) || products?.excludes(tool) == true -> 0
                githubMode && repositoryHint != null && (name == "repository_graph" || name.startsWith("repository_graph__")) -> 1100
                sourceOnly && sourceFamily in RepositoryToolHint.readOperations -> 1050
                sourceOnly -> 0
                name == needle -> 10000
                products?.selects(tool) == true -> 1200 + words.count { it in name }
                operation != null -> if (operation in github) 1000 - github.indexOf(operation) else 0
                github.isNotEmpty() && "github" in name -> 900
                // A GitHub discovery can be more specific than the original turn. Generic
                // words such as "status" must not pull in Health or unrelated web tools.
                features.route(name) != null -> 650 + words.count { it in name }
                github.isNotEmpty() || products?.hasNamedProduct == true -> 0
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

        // Do not spend an unknown/small provider's entire budget advertising tools.
        // The remaining capacity funds system instructions, prompt, completion and receipts.
        private fun contextSchemaAllowance(ceiling: Int?): Int =
            ceiling?.takeIf { it > 0 }?.let { (it / 3).coerceAtLeast(128) } ?: Int.MAX_VALUE
        private fun advancedTask(task: String): Boolean =
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task).isNotEmpty() || Regex("(?iu)\\b(research|study|studies|papers)\\b").containsMatchIn(task)
        internal fun schemaTokens(tool: AgentToolDefinition): Int = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(tool.name + tool.description + tool.inputSchema.toString())
        internal fun isGitHubTool(tool: AgentToolDefinition): Boolean = AgentToolCapability.GITHUB in tool.capabilities || tool.name.split("__").any { it == "github" || it.startsWith("github_") || it in setOf("repository_graph", "repository_workspace", "gitmcp") }
        fun create(
            definitions: List<AgentToolDefinition>,
            task: String,
            initialNames: Set<String>? = null,
            githubMode: Boolean = false,
            contextCeilingTokens: Int? = null
        ): ProgressiveToolCatalog? =
            if (
                definitions.any { it.name == NAME } ||
                definitions.none { !it.modelVisible || dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null || githubMode && AgentToolCapability.GITHUB in it.capabilities } &&
                definitions.size <= INITIAL_TOOLS &&
                definitions.sumOf { schemaTokens(it).toLong() } <= minOf(SCHEMA_TOKEN_ALLOWANCE, contextSchemaAllowance(contextCeilingTokens))
            ) {
                null
            } else {
                ProgressiveToolCatalog(definitions, task, initialNames, githubMode = githubMode, contextCeilingTokens = contextCeilingTokens)
            }
    }
}

/** Carry only this run's local catalog through trusted provenance and budget wrappers. */
internal interface ProgressiveCatalogCarrier {
    val progressiveCatalog: ProgressiveToolCatalog?
}

internal fun AgentTool.argumentCatalog(): ProgressiveToolCatalog? = this as? ProgressiveToolCatalog
    ?: (this as? ProgressiveCatalogCarrier)?.progressiveCatalog

internal fun AgentProviderSession.withToolCatalog(catalog: ProgressiveToolCatalog): AgentProviderSession = object : AgentProviderSession {
    override val handlesToolsInternally = this@withToolCatalog.handlesToolsInternally
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
        val selected = catalog.selected(tools)
        catalog.recordSelection(tools, selected)
        return this@withToolCatalog.streamRound(selected, exchanges).map { event ->
            if (event is ProviderEvent.RequestConfigured) event.copy(advertisedToolCount = selected.count { it.modelVisible }) else event
        }
    }
}
