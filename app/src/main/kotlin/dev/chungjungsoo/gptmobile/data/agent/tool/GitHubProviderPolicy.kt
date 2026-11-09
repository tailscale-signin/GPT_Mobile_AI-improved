package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.RequiredToolReadinessFailure
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.net.URI
import kotlinx.coroutines.CancellationException

/** Applied after authorization, both to initial catalogs and to cold additions. */
internal object GitHubProviderPolicy {
    fun select(tools: List<ResolvedAgentTool>, nativeAlternatives: List<ResolvedAgentTool>, connections: List<ToolConnection>, githubMode: Boolean = false): List<ResolvedAgentTool> {
        // These tools have already passed connection, selection and data grants.
        // A preferred provider must not hide authorized GitHub schemas in this mode.
        if (githubMode) return tools
        val native = (tools + nativeAlternatives).filter { it.nativeGitHub }
        val authenticated = native.filter { it.nativeGitHubAuthenticated }.mapNotNull { GitHubOperationTool.operationName(it.realToolName) }.toSet()
        val official = connections.filter(::isOfficialGitHub).map { it.connectionUid }.toSet()
        val remote = tools.filter { it.connectionUid in official && !it.nativeGitHub }.mapNotNull { equivalent(it.realToolName) }.toSet()
        return tools.filterForToolPolicy("EQUIVALENT_GITHUB_PROVIDER_SELECTED") { tool ->
            when {
                tool.nativeGitHub && !tool.nativeGitHubAuthenticated -> GitHubOperationTool.operationName(tool.realToolName) !in remote - authenticated
                tool.connectionUid in official -> equivalent(tool.realToolName) !in authenticated
                else -> true // Exact PC gateway bindings and other authorized providers are independent.
            }
        }
    }

    fun isOfficialGitHub(connection: ToolConnection): Boolean = connection.type == ToolConnectionType.MCP &&
        runCatching {
            val uri = URI(connection.endpointUrl)
            uri.scheme.equals("https", true) &&
                (uri.host.equals("api.githubcopilot.com", true) || uri.host.equals("api.github.com", true) && uri.path.orEmpty().startsWith("/mcp"))
        }.getOrDefault(false)

    /** Configured endpoint provenance survives arbitrary aliases and name truncation. */
    fun definition(connection: ToolConnection, definition: AgentToolDefinition): AgentToolDefinition =
        if (isOfficialGitHub(connection)) definition.copy(capabilities = definition.capabilities + AgentToolCapability.GITHUB) else definition

    /** Full GitHub mode waits for every selected schema before any model round. */
    suspend fun acquireCatalog(
        connection: ToolConnection,
        selectedNames: Set<String>,
        githubMode: Boolean,
        cachedOnly: Boolean,
        cached: () -> List<Tool>,
        discover: suspend (awaitCompletion: Boolean) -> List<Tool>
    ): List<Tool> {
        val required = githubMode && isOfficialGitHub(connection)
        val tools = try {
            if (cachedOnly && !required) cached() else discover(required)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!required) throw failure
            throw RequiredToolReadinessFailure(
                if (failure is McpCatalogueTimeoutException) "ACQUISITION_TIMED_OUT" else "ACQUISITION_FAILED",
                namespaceMcpToolName(connection.alias, selectedNames.sorted().firstOrNull() ?: "catalog"),
                "The full authorized GitHub catalog could not be loaded. Refresh this connection before continuing."
            ).apply { initCause(failure) }
        }
        if (required) {
            (selectedNames - tools.map { it.name }.toSet()).sorted().firstOrNull()?.let { missing ->
                throw RequiredToolReadinessFailure(
                    "CAPABILITY_UNAVAILABLE",
                    namespaceMcpToolName(connection.alias, missing),
                    "This selected GitHub tool is absent from the completed server catalog. Refresh its connection or update the selected tool before continuing."
                )
            }
        }
        return tools
    }

    private fun equivalent(name: String): String? = GitHubOperationTool.operationName(name) ?: when (name.substringAfterLast("__")) {
        "get_file_contents", "read_code", "read_ranges" -> "github_read"
        "search_code", "search_repository", "find_symbol", "find_references", "find_tests" -> "github_search"
        "resolve_context", "repo_status", "get_repository", "index_status" -> "github_context"
        "get_pull_request", "pull_request_read", "list_pull_requests", "get_pull_request_comments", "create_pull_request", "update_pull_request", "create_pull_request_review", "reply_pull_request_comment", "merge_pull_request" -> "github_pr"
        "workflow_catalog", "list_workflows", "list_workflow_runs", "get_workflow_run", "list_workflow_jobs", "list_workflow_artifacts", "get_job_logs", "ci_failure_digest", "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow" -> "github_ci"
        "create_branch", "push_files", "commit_files", "commit_staged_patch" -> "github_commit"
        "get_latest_release", "list_releases", "publish_release" -> "github_release"
        else -> null
    }
}
