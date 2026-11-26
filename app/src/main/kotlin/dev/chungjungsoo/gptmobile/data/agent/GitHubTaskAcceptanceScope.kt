package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubLookupRoute
import dev.chungjungsoo.gptmobile.data.github.GitHubPlanningContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext

/** One request's target for planning, schema selection and evidence. Execution checks live grants separately. */
internal data class GitHubTaskScope(
    val requiresAcceptance: Boolean,
    val repository: GitHubRepositoryContext?,
    val connectionUid: String?,
    val observedCommitSha: String?,
    val targetCount: Int
) {
    val usesRepositoryIntelligence get() = repository != null

    fun planningPreferences(saved: GitHubChatProfileOverride?): GitHubChatProfileOverride? =
        if (usesRepositoryIntelligence) saved ?: GitHubChatProfileOverride.newProfile else null

    companion object {
        fun fromTools(request: String, workspace: GitHubConversationContext?, tools: List<ResolvedAgentTool>): GitHubTaskScope {
            val native = tools.filter { it.nativeGitHub }
            return select(request, workspace, native.mapNotNull { it.gitHubPlanningContext }, native.isNotEmpty())
        }

        fun select(request: String, workspace: GitHubConversationContext?, contexts: List<GitHubPlanningContext>, hasNativeTools: Boolean): GitHubTaskScope {
            val targets = contexts.filter { it.repository != null && it.connectionUid.isNotBlank() }.distinctBy { it.connectionUid to it.repository }
            val count = if (workspace != null) 1 else targets.size
            val lookup = hasNativeTools && count > 0 && GitHubLookupRoute.from(request) != null
            val required = lookup || requiresNativeGitHubAcceptance(request, hasNativeTools)
            val inferred = targets.singleOrNull()?.takeIf { required && hasNativeTools }
            val head = workspace?.commitSha ?: inferred?.let { target ->
                contexts.filter { it.connectionUid == target.connectionUid && it.repository == target.repository }
                    .mapNotNull { it.observedCommitSha }.distinct().singleOrNull()
            }
            return GitHubTaskScope(required, workspace?.repository ?: inferred?.repository, workspace?.connectionUid ?: inferred?.connectionUid, head, count)
        }
    }
}

/** Availability alone cannot turn unrelated work or account discovery into a repository task. */
internal fun requiresNativeGitHubAcceptance(request: String, hasNativeTools: Boolean): Boolean {
    if (CurrentRequestContract.from(request).conversational || dev.chungjungsoo.gptmobile.data.github.GitHubCapabilityRequest.from(request) != null) return false
    val explicitGitHub = Regex("(?i)(?<![A-Za-z0-9])github(?:_[A-Za-z0-9_]+)?(?![A-Za-z0-9])").containsMatchIn(request)
    if (!hasNativeTools && !explicitGitHub) return false
    if (dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryLifecycle.intent(request) != null) return true
    val repositoryEntity = Regex("(?i)\\b(?:repos?(?:itory|itories)?|repositories|codebase|pull requests?)\\b|\\b[A-Za-z0-9_.-]+\\.(?:kt|java|py|js|ts|tsx|yml|yaml|json|xml)\\b").containsMatchIn(request)
    val repositoryScope = explicitGitHub || repositoryEntity
    val repositoryRead = repositoryEntity &&
        Regex("(?i)^(?:please\\s+)?(?:read|inspect|review|audit|show|list|fetch|open|check|verify|summari[sz]e|compare)\\b").containsMatchIn(request.trim()) &&
        !Regex("(?i)^(?:please\\s+)?(?:show(?: me)?|review|compare)\\s+(?:how|why|what)\\b").containsMatchIn(request.trim()) &&
        !Regex("(?i)\\b(?:my|accessible|available)\\s+(?:github\\s+)?(?:repos|repositories)\\b|\\brepositories\\s+(?:I|we)\\s+(?:can|have)\\b").containsMatchIn(request)
    return repositoryScope &&
        (
            dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(request) ||
                dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion.requiresRepositoryEvidence(request) ||
                repositoryRead ||
                explicitGitHub &&
                Regex("(?i)^(?:please\\s+)?(?:call|invoke|use|execute)\\b").containsMatchIn(request.trim())
            )
}

/** Decide before dispatch: an unbound task cannot later acquire repository acceptance. */
internal fun requireNativeGitHubTarget(required: Boolean, targetCount: Int, request: String = "") {
    // Creation/import selects a destination in its own schema. Requiring an
    // already accessible open repository would prevent that workflow entirely.
    if (!required || targetCount == 1 || dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryLifecycle.intent(request) != null) return
    throw GitHubTargetSelectionRequired(targetCount)
}

internal class GitHubTargetSelectionRequired(targetCount: Int) :
    IllegalStateException(
        if (targetCount == 0) {
            "GITHUB_REPOSITORY_TARGET_REQUIRED: Select the exact repository and connection for this task before dependent work. No repository operation was dispatched."
        } else {
            "GITHUB_REPOSITORY_TARGET_AMBIGUOUS: More than one repository target is selected. Select the exact repository and connection for this task before dependent work. No repository operation was dispatched."
        }
    )
