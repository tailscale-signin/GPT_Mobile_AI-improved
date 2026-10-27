package dev.chungjungsoo.gptmobile.data.permissions

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ordinary tool trust is never an administrative GitHub authorization. */
internal object GitHubAuthorizationBoundary {
    const val EXACT_APPROVAL_PREFIX = "GitHub action · exact approval required\n"

    fun applies(connection: ToolConnection): Boolean = connection.type == "GITHUB" ||
        (
            connection.type == "MCP" &&
                runCatching {
                    val uri = URI(connection.endpointUrl.orEmpty())
                    uri.scheme == "https" && (uri.host == "api.githubcopilot.com" || uri.host == "api.github.com" && uri.path.orEmpty().startsWith("/mcp"))
                }.getOrDefault(false)
            )

    fun requiresElevatedAuthorization(tool: String, arguments: JsonObject): Boolean {
        val operation = if (tool == "github") (arguments["action"] as? JsonPrimitive)?.content.orEmpty() else tool.substringAfterLast("__")
        val method = (arguments["method"] as? JsonPrimitive)?.content.orEmpty()
        val names = listOf(operation, method).map { it.lowercase() }
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
