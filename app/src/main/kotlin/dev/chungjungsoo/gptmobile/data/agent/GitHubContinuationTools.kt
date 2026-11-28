package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Stage an authorized action contract before sacrificing required task memory. */
internal object GitHubContinuationTools {
    fun select(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>, fits: (List<AgentToolDefinition>) -> Boolean): List<AgentToolDefinition> {
        val actions = linkedMapOf<String, String>()
        val priorities = linkedSetOf<String>()
        for (exchange in exchanges) {
            for (call in exchange.calls) {
                val action = (call.arguments["action"] as? JsonPrimitive)?.contentOrNull ?: continue
                val selected = if (call.name == ProgressiveToolCatalog.NAME) {
                    ((call.arguments["names"] as? JsonArray)?.singleOrNull() as? JsonPrimitive)?.contentOrNull
                } else {
                    call.name
                }
                if (selected != null) {
                    actions[selected] = action
                    priorities.remove(selected)
                    priorities.add(selected)
                }
            }
            for (result in exchange.results) {
                val payload = GitHubContinuationMemory.payload(result) ?: continue
                for (field in listOf("continuation_call", "correction_call", "recovery_call")) {
                    val recipe = payload[field] as? JsonObject ?: continue
                    val name = (recipe["tool"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val arguments = recipe["arguments"] as? JsonObject ?: continue
                    (arguments["action"] as? JsonPrimitive)?.contentOrNull?.let { actions[name] = it }
                    priorities.remove(name)
                    priorities.add(name)
                }
            }
        }
        val projected = tools.map { tool ->
            actions[tool.name]?.let { GitHubActionSchema.project(tool, it) } ?: GitHubActionSchema.compactRead(tool)
        }
        if (fits(projected)) return projected
        // Staged operations remain discoverable. Without a directory, refusing
        // is safer than silently removing the only route to another required action.
        val directory = projected.firstOrNull { it.name == ProgressiveToolCatalog.NAME } ?: return projected
        val ordered = projected.filterNot { it === directory }.sortedWith(
            compareByDescending<AgentToolDefinition> { priorities.indexOf(it.name) }
                .thenBy { AgentToolCapability.USER_DECISION in it.capabilities || it.name.githubOperationFamily() == "github_decide" }
                .thenBy { ContextBudgetService.breakdown(emptyList(), "", listOf(it)).total }
        )
        val selected = mutableListOf(directory)
        for (tool in ordered) if (fits(selected + tool)) selected += tool
        if (selected.size == 1 && ordered.any { AgentToolCapability.USER_DECISION !in it.capabilities }) return projected
        return projected.filter { tool -> selected.any { it.name == tool.name } }
    }
}
