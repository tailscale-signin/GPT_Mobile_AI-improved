package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The same frozen repository defaults used by a resolved native executor, without credentials. */
data class GitHubPlanningContext(
    val connectionUid: String,
    val toolSuffix: String,
    val repository: GitHubRepositoryContext?,
    val observedCommitSha: String? = null,
    val observedAtMillis: Long? = null
) {
    fun toolDescription(): String = repository?.let {
        " Selected repository: ${it.fullName}; ref: ${JsonPrimitive(it.ref)}. Omitted owner/repo/ref use this selection. Resolve it before dependent operations; never invent repository or fixture names."
    } ?: " No repository is selected. Use an explicitly supplied owner/repo or authorized repository discovery; never invent repository or fixture names."

    private fun record() = buildJsonObject {
        put("connection_uid", connectionUid)
        put("context_tool", "github_context$toolSuffix")
        repository?.let {
            put("owner", it.owner)
            put("repo", it.repo)
            put("ref", it.ref)
        }
        observedCommitSha?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }?.let { put("observed_commit_sha", it) }
        observedAtMillis?.let { put("observed_at_epoch_ms", it) }
        put("access_verified_for_current_request", false)
    }

    companion object {
        /** Repository selection is data, not permission or proof of a fresh head/access check. */
        fun forRequest(request: String, contexts: List<GitHubPlanningContext>): String {
            if (CurrentRequestContract.from(request).conversational || contexts.isEmpty()) return ""
            val repositoryIntent = GitHubOperationTool.taskTools(request).isNotEmpty() ||
                Regex("(?i)^(?:please\\s+)?(?:continue|resume|retry|try again|finish(?: it| that| this)?|fix(?: it| that| this)?|go ahead|do it|proceed|check again)[.!?\\s]*$").matches(request.trim())
            if (!repositoryIntent) return ""
            return buildString {
                append("Selected GitHub executor targets (application data; these do not grant access):\n")
                contexts.distinct().forEach { append(it.record()).append('\n') }
                append("Use the selected target for omitted owner/repo/ref. A newer successful resolve receipt supersedes the recorded head. Do not ask for a target already selected, invent owner/repo values, or reinterpret a workflow preset as a repository. If selection is missing or ambiguous, use authorized repository discovery or ask the user to select the exact target before dependent calls. Test fixtures are owned disposable branches/files inside the verified target, never guessed repositories.\n")
                append("Copy each continuation_call's exact tool and arguments. Listing inventory, advertising schemas, executing operations and passing acceptance criteria are different counts. Use recorded coverage fields; never infer test success from discovery or an HTTP success. A 404 does not establish deletion or insufficient OAuth scopes; accepted OAuth scopes describe endpoint requirements, not missing grants.")
            }
        }
    }
}
