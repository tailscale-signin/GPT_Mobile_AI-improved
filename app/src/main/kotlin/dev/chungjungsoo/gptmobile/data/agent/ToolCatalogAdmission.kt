package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ContextPlan
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class GitHubToolContextBudgetException(val requiredSchemaTokens: Long, val availableSchemaTokens: Long) :
    IllegalArgumentException(
        "GITHUB_TOOL_CONTEXT_BUDGET: Required GitHub and recovery schemas need approximately $requiredSchemaTokens tokens, but $availableSchemaTokens remain. Increase the context budget or reduce attached history and instructions before continuing; this provider request was not dispatched."
    ) {
    fun providerFailure() = ProviderEvent.Failed(
        message.orEmpty(),
        errorCode = "GITHUB_TOOL_CONTEXT_BUDGET",
        failureDetails = buildJsonObject {
            put("failed_stage", "provider_admission")
            put("dispatched", false)
            put("retryable", false)
            put("required_schema_tokens", requiredSchemaTokens)
            put("available_schema_tokens", availableSchemaTokens)
        }
    )
}

internal fun providerSchemaTokens(tool: AgentToolDefinition, githubMode: Boolean): Long = ProgressiveToolCatalog.schemaTokens(tool).toLong() + if (githubMode) 16 else 0

/** Recheck warm as well as cold discoveries against the current request's remaining room. */
internal fun fitProviderToolSchemas(definitions: List<AgentToolDefinition>, limits: AgentRunLimits, initialSchemaTokens: Long, replayTokens: Long): List<AgentToolDefinition> {
    val visible = definitions.filter { it.modelVisible }
    if (limits.contextTokens == Int.MAX_VALUE) return visible
    val base = (limits.initialContextTokens.toLong() - initialSchemaTokens).coerceAtLeast(0)
    var remaining = (limits.contextTokens.toLong() - base - replayTokens - limits.finalResponseReserveTokens - minOf(256, limits.contextTokens / 16)).coerceAtLeast(0)
    val github = visible.filter {
        limits.githubMode &&
            ProgressiveToolCatalog.isGitHubTool(it) ||
            (limits.githubMode || limits.githubTask) &&
            it.name == "read_recovery_context"
    }
    val requiredSchemaTokens = github.sumOf { providerSchemaTokens(it, limits.githubMode) }
    if (requiredSchemaTokens > remaining) throw GitHubToolContextBudgetException(requiredSchemaTokens, remaining)
    remaining -= requiredSchemaTokens
    val requiredNames = github.mapTo(hashSetOf()) { it.name }
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
    requireToolExecution: Boolean = true
): ContextPlan {
    // Saved receipts and prepared evidence can contain tool names and imperatives.
    // They remain context, never new requirements attributed to the user.
    val task = requestedTask ?: turns.lastOrNull { it.isCurrentTurn }?.userMessage?.content.orEmpty()
    val fullGitHubCatalog = githubMode || tools.any { it.progressiveCatalog?.githubMode == true }
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
    val catalog = if (externalTools) tools.firstNotNullOfOrNull { it.progressiveCatalog } ?: if (limits.adaptiveToolCatalog) ProgressiveToolCatalog.create(definitions, task, githubMode = fullGitHubCatalog) else null else null
    val githubSchemas = if (fullGitHubCatalog) definitions.filter { it.modelVisible && ProgressiveToolCatalog.isGitHubTool(it) } else emptyList()
    val advertised = (githubSchemas + required + (catalog?.selected(definitions + catalog.definition) ?: definitions.filter { it.modelVisible })).distinctBy { it.name }
    val requestedGitHubOperations = if (requireToolExecution) dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(task) else emptySet()
    val requiredGitHubSchemas = if (fullGitHubCatalog) {
        githubSchemas
    } else {
        advertised.filter { definition ->
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in requestedGitHubOperations
        }
    }
    if (fullGitHubCatalog && githubSchemas.isNotEmpty()) {
        val normalized = limits.normalized()
        if (normalized.contextTokens != Int.MAX_VALUE) {
            val output = normalized.outputTokens.takeIf { it > 0 } ?: minOf(4096, normalized.contextTokens / 4)
            val currentCost = ContextBudgetService.breakdown(turns.filter { it.isCurrentTurn }, prompt, emptyList()).total
            val available = (normalized.contextTokens.toLong() - output - minOf(8192, normalized.contextTokens / 8) - minOf(256, normalized.contextTokens / 16) - currentCost).coerceAtLeast(0)
            val requiredTokens = githubSchemas.sumOf { providerSchemaTokens(it, true) }
            if (requiredTokens > available) throw GitHubToolContextBudgetException(requiredTokens, available)
        }
    }
    val prioritized = (githubSchemas + required + advertised.filter { it.name == ProgressiveToolCatalog.NAME } + requiredGitHubSchemas + advertised).distinctBy { it.name }
    val plan = ContextBudgetService.plan(turns, prompt, prioritized, limits)
    val omittedRequired = required.filterNot { wanted -> plan.tools.any { it.name == wanted.name } }
    if (omittedRequired.isNotEmpty()) {
        val names = omittedRequired.joinToString { it.name }
        AppLogRecorder.record("ToolCatalog", "REQUIRED_TOOL_BLOCKED · names=$names · reason=CONTEXT_BUDGET · dispatched=false", "W")
        throw IllegalArgumentException("REQUIRED_TOOL_CONTEXT_BUDGET: The required tools ($names) do not fit the selected context allowance. Increase the context budget or reduce attached history before starting; no model or tool request was dispatched.")
    }
    val omittedRequiredGitHubSchemas = requiredGitHubSchemas.filterNot { required -> plan.tools.any { it.name == required.name } }
    if (omittedRequiredGitHubSchemas.isNotEmpty()) {
        if (fullGitHubCatalog) throw GitHubToolContextBudgetException(githubSchemas.sumOf { providerSchemaTokens(it, true) }, plan.tools.filter(ProgressiveToolCatalog::isGitHubTool).sumOf { providerSchemaTokens(it, true) })
        val operations = omittedRequiredGitHubSchemas.mapNotNull { definition ->
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name)
        }.distinct()
        AppLogRecorder.record(
            "ToolCatalog",
            "GITHUB_REQUIRED_SCHEMA_BLOCKED · operations=${operations.joinToString()} · reason=CONTEXT_BUDGET · contextTokens=${limits.normalized().contextTokens} · dispatched=false",
            "W"
        )
        throw IllegalArgumentException(
            "GITHUB_REQUIRED_TOOL_CONTEXT_BUDGET: This request requires GitHub operation schemas (${operations.joinToString()}), but they do not fit the selected context budget. Increase this profile's context size or reduce the attached history and instructions; no GitHub write was dispatched."
        )
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
