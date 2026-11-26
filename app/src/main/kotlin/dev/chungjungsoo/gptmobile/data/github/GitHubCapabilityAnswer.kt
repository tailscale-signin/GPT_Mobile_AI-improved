package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AppFeatureInventory
import dev.chungjungsoo.gptmobile.data.agent.GitHubToolInventory
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object GitHubCapabilityAnswer {
    private const val PAGE_SIZE = 18
    private val reviewActions = setOf("list", "headers", "context", "comments", "reviews", "threads", "files")
    private fun label(text: String): String = buildString {
        for (char in text) {
            if (char in "\\`*[]<>|") append('\\')
            append(if (char.isISOControl()) ' ' else char)
        }
    }
    private data class Row(val label: String, val type: String, val detail: String, val terms: String)

    fun render(request: GitHubCapabilityRequest, visible: List<AgentToolDefinition>, contracts: List<AgentToolDefinition>, inventory: JsonObject): String {
        val active = visible.filter { it.modelVisible }
        val activeNames = active.mapTo(hashSetOf()) { it.name }
        val discovered = (inventory["observed_names"] as? JsonArray).takeIf { inventory["blocker"] == null }.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        // A current projection wins over the archived executable contract, even for the same name.
        val mismatches = (inventory["contract_changes"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }.toSet()
        val mismatchCount = (inventory["contract_change_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: mismatches.size
        val available = active + contracts.filter {
            // Truncated diagnostics cannot establish which archived names are still current.
            it.modelVisible && mismatchCount == mismatches.size && it.name !in mismatches && it.name in discovered && it.name !in activeNames
        }
        val official = available.filter { AgentToolCapability.GITHUB in it.capabilities }.mapTo(hashSetOf()) { it.name }
        val actionsOnly = request.scope == GitHubCapabilityRequest.Scope.GITHUB
        val includeTools = request.scope != GitHubCapabilityRequest.Scope.FEATURES
        val includeFeatures = request.scope == GitHubCapabilityRequest.Scope.FEATURES || request.scope == GitHubCapabilityRequest.Scope.ALL
        val rows = buildList {
            if (includeTools) {
                val mounted = GitHubToolInventory.mounted(available)
                for (tool in mounted) {
                    if (actionsOnly && tool.name !in official && tool.family !in GitHubOperationTool.names && tool.family !in setOf("github_decide", "repository_graph", "repository_workspace")) continue
                    if (request.pullRequestReads && tool.family != "github_pr") continue
                    val status = if (tool.name in activeNames) "Visible this round" else "In discovery catalog"
                    if (actionsOnly && tool.actions.isNotEmpty()) {
                        for (action in tool.actions) {
                            if (!request.pullRequestReads || action in reviewActions) add(Row(action, tool.name, status, "${tool.family} $action"))
                        }
                    } else if (!request.pullRequestReads || tool.name in official && GitHubMcpRepositoryTool.isReadOnlyOperation(tool.name.substringAfterLast("__"))) {
                        add(Row(tool.name, "Tool", status, "${tool.family} ${tool.actions.joinToString(" ")} ${tool.description}"))
                    }
                }
            }
            if (request.scope == GitHubCapabilityRequest.Scope.ALL) {
                val known = available.mapTo(hashSetOf()) { it.name }
                for (tool in GitHubToolInventory.registered) {
                    if (tool.name !in known) add(Row(tool.name, "Tool", "Documented tool", tool.actions.joinToString(" ")))
                }
            }
            if (includeFeatures) {
                for (feature in AppFeatureInventory.features) {
                    add(Row(feature.title, feature.group, "Documented ${feature.kind}", "${feature.id} ${(feature.terms + feature.tools + feature.plugins).joinToString(" ")}"))
                }
            }
        }
        val words = request.filter.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        val matches = rows.filter { row -> words.all { word -> (row.label + " " + row.type + " " + row.terms).contains(word, true) } }.distinct().sortedWith(compareBy({ it.type }, { it.label }))
        val pages = ((matches.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val command = when (request.scope) {
            GitHubCapabilityRequest.Scope.TOOLS -> "list tools"
            GitHubCapabilityRequest.Scope.FEATURES -> "list features"
            GitHubCapabilityRequest.Scope.GITHUB -> if (request.pullRequestReads) "can you inspect my PRs" else "list GitHub actions"
            GitHubCapabilityRequest.Scope.ALL -> "list all tools and features"
        } + request.filter.takeIf(String::isNotBlank)?.let { " matching $it" }.orEmpty()
        return buildString {
            appendLine(if (request.pullRequestReads) "### Pull request inspection" else "### ${command.substringBefore(" matching").removePrefix("list ").removePrefix("all ").replaceFirstChar(Char::uppercaseChar)}")
            if (request.pullRequestReads && matches.isNotEmpty()) appendLine("Your chat exposes these pull request reads.")
            appendLine("${matches.size} matching entries · Page ${request.page} of $pages")
            if (includeTools) {
                appendLine(if (inventory["complete"] == JsonPrimitive(true)) "Tool coverage includes the complete discovery catalog intersected with current schemas." else "Tool coverage includes currently visible schemas and any verified discovery pages.")
            }
            if (request.page > pages) {
                appendLine("Ask `$command page $pages` for the last page.")
            } else if (matches.isEmpty()) {
                appendLine("No matching entries were found in this scope.")
            } else {
                appendLine()
                for (row in matches.drop((request.page - 1) * PAGE_SIZE).take(PAGE_SIZE)) {
                    appendLine("- ${label(row.label)} · ${label(row.type)} · ${row.detail}")
                }
                if (request.page < pages) appendLine("\nAsk `$command page ${request.page + 1}` to continue.")
            }
            if (actionsOnly) appendLine("\nThe tool checks the target and its permissions when an action runs.")
        }.trimEnd()
    }
}
