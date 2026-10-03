package dev.chungjungsoo.gptmobile.data.agent.tool

/** Repository tasks need the tool-capable worker, rather than the public-web planner. */
internal fun isGitHubTask(task: String): Boolean =
    Regex("\\b(github|git|repository|repositories|repo|pull[ -]request|draft[ -]pr|commit|branch)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)

internal fun ResolvedAgentTool.isGitHubTool(): Boolean =
    realToolName.contains("github", ignoreCase = true) ||
        modelToolName.contains("github", ignoreCase = true) ||
        connectionName.orEmpty().contains("github", ignoreCase = true)

internal fun ResolvedAgentTool.isShellExecutionTool(): Boolean {
    val identity = listOf(realToolName, modelToolName, connectionName.orEmpty())
        .joinToString(" ")
        .lowercase()
    return listOf(
        "posix",
        "shell",
        "terminal",
        "run_command",
        "run command",
        "execute_command",
        "execute command",
        "api:run"
    ).any(identity::contains)
}

internal fun preferNativeGitHubForTask(
    tools: List<ResolvedAgentTool>,
    task: String
): List<ResolvedAgentTool> {
    if (!isGitHubTask(task) || tools.none { it.isGitHubTool() }) return tools
    return tools.filterNot { it.isShellExecutionTool() }
}

/** Keep repository actions callable even when the primary delegates its research. */
internal fun primaryDelegationTools(
    tools: List<ResolvedAgentTool>,
    localResearch: Boolean,
    processingOwnership: Int
): List<ResolvedAgentTool> = tools.filter { tool ->
    when {
        !localResearch || tool.isGitHubTool() -> true
        tool.realToolName == "delegate_to_model" -> true
        tool.realToolName == "web_search" && tool.modelToolName == "web_search" -> true
        tool.realToolName == "read_url" -> true
        processingOwnership < 35 -> false
        else -> !tool.isWebSearchEngine() && !tool.isResearchPageReader()
    }
}

internal fun gitHubCapabilityRefusal(task: String, response: String): Boolean {
    if (!isGitHubTask(task)) return false
    val text = response.lowercase().replace("**", "")
    return "github" in text &&
        (
            "no github integration" in text ||
                "github tools are unavailable" in text ||
                "github tools are not available" in text ||
                "github tools are not enabled" in text ||
                "only tool provided is a read-only" in text
            )
}
