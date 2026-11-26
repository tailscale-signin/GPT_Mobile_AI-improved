package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.agent.AppFeatureInventory
import dev.chungjungsoo.gptmobile.data.agent.GitHubToolInventory
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayToolInventory

internal enum class GitHubInventorySection(val label: String) {
    BUILT_IN("All built-in"),
    GITHUB("GitHub tools"),
    APP_TOOLS("App tools"),
    FEATURES("App features"),
    RUN("Latest run"),
    GATEWAY("Gateway");

    val builtIn: Boolean get() = this == GITHUB || this == APP_TOOLS || this == FEATURES
}

internal data class GitHubInventoryRow(
    val section: GitHubInventorySection,
    val key: String,
    val title: String,
    val subtitle: String,
    val details: List<String>,
    val terms: List<String> = emptyList(),
    val category: String? = null
) {
    private val searchText = (listOf(title, subtitle) + details + terms).joinToString(" ").let { "$it ${it.replace('_', ' ').replace('-', ' ')}" }

    fun matches(words: List<String>): Boolean = words.all { searchText.contains(it, ignoreCase = true) }
}

internal fun gitHubInventoryResults(rows: List<GitHubInventoryRow>, section: GitHubInventorySection, query: String, category: String?): List<GitHubInventoryRow> {
    val words = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return rows.filter { row ->
        (row.section == section || section == GitHubInventorySection.BUILT_IN && row.section.builtIn) &&
            (section != GitHubInventorySection.GATEWAY || category == null || row.category == category) &&
            row.matches(words)
    }
}

internal fun builtInGitHubInventory(): List<GitHubInventoryRow> {
    val tools = GitHubToolInventory.registered.map { tool ->
        val section = if (tool.name in AppFeatureInventory.gitHubCatalog) GitHubInventorySection.GITHUB else GitHubInventorySection.APP_TOOLS
        GitHubInventoryRow(
            section,
            "tool/${tool.name}",
            gitHubToolTitle(tool.name),
            "Built-in · ${tool.name}",
            listOfNotNull(
                tool.description.takeIf { section == GitHubInventorySection.APP_TOOLS && it.isNotBlank() },
                tool.actions.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Actions · "),
                tool.inputFields.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Inputs · ")
            ),
            terms = listOf(tool.family, tool.description)
        )
    }
    val features = AppFeatureInventory.features.map { feature ->
        GitHubInventoryRow(
            GitHubInventorySection.FEATURES,
            "feature/${feature.id}",
            feature.title,
            feature.group.replace('_', ' '),
            listOfNotNull(
                feature.admission.takeIf(String::isNotBlank),
                feature.tools.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Tools · "),
                feature.plugins.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Plugins · ")
            ),
            feature.terms + feature.id
        )
    }
    return tools.sortedWith(compareBy({ it.section.ordinal }, { it.title })) + features.sortedBy { it.title }
}

private fun gitHubToolTitle(name: String): String = when (name) {
    "github_ci" -> "Checks and builds"
    "github_commit" -> "Commit changes"
    "github_context" -> "Repository context"
    "github_context_inspect" -> "Inspect repository context"
    "github_decide" -> "Task decisions"
    "github_issue" -> "Issues"
    "github_pr" -> "Pull requests"
    "github_pr_inspect" -> "Inspect pull requests"
    "github_read" -> "Read files"
    "github_release" -> "Releases"
    "github_repository" -> "Manage repository"
    "github_search" -> "Search repository"
    "github_stage_patch" -> "Stage a patch"
    "github_watch_checks" -> "Watch checks"
    "github_write" -> "Write files"
    "repository_graph" -> "Repository map"
    "repository_workspace" -> "Repository workspace"
    else -> name.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

internal fun latestGitHubAdmission(chatId: Int?, context: GitHubConversationContext?, runs: List<AgentRun>, observations: List<GitHubToolInventory.Observation>): GitHubToolInventory.Observation? {
    if (chatId == null || context == null) return null
    val scoped = runs.filter { it.chatId == chatId }
    val latestTurn = scoped.maxOfOrNull { it.userMessageId } ?: return null
    val mainRun = scoped.filter { it.userMessageId == latestTurn && it.profileUid == context.mainProfileUid }
        .maxWithOrNull(compareBy({ it.createdAt }, { it.assistantMessageId })) ?: return null
    return observations.firstOrNull { it.runId == mainRun.runId && it.matches(chatId, context) }
}

internal fun mountedGitHubInventory(observation: GitHubToolInventory.Observation?): List<GitHubInventoryRow> = observation?.tools.orEmpty().map { tool ->
    GitHubInventoryRow(
        GitHubInventorySection.RUN,
        "admitted/${tool.name}",
        tool.name,
        "Authorized for this run",
        listOfNotNull(tool.sourceId?.let { "Connection · $it" }, tool.description.takeIf(String::isNotBlank), tool.actions.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Admitted actions · "), tool.inputFields.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Inputs · "))
    )
}

internal fun gatewayInventoryRows(snapshot: GatewayToolInventory): List<GitHubInventoryRow> {
    val sourceRows = snapshot.tools.associateBy { it.sourceId to it.name }
    return GitHubToolInventory.gateway(snapshot).map { tool ->
        val row = sourceRows.getValue(requireNotNull(tool.sourceId) to tool.name)
        val readiness = when {
            tool.enabled == false -> "Disabled"
            tool.authorized == false -> "Access denied"
            tool.executableNow == false -> "Blocked"
            tool.executableNow == true && tool.authorized == true && tool.enabled == true -> "Reported ready"
            else -> "Readiness unverified"
        }
        GitHubInventoryRow(
            GitHubInventorySection.GATEWAY,
            "gateway/${row.sourceId}/${row.id}",
            tool.name,
            "${row.sourceId} · $readiness at observation",
            buildList {
                row.description?.takeIf(String::isNotBlank)?.let(::add)
                val authorization = when (tool.authorized) {
                    true -> "Granted"
                    false -> "Denied"
                    null -> "Unverified"
                }
                add("Discovery authorization · $authorization")
                if (tool.inputFields.isNotEmpty()) add(tool.inputFields.joinToString(prefix = "Inputs · "))
                tool.blockedReason?.takeIf(String::isNotBlank)?.let(::add)
                val metric = row.metrics
                add(
                    if (metric == null || metric.total == 0L) {
                        "Usage and latency not measured."
                    } else {
                        val latency = if (metric.sampleCount == 0L) "latency not measured" else "p50 ${metric.p50Millis} ms · p95 ${metric.p95Millis} ms"
                        "Recorded activity · ${metric.total} calls · ${metric.success} succeeded · ${metric.failure} failed · $latency"
                    }
                )
            },
            terms = listOf(row.category),
            category = row.category
        )
    }
}
