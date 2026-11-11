package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Reconciled by the Status view model before this command can be queued. */
@Serializable
data class GitHubDraftRepair(
    val repository: String,
    @SerialName("pull_number") val pullNumber: Long,
    @SerialName("head_sha") val headSha: String,
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("base_branch") val baseBranch: String,
    @SerialName("run_id") val runId: Long? = null,
    val attempt: Int? = null
) {
    val branch: String get() = "fix/pr-$pullNumber-${headSha.take(10)}" + (runId?.let { "-$it-a$attempt" } ?: "")
    init {
        require(repository.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")) && pullNumber > 0)
        require(headSha.matches(Regex("[a-fA-F0-9]{40}")) && headSha.any { it != '0' })
        require(listOf(sourceBranch, baseBranch).all { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) })
        require(sourceBranch != baseBranch && (runId == null && attempt == null || runId != null && runId > 0 && attempt != null && attempt > 0))
    }

    val key: String get() = "github-draft-repair:$repository:$pullNumber:$headSha:${runId ?: 0}:${attempt ?: 0}"
    val title: String get() = ("Repair draft #$pullNumber · ${headSha.take(10)}" + (runId?.let { " · $it/$attempt" } ?: "")).take(50)

    fun bind(context: GitHubConversationContext): GitHubConversationContext {
        require(repository.equals("${context.owner}/${context.repo}", ignoreCase = true)) { "The repair belongs to a different repository." }
        return context.copy(ref = sourceBranch, commitSha = headSha, prepareIndex = false, stagedFileCount = null, recentCommits = emptyList(), observedAtMillis = null)
    }

    fun requireSource(context: GitHubConversationContext) {
        require(context.sameWorkspace(bind(context)) && context.commitSha.equals(headSha, true)) { "The repair's saved source branch or revision changed." }
    }

    fun command(): String = buildString {
        appendLine("Repair the failed checks for draft PR #$pullNumber in $repository at the exact head $headSha on `$sourceBranch`.")
        runId?.let { appendLine("Inspect workflow run $it, attempt ${requireNotNull(attempt)}, and the other failed checks on that revision.") }
        appendLine("Verify the PR, head, failing jobs and logs before editing. If the head has changed, stop and report the new revision.")
        appendLine("Create a separate working branch `$branch` from $headSha in $repository. Implement the minimal repairs and run the relevant tests.")
        appendLine("When the repaired branch is verified, open a new draft pull request into `$baseBranch` with the failure causes, fixes and test results. Link draft #$pullNumber and track the new draft's checks.")
        append("Keep the original draft and its branch intact. Do not merge, force push, or publish a release.")
    }
}
