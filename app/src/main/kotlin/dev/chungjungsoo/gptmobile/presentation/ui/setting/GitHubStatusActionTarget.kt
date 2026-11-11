package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatch
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class GitHubStatusActionTarget(
    val headSha: String,
    val pullNumber: Long?,
    val runId: Long?,
    val attempt: Int?,
    val failed: Boolean,
    val draft: Boolean
) {
    val canTrack get() = headSha.matches(Regex("[a-fA-F0-9]{40}")) && (pullNumber != null || runId != null)
    val canFix get() = canTrack && failed && draft && pullNumber != null

    fun watchArguments(owner: String, repo: String) = buildJsonObject {
        put("owner", owner)
        put("repo", repo)
        put("commit_sha", headSha)
        pullNumber?.let { put("pr_number", it) }
        runId?.let { put("run_id", it) }
        put("follow_pr", pullNumber != null && runId == null)
    }

    fun matches(watch: GitHubCheckWatch) =
        watch.pullNumber == pullNumber && watch.runId == runId && watch.headSha.equals(headSha, true)

    companion object {
        fun from(pull: JsonObject?, row: GitHubLiveProcess? = null): GitHubStatusActionTarget {
            val pullHead = (pull?.get("head") as? JsonObject)?.text("sha").orEmpty()
            return GitHubStatusActionTarget(
                headSha = row?.headSha ?: pullHead,
                pullNumber = pull?.text("number")?.toLongOrNull()?.takeIf { it > 0 && (row == null || row.headSha.equals(pullHead, true)) },
                runId = row?.runId?.takeIf { it > 0 },
                attempt = row?.attempt,
                failed = if (row == null) pull?.text("check_state") == "FAILED" else githubCheckState(row.status) == "Failed",
                draft = pull?.flag("draft") == true
            )
        }
    }
}

/** Reconciled by the Status view model before this command can be queued. */
internal data class GitHubDraftRepair(
    val repository: String,
    val pullNumber: Long,
    val headSha: String,
    val sourceBranch: String,
    val baseBranch: String,
    val runId: Long?,
    val attempt: Int?
) {
    val branch: String get() = "fix/pr-$pullNumber-${headSha.take(10)}"
    val key: String get() = "github-draft-repair:$repository:$pullNumber:$headSha"

    fun command(): String = buildString {
        appendLine("Repair the failed checks for draft PR #$pullNumber in $repository at the exact head $headSha on `$sourceBranch`.")
        runId?.let { appendLine("Inspect workflow run $it, attempt ${requireNotNull(attempt)}, and the other failed checks on that revision.") }
        appendLine("Verify the PR, head, failing jobs and logs before editing. If the head has changed, stop and report the new revision.")
        appendLine("Create a separate working branch `$branch` from $headSha in $repository. Implement the minimal repairs and run the relevant tests.")
        appendLine("When the repaired branch is verified, open a new draft pull request into `$baseBranch` with the failure causes, fixes and test results. Link draft #$pullNumber and track the new draft's checks.")
        append("Keep the original draft and its branch intact. Do not merge, force push, or publish a release.")
    }
}
