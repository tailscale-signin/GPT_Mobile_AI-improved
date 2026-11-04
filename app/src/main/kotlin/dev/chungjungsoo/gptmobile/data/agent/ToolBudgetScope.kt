package dev.chungjungsoo.gptmobile.data.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Carries the accepted allowance to catalogues created after the original tools were bound. */
internal class ToolBudgetScope(private val budget: ToolExecutionBudget) : AbstractCoroutineContextElement(Key) {
    fun bind(tool: AgentTool): AgentTool = budget.bind(tool)

    companion object Key : CoroutineContext.Key<ToolBudgetScope>
}

/** Resolver-owned catalogues already carry their original authorization and budget wrappers. */
internal suspend fun appendBudgetedCatalog(tools: List<AgentTool>, catalog: ProgressiveToolCatalog?): List<AgentTool> {
    if (catalog == null || tools.any { it.definition.name == catalog.definition.name }) return tools
    val bound = currentCoroutineContext()[ToolBudgetScope]?.bind(catalog) ?: catalog
    return tools + bound
}
