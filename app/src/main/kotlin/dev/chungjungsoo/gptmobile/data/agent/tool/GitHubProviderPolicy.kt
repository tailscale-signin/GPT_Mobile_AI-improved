package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import java.net.URI

/** Applied after authorization, both to initial catalogs and to cold additions. */
internal object GitHubProviderPolicy {
    fun select(tools: List<ResolvedAgentTool>, nativeAlternatives: List<ResolvedAgentTool>, connections: List<ToolConnection>): List<ResolvedAgentTool> {
        val native = (tools + nativeAlternatives).filter { it.nativeGitHub }
        val authenticated = native.filter { it.nativeGitHubAuthenticated }.mapNotNull { GitHubOperationTool.operationName(it.realToolName) }.toSet()
        val official = connections.filter { connection ->
            connection.type == ToolConnectionType.MCP && runCatching {
                val uri = URI(connection.endpointUrl)
                uri.host.equals("api.githubcopilot.com", true) || uri.host.equals("api.github.com", true) && uri.path.orEmpty().startsWith("/mcp")
            }.getOrDefault(false)
        }.map { it.connectionUid }.toSet()
        val remote = tools.filter { it.connectionUid in official && !it.nativeGitHub }.mapNotNull { equivalent(it.realToolName) }.toSet()
        return tools.filter { tool ->
            when {
                tool.nativeGitHub && !tool.nativeGitHubAuthenticated -> GitHubOperationTool.operationName(tool.realToolName) !in remote - authenticated
                tool.connectionUid in official -> equivalent(tool.realToolName) !in authenticated
                else -> true // Exact PC gateway bindings and other authorized providers are independent.
            }
        }
    }

    private fun equivalent(name: String): String? = GitHubOperationTool.operationName(name) ?: when (name.substringAfterLast("__")) {
        "get_file_contents", "read_code", "read_ranges" -> "github_read"
        "search_code", "search_repository", "find_symbol", "find_references", "find_tests" -> "github_search"
        "resolve_context", "repo_status", "get_repository", "index_status" -> "github_context"
        "get_pull_request", "pull_request_read", "list_pull_requests", "create_pull_request", "update_pull_request", "merge_pull_request" -> "github_pr"
        "list_workflows", "list_workflow_runs", "get_workflow_run", "get_job_logs", "ci_failure_digest" -> "github_ci"
        "create_branch", "push_files", "commit_files", "commit_staged_patch" -> "github_commit"
        "get_latest_release", "list_releases", "publish_release" -> "github_release"
        else -> null
    }
}
