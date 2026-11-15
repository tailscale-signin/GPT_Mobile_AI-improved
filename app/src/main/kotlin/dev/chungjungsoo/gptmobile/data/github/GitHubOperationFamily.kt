package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition

/** Classify the two read-only facades without changing their stored invocation identity. */
internal fun String.githubOperationFamily(): String = when (val name = substringBefore("__")) {
    "github_context_inspect" -> "github_context"
    "github_pr_inspect" -> "github_pr"
    else -> name
}

/** Consider only supplied schemas, retaining the selected connection when a facade changes. */
internal fun githubFamilyTools(tools: List<AgentToolDefinition>, family: String): List<AgentToolDefinition> {
    val candidates = tools.filter { it.modelVisible && it.name.githubOperationFamily() == family }
    if (family !in setOf("github_context", "github_pr")) return candidates
    val connection = candidates.firstOrNull()?.name?.substringAfter("__", "") ?: return emptyList()
    return candidates.filter { it.name.substringAfter("__", "") == connection }
}
