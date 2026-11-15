package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ContextCostBreakdown
import dev.chungjungsoo.gptmobile.data.context.ContextPlan
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
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

    fun withAdmissionEvidence(evidence: JsonObject): GitHubToolContextBudgetException =
        GitHubToolContextBudgetException(requiredSchemaTokens, availableSchemaTokens, budget, evidence).also { it.initCause(this) }

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
                put("admission_safety_tokens", evidence.safety)
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
private fun githubSchemaBudgetMessage(requiredSchemaTokens: Long, availableSchemaTokens: Long, measuredBudget: GitHubSchemaBudget?, evidence: JsonObject?): String = buildString {
    append("GITHUB_TOOL_CONTEXT_BUDGET: Required GitHub, recovery and discovery schemas need approximately $requiredSchemaTokens tokens, but $availableSchemaTokens remain.")
    val budget = measuredBudget?.takeIf {
        it.capacity in 1..16_000_000 &&
            it.input in 0L..16_000_000L &&
            it.output in 0..16_000_000 &&
            it.safety in 0..16_000_000 &&
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
        append(" It reserved ${budget.input} for input, ${budget.output} for the reply and ${budget.safety} for safety")
        budget.toolResults?.let { append(", plus $it for tool results") }
        append(" (token estimates).")
        if (requiredSchemaTokens in 0L..16_000_000L && availableSchemaTokens in 0L..16_000_000L && requiredSchemaTokens > availableSchemaTokens) {
            val shortfall = requiredSchemaTokens - availableSchemaTokens
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
    append(", then start a new request. This provider request was not dispatched.")
}

internal data class GitHubSchemaBudget(val capacity: Int, val input: Long, val output: Int, val safety: Int, val toolResults: Int? = null, val promptCosts: ContextCostBreakdown? = null) {
    val available: Long get() = (capacity.toLong() - input - output - safety - (toolResults ?: 0)).coerceAtLeast(0)
}

internal fun providerSchemaTokens(tool: AgentToolDefinition, githubMode: Boolean): Long = ProgressiveToolCatalog.schemaTokens(tool).toLong() + if (githubMode) 16 else 0

/** Recheck warm as well as cold discoveries against the current request's remaining room. */
internal fun fitProviderToolSchemas(definitions: List<AgentToolDefinition>, limits: AgentRunLimits, initialSchemaTokens: Long, replayTokens: Long): List<AgentToolDefinition> {
    val visible = definitions.filter { it.modelVisible }
    if (limits.contextTokens == Int.MAX_VALUE) return visible
    val base = (limits.initialContextTokens.toLong() - initialSchemaTokens).coerceAtLeast(0)
    val budget = GitHubSchemaBudget(limits.contextTokens, base + replayTokens, limits.finalResponseReserveTokens, minOf(256, limits.contextTokens / 16))
    var remaining = budget.available
    // The native status plan needs every admitted status/health read even when
    // replay grows. Other engineering operations remain staged on demand.
    val statusOperations = limits.githubTaskRequest?.takeIf {
        dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(it) &&
            it.lineSequence().none { line -> line.trim() == "[github-workflow-preset:test_integration]" }
    }?.let(dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest::requiredOperations).orEmpty()
    val required = visible.filter {
        (limits.githubMode || limits.githubTask) &&
            (it.name == "read_recovery_context" || dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) in statusOperations)
    }
    val requiredSchemaTokens = required.sumOf { providerSchemaTokens(it, limits.githubMode) }
    if (requiredSchemaTokens > remaining) throw GitHubToolContextBudgetException(requiredSchemaTokens, remaining, budget)
    remaining -= requiredSchemaTokens
    val requiredNames = required.mapTo(hashSetOf()) { it.name }
    // Preserve a path to the rest of the authorized catalog when a large optional
    // schema consumes the remaining round budget. The returned order stays stable.
    val discovery = visible.firstOrNull { it.name == ProgressiveToolCatalog.NAME }
    val discoveryFits = discovery != null && providerSchemaTokens(discovery, limits.githubMode) <= remaining
    if (discoveryFits) remaining -= providerSchemaTokens(requireNotNull(discovery), limits.githubMode)
    val omitted = linkedMapOf<String, String>()
    val selected = visible.filter { tool ->
        if (tool.name in requiredNames || tool === discovery && discoveryFits) return@filter true
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
    if ((limits.githubMode || limits.githubTask) && !discoveryFits && visible.any { it.name in omitted && ProgressiveToolCatalog.isGitHubTool(it) }) {
        val needed = visible.filter { ProgressiveToolCatalog.isGitHubTool(it) || it.name in requiredNames }
            .sumOf { providerSchemaTokens(it, limits.githubMode) }
        throw GitHubToolContextBudgetException(needed, budget.available, budget)
    }
    return selected
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
    allowOutputReduction: Boolean = false
): ContextPlan {
    // Saved receipts and prepared evidence can contain tool names and imperatives.
    // They remain context, never new requirements attributed to the user.
    val task = requestedTask ?: turns.lastOrNull { it.isCurrentTurn }?.userMessage?.content.orEmpty()
    val existingCatalog = tools.firstNotNullOfOrNull { it.progressiveCatalog }
    val effectiveGitHubMode = githubMode || existingCatalog?.githubMode == true
    val scopedGitHubStatus = effectiveGitHubMode &&
        dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.matches(task) &&
        task.lineSequence().none { it.trim() == "[github-workflow-preset:test_integration]" }
    val fullGitHubCatalog = effectiveGitHubMode && !scopedGitHubStatus
    // Current user intent wins over repository keywords in memory or workspace instructions.
    if (!fullGitHubCatalog && CurrentRequestContract.from(task).conversational) return ContextBudgetService.plan(turns, prompt, emptyList(), limits)
    val definitions = tools.map { it.tool.definition }
    val recovery = definitions.filter { it.modelVisible && it.name == "read_recovery_context" }
    val required = (
        if (requireToolExecution) {
            requiredToolSchemas(explicitlyRequiredToolNames(task, definitions.mapTo(hashSetOf()) { it.name }), tools)
        } else {
            emptyList()
        } + recovery
        ).distinctBy { it.name }
    val catalog = if (externalTools) existingCatalog ?: if (limits.adaptiveToolCatalog || scopedGitHubStatus) ProgressiveToolCatalog.create(definitions, task, githubMode = effectiveGitHubMode) else null else null
    val advertised = (required + (catalog?.selected(definitions + catalog.definition) ?: definitions.filter { it.modelVisible })).distinctBy { it.name }
    // Pin the immediate status/context read, not every operation mentioned in
    // a multi-step engineering task. Remaining schemas are discoverable later.
    val requestedGitHubOperations = if (requireToolExecution) {
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task)
    } else {
        emptySet()
    }
    val requiredGitHubSchemas = when {
        !requireToolExecution -> emptyList()
        scopedGitHubStatus -> advertised.filter {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) in dev.chungjungsoo.gptmobile.data.github.GitHubStatusRequest.requiredOperations(task)
        }
        fullGitHubCatalog -> advertised.filter {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) == "github_context"
        }.take(1)
        else -> advertised.filter { definition ->
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in requestedGitHubOperations
        }
    }
    val immediateGitHubSchemas = requiredGitHubSchemas.groupBy {
        it.name.replace("_inspect__", "__").removeSuffix("_inspect")
    }.values.map { alternatives -> alternatives.firstOrNull { it.name.substringBefore("__").endsWith("_inspect") } ?: alternatives.first() }
    // The staged inventory is still authorized only while its discovery path
    // survives initial planning. Charge that path before optional schemas rather
    // than silently reducing executable authority to the schemas that happen to fit.
    val requiredDiscovery = catalog?.definition?.takeIf { effectiveGitHubMode || requiredGitHubSchemas.isNotEmpty() }
    val pinnedSchemas = (immediateGitHubSchemas + required + listOfNotNull(requiredDiscovery)).distinctBy { it.name }
    val normalized = limits.normalized()
    var effectiveLimits = normalized
    var admittedGitHubBudget: GitHubSchemaBudget? = null
    if ((requiredGitHubSchemas.isNotEmpty() || requiredDiscovery != null) && normalized.contextTokens != Int.MAX_VALUE) {
        val output = normalized.outputTokens.takeIf { it > 0 } ?: minOf(4096, normalized.contextTokens / 4)
        val currentCosts = ContextBudgetService.breakdown(turns.filter { it.isCurrentTurn }, prompt, emptyList())
        val requestedBudget = GitHubSchemaBudget(normalized.contextTokens, currentCosts.total.toLong(), output, minOf(256, normalized.contextTokens / 16), minOf(8192, normalized.contextTokens / 8), currentCosts)
        val requiredTokens = pinnedSchemas.sumOf { providerSchemaTokens(it, githubMode = true) }
        val minimumOutput = minOf(output, 512)
        val smallerBudget = requestedBudget.copy(output = minimumOutput)
        admittedGitHubBudget = requestedBudget
        if (requiredTokens > requestedBudget.available) {
            if (!allowOutputReduction || requiredTokens > smallerBudget.available) {
                val budget = if (allowOutputReduction) smallerBudget else requestedBudget
                throw GitHubToolContextBudgetException(requiredTokens, budget.available, budget)
            }
            val recoveredOutput = minimumOutput + minOf((output - minimumOutput).toLong(), smallerBudget.available - requiredTokens).toInt()
            effectiveLimits = normalized.copy(outputTokens = recoveredOutput)
            admittedGitHubBudget = requestedBudget.copy(output = recoveredOutput)
        }
    }
    val prioritized = (pinnedSchemas + advertised.filter { it.name == ProgressiveToolCatalog.NAME } + advertised).distinctBy { it.name }
    val plan = ContextBudgetService.plan(turns, prompt, prioritized, effectiveLimits)
    val omittedRequired = pinnedSchemas.filterNot { wanted -> plan.tools.any { it.name == wanted.name } }
    if (omittedRequired.isNotEmpty()) {
        admittedGitHubBudget?.let { budget ->
            throw GitHubToolContextBudgetException(pinnedSchemas.sumOf { providerSchemaTokens(it, githubMode = true) }, budget.available, budget)
        }
        val names = omittedRequired.joinToString { it.name }
        AppLogRecorder.record("ToolCatalog", "REQUIRED_TOOL_BLOCKED · names=$names · reason=CONTEXT_BUDGET · dispatched=false", "W")
        throw IllegalArgumentException("REQUIRED_TOOL_CONTEXT_BUDGET: The required tools ($names) do not fit the selected context allowance. Increase the context budget or reduce attached history before starting; no model or tool request was dispatched.")
    }
    ToolCatalogDiagnostics.recordOmissions("initial_context", advertised.filterNot { candidate -> plan.tools.any { it.name == candidate.name } }.associate { it.name to "CONTEXT_BUDGET" })
    // Authorization stays discoverable only if the discovery schema itself fits.
    return if (catalog != null && plan.tools.any { it.name == catalog.definition.name }) plan.copy(authorizedTools = definitions) else plan
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
