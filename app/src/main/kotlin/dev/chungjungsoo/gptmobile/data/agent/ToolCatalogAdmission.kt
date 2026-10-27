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

/** Recheck warm as well as cold discoveries against the current request's remaining room. */
internal fun fitProviderToolSchemas(definitions: List<AgentToolDefinition>, limits: AgentRunLimits, initialSchemaTokens: Long, replayTokens: Long): List<AgentToolDefinition> {
    val visible = definitions.filter { it.modelVisible }
    if (limits.contextTokens == Int.MAX_VALUE) return visible
    val base = (limits.initialContextTokens.toLong() - initialSchemaTokens).coerceAtLeast(0)
    var remaining = (limits.contextTokens.toLong() - base - replayTokens - limits.finalResponseReserveTokens - minOf(256, limits.contextTokens / 16)).coerceAtLeast(0)
    return visible.filter { tool ->
        val cost = ProgressiveToolCatalog.schemaTokens(tool)
        (cost <= remaining).also { fits ->
            if (fits) {
                remaining -= cost
            } else {
                AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=provider_round · name=${tool.name} · reason=REMAINING_CONTEXT_BUDGET · schemaTokens=$cost")
            }
        }
    }
}

/** Use the resolver's catalog for both planning and execution, including telemetry-wrapped tools. */
internal fun planAuthorizedToolContext(turns: List<ConversationTurn>, prompt: String, tools: List<ResolvedAgentTool>, limits: TokenBudgetSettings, externalTools: Boolean): ContextPlan {
    val definitions = tools.map { it.tool.definition }
    val catalog = if (externalTools) tools.firstNotNullOfOrNull { it.progressiveCatalog } ?: if (limits.adaptiveToolCatalog) ProgressiveToolCatalog.create(definitions, turns.lastOrNull { it.isCurrentTurn }?.userMessage?.content.orEmpty()) else null else null
    val advertised = catalog?.selected(definitions + catalog.definition) ?: definitions.filter { it.modelVisible }
    val requestedGitHubOperations = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(prompt)
    val requiredGitHubSchemas = advertised.filter { definition ->
        dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(definition.name) in requestedGitHubOperations
    }
    val prioritized = requiredGitHubSchemas + advertised.filterNot { it in requiredGitHubSchemas }
    val plan = ContextBudgetService.plan(turns, prompt, prioritized, limits)
    val omittedRequiredGitHubSchemas = requiredGitHubSchemas.filterNot { required -> plan.tools.any { it.name == required.name } }
    if (omittedRequiredGitHubSchemas.isNotEmpty()) {
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
    advertised.filterNot { candidate -> plan.tools.any { it.name == candidate.name } }.forEach { tool ->
        AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=initial_context · name=${tool.name} · reason=CONTEXT_BUDGET · schemaTokens=${ProgressiveToolCatalog.schemaTokens(tool)}")
    }
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
        if (excluded.isEmpty()) return@map result
        val corrected = payload + mapOf(
            "available_next_round" to JsonArray(available - excluded.toSet()),
            "context_budget_excluded" to JsonArray(excluded.mapNotNull { (it as? JsonObject)?.get("name") }),
            "next_step" to JsonPrimitive("Some discovered schemas could not fit the remaining request context. Use only the attached schemas; do not claim those excluded tools are callable.")
        )
        result.copy(content = ToolResultContent.Json(JsonObject(corrected)))
    }
}
