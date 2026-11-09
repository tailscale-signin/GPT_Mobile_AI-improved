package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Prevents a model's invented target from replacing the user's selection; grants are checked elsewhere. */
internal class GitHubTargetPolicy(private val request: String?) {
    private val discovered = linkedSetOf<String>()

    fun suppliedRead(action: String, target: String): Boolean = isReadAction(action) && explicitlySupplied(target)

    @Synchronized fun rejection(action: String, arguments: JsonObject, selected: GitHubRepositoryContext?): JsonObject? {
        if (request == null || action in ACCOUNT_ACTIONS || action in GitHubRepositoryLifecycle.actions) return null
        val owner = arguments.text("owner")
        val repo = arguments.text("repo")
        val target = if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else null
        val code = when {
            target == null -> "REPOSITORY_TARGET_REQUIRED"
            selected != null && target.equals(selected.fullName, true) -> return null
            target.lowercase(Locale.ROOT) in discovered -> return null
            explicitlySupplied(target) -> return null
            else -> "REPOSITORY_TARGET_UNVERIFIED"
        }
        return buildJsonObject {
            put("schema", "github.target-admission.v1")
            put("error_code", code)
            put("dispatched", false)
            target?.let { put("requested_repository", it) }
            selected?.let { put("selected_repository", it.fullName) }
            put("message", if (target == null) "Select the exact repository or use authorized repository discovery before this operation. No repository was guessed and no request was sent." else "This target was not selected, supplied by the user or observed through authorized repository discovery. Use the selected target or ask for the intended repository; never invent a test repository or reinterpret a workflow preset as a target. No request was sent.")
            put("next_action", if (selected == null) "Select a repository in GitHub settings, or use github_context action=repositories with the user's search criteria." else "Use ${selected.fullName} with omitted owner/repo, or explicitly select the intended repository before continuing.")
        }
    }

    /** Only repository-discovery response fields count, never arbitrary source/log text. */
    @Synchronized fun observeDiscovery(action: String, value: JsonElement?) {
        if (action !in REPOSITORY_DISCOVERY) return
        val root = value as? JsonObject ?: return
        val rows = (root["items"] ?: root["repositories"]) as? JsonArray ?: return
        rows.filterIsInstance<JsonObject>().forEach { row ->
            val fullName = row.text("full_name")
            val parsed = runCatching { normalizeGitHubRepository(null, fullName) }.getOrNull()
            if (parsed?.first != null && parsed.second != null) discovered += "${parsed.first}/${parsed.second}".lowercase(Locale.ROOT)
        }
        while (discovered.size > 512) discovered.remove(discovered.first())
    }

    private fun explicitlySupplied(target: String): Boolean = target.lowercase(Locale.ROOT) in readSources(request.orEmpty())

    companion object {
        private val REPOSITORY_DISCOVERY = setOf("search_repositories", "list_repositories", "list_installation_repositories")
        private val ACCOUNT_ACTIONS = REPOSITORY_DISCOVERY + setOf("plan_change", "rate_limit_status", "efficiency", "get_account", "list_installations", "search_issues", "search_code")
        internal fun isAccountAction(action: String): Boolean = action in ACCOUNT_ACTIONS
        internal fun isReadAction(action: String): Boolean = action in GitHubWorkspaceClient.readActions ||
            action in setOf(
                "get_file_contents", "get_issue", "get_pull_request", "list_pull_requests", "list_workflow_runs", "list_workflows",
                "get_workflow_run", "list_workflow_jobs", "get_workflow_job", "list_workflow_artifacts", "get_job_logs"
            )

        /** Exact user-supplied repository references are read sources, never implicit write grants. */
        internal fun readSources(request: String): Set<String> = sequenceOf(
            // A GitHub URL may include /tree, /blob or an issue path after the repository.
            Regex("(?<![A-Za-z0-9_./:-])https://github\\.com/([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9_.-]+)(?![A-Za-z0-9_.-])", RegexOption.IGNORE_CASE),
            // A bare owner/repo must be a complete token, never the prefix of a file path.
            Regex("(?<![A-Za-z0-9_./:-])([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9_.-]+)(?![A-Za-z0-9_./-])")
        ).flatMap { it.findAll(request) }.mapNotNull { match ->
            val parsed = runCatching { normalizeGitHubRepository(match.groupValues[1], match.groupValues[2]) }.getOrNull()
            if (parsed?.first != null && parsed.second != null) "${parsed.first}/${parsed.second}".lowercase(Locale.ROOT) else null
        }.take(32).toSet()
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
