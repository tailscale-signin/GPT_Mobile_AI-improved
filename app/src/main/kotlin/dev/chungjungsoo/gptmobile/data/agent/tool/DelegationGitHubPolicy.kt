package dev.chungjungsoo.gptmobile.data.agent.tool

/** Repository tasks need the tool-capable worker, rather than the public-web planner. */
internal fun isGitHubTask(task: String): Boolean =
    Regex("\\b(github|git|repository|repositories|repo|pull[ -]request|draft[ -]pr|commit|branch)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(task)

/** Short follow-ups inherit repository intent without contaminating a new web question. */
internal fun repositoryRoutingTask(currentTask: String, previousTasks: List<String>): String {
    if (isGitHubTask(currentTask)) return currentTask
    if (!continuationOnly(currentTask)) return currentTask
    val previous = previousTasks.asReversed().firstOrNull { it.isNotBlank() && !continuationOnly(it) }
    return if (previous != null && isGitHubTask(previous)) "$previous\n$currentTask" else currentTask
}

private fun continuationOnly(task: String): Boolean =
    Regex("^(?:please\\s+)?(?:continue|retry|try again|finish(?: it| that| this)?|fix(?: it| that| this)?|go ahead|do it|proceed|yes|push|merge|publish|check again)[.!?\\s]*$", RegexOption.IGNORE_CASE).matches(task.trim())

internal fun ResolvedAgentTool.isGitHubTool(): Boolean =
    realToolName.startsWith("gitmcp__") ||
        realToolName in setOf("fetch_generic_documentation", "search_generic_documentation", "search_generic_code") ||
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
    // Repository catalogs are large and misleading to small llama models during
    // ordinary web questions. Keep them out of both the schema and executor maps.
    if (!isGitHubTask(task)) return tools.filterNot { it.isGitHubTool() }
    if (tools.none { it.isGitHubTool() }) return tools
    return tools.filterNot { it.isShellExecutionTool() }
}

internal fun synthesisSafeTools(
    tools: List<ResolvedAgentTool>,
    runId: String
): List<ResolvedAgentTool> =
    if (runId.startsWith("combined-synthesis:")) {
        tools.filterNot { it.realToolName == "delegate_to_model" }
    } else {
        tools
    }

/** Partial delegation keeps recovery tools; maximum delegation reserves work for the helper. */
internal fun primaryDelegationTools(
    tools: List<ResolvedAgentTool>,
    localResearch: Boolean,
    processingOwnership: Int
): List<ResolvedAgentTool> = tools.filter { tool ->
    when {
        localResearch && processingOwnership == 0 -> tool.realToolName == "delegate_to_model"
        !localResearch || tool.isGitHubTool() -> true
        tool.isAmazonProductTool() -> true
        tool.realToolName == "delegate_to_model" -> true
        tool.realToolName == "web_search" && tool.modelToolName == "web_search" -> true
        tool.connectionUid == null && tool.realToolName == "read_url" -> true
        else -> {
            val description = tool.tool.definition.description
            val researchLikeByIdentity =
                isCrawlerTool(tool.realToolName, description) ||
                    isNamedWebSearch(tool.realToolName, description) ||
                    isCrawlerTool(tool.modelToolName, description) ||
                    isNamedWebSearch(tool.modelToolName, description)
            !researchLikeByIdentity && !tool.isWebSearchEngine() && !tool.isResearchPageReader()
        }
    }
}

/** The final pass cannot restart work already completed by the delegate and reviewer. */
internal fun reviewedSynthesisTools(tools: List<ResolvedAgentTool>, processingOwnership: Int, followUpsEnabled: Boolean): List<ResolvedAgentTool> =
    if (processingOwnership == 0) {
        emptyList()
    } else {
        tools.filterNot {
            it.realToolName == "delegate_to_model" ||
                (!followUpsEnabled && (it.isWebSearchEngine() || it.isResearchPageReader() || it.realToolName == "web_search" || it.realToolName == "read_url"))
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
