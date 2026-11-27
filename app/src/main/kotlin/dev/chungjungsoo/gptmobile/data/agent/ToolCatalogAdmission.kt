package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ContextPlan
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.github.GitHubLookupRoute
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal class GitHubToolContextBudgetException(
    val requiredSchemaTokens: Long,
    val availableSchemaTokens: Long,
    private val budget: GitHubSchemaBudget? = null,
    private val admissionEvidence: JsonObject? = null
) : IllegalArgumentException(
    githubSchemaBudgetMessage(requiredSchemaTokens, availableSchemaTokens, budget, admissionEvidence)
) {
    val admissionCapacityTokens: Int? get() = budget?.capacity

    fun admissionMessage(generationDispatched: Boolean) = githubSchemaBudgetMessage(requiredSchemaTokens, availableSchemaTokens, budget, admissionEvidence, generationDispatched)

    fun withAdmissionEvidence(evidence: JsonObject): GitHubToolContextBudgetException =
        GitHubToolContextBudgetException(requiredSchemaTokens, availableSchemaTokens, budget, JsonObject(admissionEvidence.orEmpty() + evidence)).also { it.initCause(this) }

    fun withRequiredSchemas(tools: List<AgentToolDefinition>, githubMode: Boolean): GitHubToolContextBudgetException = withAdmissionEvidence(
        buildJsonObject {
            put("required_schema_count", tools.size)
            put("required_catalog_schema_tokens", tools.filter { it.name == ProgressiveToolCatalog.NAME }.sumOf { providerSchemaTokens(it, githubMode) })
            put("required_recovery_schema_tokens", tools.filter { it.name == "read_recovery_context" }.sumOf { providerSchemaTokens(it, githubMode) })
            put("required_github_operation_tokens", tools.filter { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null }.sumOf { providerSchemaTokens(it, githubMode) })
        }
    )

    fun providerFailure() = ProviderEvent.Failed(
        message.orEmpty(),
        errorCode = "GITHUB_TOOL_CONTEXT_BUDGET",
        failureDetails = buildJsonObject {
            admissionEvidence?.forEach { (key, value) -> put(key, value) }
            put("failed_stage", "provider_admission")
            put("diagnostic_area", "PROVIDER")
            put("provider_failure_code", "GITHUB_TOOL_CONTEXT_BUDGET")
            put("provider_failure_stage", "ADMISSION")
            put("failure_origin", "APPLICATION")
            put("dispatched", false)
            put("retryable", false)
            put("required_schema_tokens", requiredSchemaTokens)
            put("available_schema_tokens", availableSchemaTokens)
            budget?.let { evidence ->
                put("admission_capacity_tokens", evidence.capacity)
                put("admission_prompt_tokens", evidence.input)
                put("admission_output_tokens", evidence.output)
                put("admission_safety_tokens", evidence.reservedSafety)
                evidence.toolResults?.let { put("admission_tool_result_reserve_tokens", it) }
                put("admission_measurement", "UTF8_ESTIMATE")
                evidence.promptCosts?.let { costs ->
                    for ((key, value) in listOf(
                        "admission_system_tokens" to costs.system,
                        "admission_current_request_tokens" to costs.currentMessage,
                        "admission_history_tokens" to costs.history,
                        "admission_attachment_reserve_tokens" to costs.attachments
                    )) {
                        value.takeIf { it in 0..16_000_000 }?.let { put(key, it) }
                    }
                }
            }
        }
    )
}

