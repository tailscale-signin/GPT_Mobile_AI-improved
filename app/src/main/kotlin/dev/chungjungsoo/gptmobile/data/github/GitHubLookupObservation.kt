package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An exact lookup may request only a visible read on the accepted repository and connection. */
internal fun GitHubLookupRoute.Request.firstRead(
    trustedTools: List<AgentToolDefinition>,
    repository: String,
    branch: String?
): ProviderEvent.ToolCall? {
    val target = repository.split('/')
    if (target.size != 2 || target.any { it.isBlank() || it != it.trim() }) return null
    val candidates = trustedTools.filter { it.modelVisible && it.name.githubOperationFamily() == route.operation }
    val first = candidates.firstOrNull() ?: return null
    if (candidates.any { !it.name.githubReadFacadeMatches(first.name) }) return null
    if (route == GitHubLookupRoute.COMMITS && branch.isNullOrBlank()) return null
    val arguments = buildJsonObject {
        put("owner", target[0])
        put("repo", target[1])
        put("action", route.action)
        number?.let { put(if (route == GitHubLookupRoute.ISSUE) "issue_number" else "pull_number", it) }
        state?.let { put("state", it) }
        if (newestFirst) {
            put("sort", "created")
            put("direction", "desc")
        }
        pageSize?.let { put("per_page", it) }
        if (route == GitHubLookupRoute.COMMITS) put("ref", requireNotNull(branch))
        if (filesOnly) {
            put("include_files", true)
            put("include_checks", false)
            if (patches) put("include_patches", true)
        }
    }
    val tool = candidates.firstOrNull {
        ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty() &&
            (it.contractSchema == it.inputSchema || ToolArgumentValidator.errors(arguments, it.contractSchema).isEmpty())
    } ?: return null
    return ProviderEvent.ToolCall("lookup-" + route.step, tool.name, arguments)
}
