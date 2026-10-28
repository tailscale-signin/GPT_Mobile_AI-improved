package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ordinary tool trust is never an administrative GitHub authorization. */
internal object GitHubAuthorizationBoundary {
    const val READ_APPROVAL_PREFIX = "GitHub read · safe to retry\n"
    const val EXACT_APPROVAL_PREFIX = "GitHub action · exact approval required\n"

    fun applies(connection: ToolConnection): Boolean = connection.type == "GITHUB" ||
        (
            connection.type == "MCP" &&
                runCatching {
                    val uri = URI(connection.endpointUrl.orEmpty())
                    uri.scheme.equals("https", true) && (uri.host.equals("api.githubcopilot.com", true) || uri.host.equals("api.github.com", true) && uri.path.orEmpty().startsWith("/mcp"))
                }.getOrDefault(false)
            )

    /** Native reviewed reads may be repeated with a fresh invocation after a timeout.
     * This is replay classification, never a grant of permission. */
    fun isReplayableRead(tool: String, arguments: JsonObject): Boolean {
        val native = if (tool == "github") {
            arguments
        } else {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.nativeArguments(tool, arguments) ?: return false
        }
        val action = (native["action"] as? JsonPrimitive)?.content ?: return false
        return action in dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient.readActions ||
            action in setOf(
                "read_saved_result", "search_repositories", "search_issues", "search_code", "get_file_contents",
                "get_issue", "get_pull_request", "list_pull_requests", "list_workflow_runs", "list_workflows",
                "get_workflow_run", "list_workflow_jobs", "list_workflow_artifacts", "get_job_logs"
            )
    }

    /** Selecting a mutable branch changes subsequent engineering destinations. */
    fun isBranchSelection(tool: String, arguments: JsonObject): Boolean {
        val native = if (tool == "github") arguments else dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.nativeArguments(tool, arguments) ?: return false
        val action = (native["action"] as? JsonPrimitive)?.content
        val ref = (native["ref"] as? JsonPrimitive)?.content.orEmpty()
        return action in setOf("resolve_context", "task_context") && ref.isNotBlank() && !ref.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
    }

    fun requiresElevatedAuthorization(tool: String, arguments: JsonObject): Boolean {
        val operation = if (tool == "github") (arguments["action"] as? JsonPrimitive)?.content.orEmpty() else tool.substringAfterLast("__")
        val method = (arguments["method"] as? JsonPrimitive)?.content.orEmpty()
        val action = (arguments["action"] as? JsonPrimitive)?.content.orEmpty()
        val names = listOf(operation, method, action).map { it.lowercase() }
        return names.any { name ->
            if (name.contains("secret")) return@any true
            if (listOf("get_", "list_", "inspect_").any(name::startsWith)) return@any false
            name.contains("collaborator") ||
                name.contains("permission") ||
                name.contains("branch_protection") ||
                name.contains("ruleset") ||
                name in setOf("update_repository", "delete_repository", "transfer_repository", "archive_repository", "edit_repository", "set_repository_settings", "create_repository_variable", "update_repository_variable", "delete_repository_variable", "create_org", "update_org", "delete_org")
        }
    }
}

internal val ToolApproval.requiresExactConfirmation: Boolean
    get() = argumentPreview.startsWith(GitHubAuthorizationBoundary.EXACT_APPROVAL_PREFIX)

internal val ToolApproval.isReplayableGitHubRead: Boolean
    get() = argumentPreview.startsWith(GitHubAuthorizationBoundary.READ_APPROVAL_PREFIX)