/** Explain only the consumed admission snapshot; settings changes cannot rewrite this failure. */
private fun githubSchemaBudgetMessage(requiredSchemaTokens: Long, availableSchemaTokens: Long, measuredBudget: GitHubSchemaBudget?, evidence: JsonObject?, generationDispatched: Boolean = false): String = buildString {
    append("GITHUB_TOOL_CONTEXT_BUDGET: Required GitHub, recovery and discovery schemas need approximately $requiredSchemaTokens tokens, but $availableSchemaTokens remain.")
    val budget = measuredBudget?.takeIf {
        it.capacity in 1..16_000_000 &&
            it.input in 0L..16_000_000L &&
            it.output in 0..16_000_000 &&
            it.reservedSafety in 0..16_000_000 &&
            (it.toolResults == null || it.toolResults in 0..16_000_000)
    }
    fun observedCount(key: String): Int? = (evidence?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..16_000_000 }
    val reductions = mutableListOf<String>()
    if (budget != null) {
        val provenance = (evidence?.get("admission_capacity_provenance") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val route = observedCount("admission_route_context_tokens")
        if (provenance == "VERIFIED_ROUTE" && route == budget.capacity) {
            append(" The selected route was verified for $route context tokens.")
            observedCount("admission_configured_context_tokens")?.takeIf { it >= budget.capacity }?.let { configured ->
                append(" The app's configured allowance is $configured tokens; increasing it does not enlarge that route.")
            }
        } else {
            append(" This attempt used a context allowance of ${budget.capacity} tokens.")
        }
        append(" It reserved ${budget.input} for input, ${budget.output} for the reply and ${budget.reservedSafety} for safety")
        budget.toolResults?.let { append(", plus $it for tool results") }
        append(" (token estimates).")
        if (requiredSchemaTokens in 0L..16_000_000L && availableSchemaTokens in 0L..16_000_000L && requiredSchemaTokens > availableSchemaTokens) {
            val baseOverrun = if (budget.available == availableSchemaTokens) {
                budget.baseOverrun
            } else {
                0
            }
            val shortfall = requiredSchemaTokens - availableSchemaTokens + baseOverrun
            append(" The required schemas are short by $shortfall tokens.")
            if (budget.available == availableSchemaTokens && shortfall > budget.output) {
                append(" Reducing the reply allowance alone cannot cover this shortfall.")
            }
        }
        budget.promptCosts?.takeIf { costs ->
            costs.toolSchemas == 0 && listOf(costs.system, costs.currentMessage, costs.history, costs.attachments).all { it in 0..16_000_000 } && costs.total.toLong() == budget.input
        }?.let { costs ->
            if (costs.system > 0) reductions += "instructions"
            if (costs.currentMessage > 0) reductions += "request input"
            if (costs.history > 0) reductions += "included history"
            if (costs.attachments > 0) reductions += "attachments"
        }
    }
    append(" Review this profile's supported context in settings")
    if (reductions.isNotEmpty()) append(", or explicitly reduce ${reductions.joinToString(", ")}")
    if (generationDispatched) {
        append(". Earlier generation was dispatched. Preserve its output and check tool outcomes before continuing.")
    } else {
        append(", then start a new request. This provider request was not dispatched.")
    }
}

internal fun providerSchemaTokens(tool: AgentToolDefinition, githubMode: Boolean): Long = ProgressiveToolCatalog.schemaTokens(tool).toLong() + if (githubMode) 16 else 0

/** Only advance after a delivered success whose native response offers no further page for this operation. */
internal fun githubSchemaPhaseComplete(name: String, result: AgentToolResult, arguments: JsonObject?, restoredJournal: Boolean = false): Boolean {
    if (result.isError) return false
    if (!result.dispatched || result.sharedResult && !restoredJournal) {
        val call = ProviderEvent.ToolCall(result.callId, name, arguments ?: return false)
        if (GitHubReplayRecovery.checkpointPage(call, result) == null) return false
    }
    val page = GitHubReplayRecovery.envelope(result.content)
    fun sameTool(call: JsonObject?) = call?.get("tool") == JsonPrimitive(name) || call?.get("name") == JsonPrimitive(name)
    if (sameTool(page?.get("continuation_call") as? JsonObject)) return false
    return listOf(page?.get("remote_continuations"), result.executionReceipt?.get("retained_remote_continuations")).none { recipes ->
        (recipes as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { sameTool(it["call"] as? JsonObject) }
    }
}

/** Recheck warm as well as cold discoveries against the current request's remaining room. */
internal fun fitProviderToolSchemas(definitions: List<AgentToolDefinition>, limits: AgentRunLimits, initialSchemaTokens: Long, replayTokens: Long, maxSchemaTokens: Int? = null, discoveryPaused: Boolean = false, completedOperations: List<String> = emptyList(), focusedOperation: String? = null, owedOperation: String? = null): List<AgentToolDefinition> {
    val visible = definitions.filter { it.modelVisible }
    if (limits.contextTokens == Int.MAX_VALUE && maxSchemaTokens == null) return visible
    val base = (limits.initialContextTokens.toLong() - initialSchemaTokens).coerceAtLeast(0)
    val budget = GitHubSchemaBudget(limits.contextTokens, base + replayTokens, limits.finalResponseReserveTokens, minOf(256, limits.contextTokens / 16), wireSafety = true)
    var remaining = minOf(budget.available, maxSchemaTokens?.toLong() ?: Long.MAX_VALUE)
    // The native status plan needs every admitted status/health read even when
    // replay grows. Other engineering operations remain staged on demand.
    val lookup = limits.githubTaskRequest?.let(GitHubLookupRoute::from)
    val scopedOperations = lookup?.let { setOf(it.operation) } ?: limits.githubTaskRequest?.takeIf {
        dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(it) &&
            it.lineSequence().none { line -> line.trim() == "[github-workflow-preset:test_integration]" }
    }?.let(dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest::requiredOperations).orEmpty()
    val explicitlyRequired = if (maxSchemaTokens != null) {
        explicitlyRequiredToolNames(limits.githubTaskRequest.orEmpty(), visible.mapTo(hashSetOf()) { it.name })
    } else {
        emptySet()
    }
    val required = visible.filter { tool ->
        val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool.name)
        val requiredForGitHub = (limits.githubMode || limits.githubTask) &&
            (tool.name == "read_recovery_context" || tool.name == owedOperation || operation in scopedOperations || lookup?.matchesTool(tool) == true)
        val requested = tool.name in explicitlyRequired || operation in explicitlyRequired
        val requiredForRetry = maxSchemaTokens != null && (requested || AgentToolCapability.USER_DECISION in tool.capabilities)
        requiredForGitHub || requiredForRetry
    }
    val requiredSchemaTokens = required.sumOf { providerSchemaTokens(it, limits.githubMode) }
    if (requiredSchemaTokens > remaining) throw GitHubToolContextBudgetException(requiredSchemaTokens, remaining, budget).withRequiredSchemas(required, limits.githubMode)
    remaining -= requiredSchemaTokens
    val requiredNames = required.mapTo(hashSetOf()) { it.name }
    // Preserve a path to the rest of the authorized catalog when a large optional
    // schema consumes the remaining round budget. The returned order stays stable.
    val discovery = visible.firstOrNull { it.name == ProgressiveToolCatalog.NAME }
    val discoveryCost = discovery?.takeUnless { it.name in requiredNames }?.let { providerSchemaTokens(it, limits.githubMode) } ?: 0L
    val discoveryFits = discovery != null && discoveryCost <= remaining
    // A local sequence can page the already resolved native contracts even when the
    // wire window has no directory. It never acquires tools or increases a reserve.
    if ((limits.githubMode || limits.githubTask) && limits.contextTokens <= 4096 && discovery == null && maxSchemaTokens == null && scopedOperations.isEmpty()) {
        val optional = visible.filter { it.name !in requiredNames && ProgressiveToolCatalog.isGitHubTool(it) }
        val nativeOnly = optional.all { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null }
        if (nativeOnly && optional.size > 1 && optional.sumOf { providerSchemaTokens(it, limits.githubMode) } > remaining) {
            // Keep the planner's order for unvisited contracts. After all have run,
            // prioritize the least recently completed one for follow-up evidence.
            val next = optional.firstOrNull { it.name == focusedOperation } ?: optional.minBy { completedOperations.indexOf(it.name) }
            val nextCost = providerSchemaTokens(next, limits.githubMode)
            if (nextCost > remaining) {
                throw GitHubToolContextBudgetException(nextCost + requiredSchemaTokens, budget.available, budget).withRequiredSchemas(required + next, limits.githubMode)
            }
            remaining -= nextCost
            val selected = visible.filter { tool ->
                when {
                    tool.name in requiredNames || tool.name == next.name -> true
                    tool === discovery || ProgressiveToolCatalog.isGitHubTool(tool) -> false
                    else -> {
                        val cost = providerSchemaTokens(tool, limits.githubMode)
                        (cost <= remaining).also { fits -> if (fits) remaining -= cost }
                    }
                }
            }
            ToolCatalogDiagnostics.recordOmissions("provider_round", optional.filterNot { it.name == next.name }.associate { it.name to "DEFERRED_GITHUB_OPERATION" })
            return selected
        }
    }
    // After unchanged discovery spend the next round on an operation if one fits on its own.
    val nextOperation = if (discoveryPaused && discovery?.name !in requiredNames) {
        required.firstOrNull { it.name != ProgressiveToolCatalog.NAME && ProgressiveToolCatalog.isGitHubTool(it) }
            ?: visible.firstOrNull { it.name != ProgressiveToolCatalog.NAME && ProgressiveToolCatalog.isGitHubTool(it) && providerSchemaTokens(it, limits.githubMode) <= remaining }
    } else {
        null
    }
    if (nextOperation != null && requiredNames.add(nextOperation.name)) remaining -= providerSchemaTokens(nextOperation, limits.githubMode)
    if (discoveryFits && nextOperation == null) remaining -= discoveryCost
    val omitted = linkedMapOf<String, String>()
    val selected = visible.filter { tool ->
        if (tool.name in requiredNames) return@filter true
        if (tool === discovery && nextOperation != null) return@filter false
        if (tool === discovery && discoveryFits) return@filter true
        val cost = providerSchemaTokens(tool, limits.githubMode)
        (cost <= remaining).also { fits ->
            if (fits) {
                remaining -= cost
            } else {
                omitted[tool.name] = "REMAINING_CONTEXT_BUDGET"
            }
        }
    }
    ToolCatalogDiagnostics.recordOmissions("provider_round", omitted)
    val lookupAdmitted = lookup != null && required.any(lookup::matchesTool)
    if ((limits.githubMode || limits.githubTask) && !discoveryFits && nextOperation == null && !lookupAdmitted && visible.any { it.name in omitted && ProgressiveToolCatalog.isGitHubTool(it) }) {
        val needed = visible.filter { ProgressiveToolCatalog.isGitHubTool(it) || it.name in requiredNames }
        throw GitHubToolContextBudgetException(needed.sumOf { providerSchemaTokens(it, limits.githubMode) }, budget.available, budget).withRequiredSchemas(needed, limits.githubMode)
    }
    return selected
}

/** Pause unchanged discovery for one actionable round. Admission still reserves a path to every authorized schema. */
internal fun afterRepeatedGitHubDiscovery(admitted: List<AgentToolDefinition>, paused: Boolean): List<AgentToolDefinition> {
    if (!paused || admitted.none { it.name != ProgressiveToolCatalog.NAME && ProgressiveToolCatalog.isGitHubTool(it) }) return admitted
    return admitted.filterNot { it.name == ProgressiveToolCatalog.NAME }
}

/** Use the resolver's catalog for both planning and execution, including telemetry-wrapped tools. */
internal fun planAuthorizedToolContext(
    turns: List<ConversationTurn>,
    prompt: String,
    tools: List<ResolvedAgentTool>,
    limits: TokenBudgetSettings,
    externalTools: Boolean,
    githubMode: Boolean = false,
    requestedTask: String? = null,
    requireToolExecution: Boolean = true,
    allowOutputReduction: Boolean = false,
    planningProvenance: CapacityProvenance = CapacityProvenance.UNKNOWN
): ContextPlan {
    // Saved receipts and prepared evidence can contain tool names and imperatives.
    // They remain context, never new requirements attributed to the user.
    val task = requestedTask ?: turns.lastOrNull { it.isCurrentTurn }?.userMessage?.content.orEmpty()
    val existingCatalog = tools.firstNotNullOfOrNull { it.progressiveCatalog }
    val integration = externalTools &&
        dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(task) &&
        (task.contains("github", ignoreCase = true) || githubMode) &&
        tools.any { ProgressiveToolCatalog.isGitHubTool(it.tool.definition) }
    val effectiveGitHubMode = githubMode || existingCatalog?.githubMode == true || integration
    val scopedGitHubStatus = effectiveGitHubMode &&
        dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(task) &&
        task.lineSequence().none { it.trim() == "[github-workflow-preset:test_integration]" }
    val lookup = if (effectiveGitHubMode) GitHubLookupRoute.from(task) else null
    val fullGitHubCatalog = effectiveGitHubMode && !scopedGitHubStatus && lookup == null
    // Current user intent wins over repository keywords in memory or workspace instructions.
    if (!fullGitHubCatalog && CurrentRequestContract.from(task).conversational) return ContextBudgetService.plan(turns, prompt, emptyList(), limits, planningProvenance = planningProvenance)
    val definitions = tools.map { it.tool.definition }
    val recovery = definitions.filter { it.modelVisible && it.name == "read_recovery_context" }
    val required = (
        if (requireToolExecution) {
            requiredToolSchemas(explicitlyRequiredToolNames(task, definitions.mapTo(hashSetOf()) { it.name }), tools)
        } else {
            emptyList()
        } + recovery
        ).distinctBy { it.name }
    val catalog = if (externalTools) existingCatalog ?: if (limits.adaptiveToolCatalog || scopedGitHubStatus || lookup != null || integration) ProgressiveToolCatalog.create(definitions, task, githubMode = effectiveGitHubMode) else null else null
    val normalized = limits.normalized()
    // Readiness for every explicit tool is validated above, including credentials and ambiguous aliases.
    // Stage by measured headroom, including verified 5,632-token routes. A capacity
    // label is not evidence that several operation schemas fit in this request.
    val currentCosts = ContextBudgetService.breakdown(turns.filter { it.isCurrentTurn }, prompt, emptyList())
    val plannedOutput = normalized.outputTokens.takeIf { it > 0 } ?: minOf(4096, normalized.contextTokens / 4)
    val initialBudget = GitHubSchemaBudget(normalized.contextTokens, currentCosts.total.toLong(), plannedOutput, minOf(256, normalized.contextTokens / 16), minOf(8192, normalized.contextTokens / 8), currentCosts, wireSafety = true)
    val allRequiredTokens = (required + listOfNotNull(catalog?.definition)).distinctBy { it.name }.sumOf { providerSchemaTokens(it, effectiveGitHubMode) }
    val staged = required.filter { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null }
        .takeIf { effectiveGitHubMode && fullGitHubCatalog && normalized.contextTokens != Int.MAX_VALUE && it.size > 1 && allRequiredTokens > initialBudget.available }.orEmpty()
    catalog?.stageRequiredOperations(staged.map { it.name })
    val immediateRequired = if (staged.isEmpty()) required else required - staged.toSet() + staged.first()
    val advertised = (immediateRequired + (catalog?.selected(definitions + catalog.definition) ?: definitions.filter { it.modelVisible })).distinctBy { it.name }
    // Pin the immediate status/context read, not every operation mentioned in
    // a multi-step engineering task. Remaining schemas are discoverable later.
    val requestedGitHubOperations = if (requireToolExecution) {
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task)
    } else {
        emptySet()
    }
    val requiredGitHubSchemas = when {
        !requireToolExecution -> emptyList()
        lookup != null -> advertised.filter(lookup::matchesTool)
        scopedGitHubStatus -> advertised.filter {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) in dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.requiredOperations(task)
        }
        staged.isNotEmpty() -> listOf(staged.first())
        fullGitHubCatalog -> dev.chungjungsoo.gptmobile.data.github.githubFamilyTools(advertised, "github_context")
        else -> advertised.filter { definition ->
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in requestedGitHubOperations
        }
    }
    val immediateGitHubSchemas = requiredGitHubSchemas.groupBy {
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) to it.name.substringAfter("__", "")
    }.values.map { alternatives -> alternatives.firstOrNull { it.name.substringBefore("__").endsWith("_inspect") } ?: alternatives.first() }
    // The staged inventory is still authorized only while its discovery path
    // survives initial planning. Charge that path before optional schemas rather
    // than silently reducing executable authority to the schemas that happen to fit.
    val requiredDiscovery = catalog?.definition?.takeIf { effectiveGitHubMode || requiredGitHubSchemas.isNotEmpty() }
    var pinnedSchemas = (immediateGitHubSchemas + immediateRequired + listOfNotNull(requiredDiscovery)).distinctBy { it.name }
    var effectiveLimits = normalized
    var admittedGitHubBudget: GitHubSchemaBudget? = null
    if ((requiredGitHubSchemas.isNotEmpty() || requiredDiscovery != null) && normalized.contextTokens != Int.MAX_VALUE) {
        val output = plannedOutput
        val requestedBudget = initialBudget
        val requiredTokens = pinnedSchemas.sumOf { providerSchemaTokens(it, githubMode = true) }
        val fitted = try {
            requestedBudget.fitInitialSchemas(requiredTokens, allowOutputReduction)
        } catch (failure: GitHubToolContextBudgetException) {
            val compact = pinnedSchemas.map(GitHubActionSchema::compactRead)
            val compactTokens = compact.sumOf { providerSchemaTokens(it, githubMode = true) }
            if (catalog == null || compactTokens >= requiredTokens) throw failure.withRequiredSchemas(pinnedSchemas, true)
            val budget = try {
                requestedBudget.fitInitialSchemas(compactTokens, allowOutputReduction)
            } catch (stillTooLarge: GitHubToolContextBudgetException) {
                throw stillTooLarge.withRequiredSchemas(compact, true)
            }
            catalog.useConciseReadSchemas()
            pinnedSchemas = compact
            budget
        }
        admittedGitHubBudget = fitted
        if (fitted.output < output) effectiveLimits = normalized.copy(outputTokens = fitted.output)
    }
    val prioritized = (pinnedSchemas + advertised.filter { it.name == ProgressiveToolCatalog.NAME } + advertised).distinctBy { it.name }
    val plan = ContextBudgetService.plan(
        turns,
        prompt,
        prioritized,
        effectiveLimits,
        admittedGitHubBudget?.toolResults,
        if (effectiveLimits.contextTokens == limits.contextTokens) planningProvenance else CapacityProvenance.UNKNOWN,
        reserveEstimatedSafety = prioritized.isNotEmpty(),
        continuityRequiredSchemas = pinnedSchemas.mapTo(hashSetOf()) { it.name }.takeIf { effectiveGitHubMode }
    )
    val omittedRequired = pinnedSchemas.filterNot { wanted -> plan.tools.any { it.name == wanted.name } }
    if (omittedRequired.isNotEmpty()) {
        admittedGitHubBudget?.let { budget ->
            throw GitHubToolContextBudgetException(pinnedSchemas.sumOf { providerSchemaTokens(it, githubMode = true) }, budget.available, budget).withRequiredSchemas(pinnedSchemas, true)
        }
        val names = omittedRequired.joinToString { it.name }
        AppLogRecorder.record("ToolCatalog", "REQUIRED_TOOL_BLOCKED · names=$names · reason=CONTEXT_BUDGET · dispatched=false", "W")
        throw IllegalArgumentException("REQUIRED_TOOL_CONTEXT_BUDGET: The required tools ($names) do not fit the selected context allowance. Increase the context budget or reduce attached history before starting; no model or tool request was dispatched.")
    }
    ToolCatalogDiagnostics.recordOmissions("initial_context", advertised.filterNot { candidate -> plan.tools.any { it.name == candidate.name } }.associate { it.name to "CONTEXT_BUDGET" })
    // Authorization stays discoverable only if the discovery schema itself fits.
    val authorized = when {
        catalog != null && plan.tools.any { it.name == catalog.definition.name } -> definitions
        staged.isNotEmpty() -> (plan.authorizedTools + staged).distinctBy { it.name }
        else -> return plan
    }
    return plan.copy(authorizedTools = authorized, stagedGitHubOperations = staged.map { it.name })
}

