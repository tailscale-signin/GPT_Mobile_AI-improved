package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Route only exact, retained read recipes through the ordinary authorization/budget path. */
internal object GitHubRecoveryCalls {
    private val keys = setOf("continuation_call", "correction_call", "recovery_call", "source_recovery_call")

    fun repairSourceRead(call: ProviderEvent.ToolCall, tool: AgentTool?): ProviderEvent.ToolCall {
        if (tool == null || call.name.githubOperationFamily() != "github_read") return call
        val schema = tool.definition.inputSchema
        if (ToolArgumentValidator.errors(call.arguments, schema).isEmpty()) return call
        val corrected = ToolArgumentValidator.sourceReadArgumentCorrection(call.arguments) ?: return call
        if (ToolArgumentValidator.errors(corrected, schema).isNotEmpty() ||
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors("github_read", corrected).isNotEmpty() ||
            !GitHubAuthorizationBoundary.isReplayableRead(call.name, corrected)
        ) {
            return call
        }
        return call.copy(arguments = corrected)
    }

    fun resolve(call: ProviderEvent.ToolCall, exchanges: List<AgentToolExchange>, tools: Map<String, AgentTool>): ProviderEvent.ToolCall {
        val envelope = call.arguments.takeIf { it.size == 1 }?.entries?.singleOrNull()
            ?.takeIf { it.key in keys }?.value as? JsonObject
        val recipes = exchanges.asReversed().asSequence().flatMap { exchange ->
            exchange.results.asReversed().asSequence().mapNotNull { GitHubReplayRecovery.envelope(it.content) }
        }.flatMap { result -> keys.asSequence().mapNotNull { result[it] as? JsonObject } }
        val candidate = if (envelope != null) {
            recipes.firstOrNull { it == envelope }
        } else if (call.arguments.keys == setOf("view_cursor")) {
            recipes.firstOrNull { it["arguments"] == call.arguments }
        } else {
            null
        }
        val recipe = candidate ?: return call
        val name = (recipe["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return call
        val arguments = recipe["arguments"] as? JsonObject ?: return call
        val tool = tools[name] ?: return call
        if (!GitHubAuthorizationBoundary.isReplayableRead(name, arguments) || ToolArgumentValidator.errors(arguments, tool.definition.inputSchema).isNotEmpty()) return call
        return call.copy(name = name, arguments = arguments)
    }
}
