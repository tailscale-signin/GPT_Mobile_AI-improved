package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.Serializable

/** Frozen workspace destination; it grants no credential, tool or write permission. */
@Serializable
data class GitHubConversationContext(
    val connectionUid: String,
    val owner: String,
    val repo: String,
    val ref: String,
    val commitSha: String,
    val mainProfileUid: String,
    val prepareIndex: Boolean = false
) {
    init {
        require(connectionUid.isNotBlank() && mainProfileUid.isNotBlank())
        val normalized = normalizeGitHubRepository(owner, repo)
        require(normalized.first == owner && normalized.second == repo)
        require(ref.isNotBlank() && commitSha.matches(Regex("[a-fA-F0-9]{40}")))
    }

    /** Request-local scope only; this does not grant tools, credentials or write access. */
    fun promptContext(): String = "GitHub workspace: $owner/$repo\nBranch: $ref\nWork within this selected repository. Inspect current source and report actions only from tool results."

    val repository: GitHubRepositoryContext get() = GitHubRepositoryContext(owner, repo, ref)
    fun sameWorkspace(other: GitHubConversationContext) = connectionUid == other.connectionUid && owner.equals(other.owner, true) && repo.equals(other.repo, true) && ref == other.ref && mainProfileUid == other.mainProfileUid
}