/** A discovery receipt must not promise a schema that the runner could not admit. */
internal fun reconcileDiscoveryExchange(exchanges: MutableList<AgentToolExchange>, definitions: List<AgentToolDefinition>) {
    val last = exchanges.lastOrNull() ?: return
    exchanges[exchanges.lastIndex] = last.copy(results = reconcileDiscoveryResults(last.calls, last.results, definitions))
}

internal fun reconcileDiscoveryResults(calls: List<ProviderEvent.ToolCall>, results: List<AgentToolResult>, definitions: List<AgentToolDefinition>): List<AgentToolResult> {
    val discoveryIds = calls.filter { it.name == ProgressiveToolCatalog.NAME }.mapTo(HashSet()) { it.callId }
    if (discoveryIds.isEmpty()) return results
    val names = definitions.mapTo(HashSet()) { it.name }
    return results.map { result ->
        val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject
        val available = payload?.get("available_next_round") as? JsonArray
        if (result.callId !in discoveryIds || payload == null || available == null) return@map result
        val excluded = available.filter { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content !in names }
        val corrected = if (excluded.isEmpty()) {
            payload
        } else {
            payload + mapOf(
                "available_next_round" to JsonArray(available - excluded.toSet()),
                "context_budget_excluded" to JsonArray(excluded.mapNotNull { (it as? JsonObject)?.get("name") }),
                "next_step" to JsonPrimitive("Some discovered schemas could not fit the remaining request context. Use only the attached schemas; do not claim those excluded tools are callable.")
            )
        }
        val coverage = payload["coverage"] as? JsonObject
        val reconciled = if (coverage == null) corrected else corrected + ("coverage" to JsonObject(coverage + ("schemas_admitted_next_round" to JsonPrimitive(available.size - excluded.size))))
        result.copy(content = ToolResultContent.Json(JsonObject(reconciled)))
    }
}
