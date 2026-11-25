package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Identity of the upstream collection query, independent of display and page controls. */
internal object GitHubInventoryQuery {
    private val fullSha = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")

    fun commitSha(value: JsonElement?): JsonPrimitive? =
        (value as? JsonPrimitive)?.takeIf { it.isString && it.content.matches(fullSha) }?.let { JsonPrimitive(it.content.lowercase(Locale.ROOT)) }

    /** These endpoints address a run, an explicit branch, or a repository search independently of the selected ref. */
    fun ignoresWorkspaceRef(tool: String, action: String?): Boolean = when (tool.githubOperationFamily()) {
        "github_ci" -> action in setOf("jobs", "list_workflow_jobs", "artifacts", "list_workflow_artifacts")
        "github_context" -> action in setOf("branch_dependencies", "commit_search", "search_commits")
        else -> false
    }

    fun arguments(tool: String, arguments: JsonObject, observedCommit: JsonElement? = null): JsonObject {
        val action = (arguments["action"] as? JsonPrimitive)?.content
        val family = tool.githubOperationFamily()
        val ci = family == "github_ci"
        val commits = family == "github_context" && action in setOf("commits", "list_commits")
        val pulls = family == "github_pr" && action in setOf("list", "list_pull_requests")
        val workflows = ci && action in setOf("workflows", "list_workflows")
        val runs = ci && action in setOf("runs", "list_workflow_runs")
        val issues = family == "github_issue" && action in setOf("list", "list_issues")
        val ignored = setOf("page", "response_detail", "verify_live") + when {
            workflows -> setOf("ref", "commit_sha")
            // The runs endpoint filters on branch/head_sha; ref is workspace context.
            runs -> setOf("ref", "commit_sha")
            // An opaque issue after cursor advances one remote collection, not a new query.
            issues -> setOf("after")
            ignoresWorkspaceRef(tool, action) -> setOf("ref")
            else -> emptySet()
        }
        val normalized = arguments.filterKeys { it !in ignored }.toMutableMap()
        if (workflows || runs) {
            normalized["action"] = JsonPrimitive(if (workflows) "workflows" else "runs")
            normalized.putIfAbsent("per_page", JsonPrimitive(if (workflows) 30 else 100))
        }
        if (pulls) {
            normalized["action"] = JsonPrimitive("list_pull_requests")
            normalized.putIfAbsent("state", JsonPrimitive("all"))
            normalized.putIfAbsent("per_page", JsonPrimitive(20))
            normalized.putIfAbsent("sort", JsonPrimitive("created"))
            normalized.putIfAbsent("direction", JsonPrimitive(GitHubPullRequestQuery.defaultDirection((normalized["sort"] as? JsonPrimitive)?.content.orEmpty())))
        }
        if (commits) {
            normalized["action"] = JsonPrimitive("list_commits")
            normalized.putIfAbsent("per_page", JsonPrimitive(30))
            val selector = listOf("commit_sha", "ref", "branch").firstNotNullOfOrNull { key ->
                (arguments[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
            }
            val requested = commitSha(selector)
            val observed = commitSha(observedCommit)
            for (key in listOf("commit_sha", "ref", "branch")) normalized.remove(key)
            val revision = observed ?: requested
            if (revision != null) {
                normalized["commit_sha"] = revision
                if (requested != null && requested != revision) normalized["ref"] = requested
            } else if (selector != null) {
                normalized["ref"] = selector
            }
        }
        if (issues) {
            // The authenticated cursor and omitted REST defaults must not split one issue traversal.
            normalized["action"] = JsonPrimitive("list_issues")
            normalized.putIfAbsent("state", JsonPrimitive("open"))
            normalized.putIfAbsent("per_page", JsonPrimitive(30))
            normalized.putIfAbsent("sort", JsonPrimitive("created"))
            normalized.putIfAbsent("direction", JsonPrimitive("asc"))
        }
        if (runs) (arguments["commit_sha"] as? JsonPrimitive)?.takeIf { it.content.isNotBlank() }?.let { normalized["head_sha"] = it }
        for (key in listOf("owner", "repo")) {
            (normalized[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { normalized[key] = JsonPrimitive(it.content.lowercase(Locale.ROOT)) }
        }
        return JsonObject(normalized.toSortedMap())
    }
}
