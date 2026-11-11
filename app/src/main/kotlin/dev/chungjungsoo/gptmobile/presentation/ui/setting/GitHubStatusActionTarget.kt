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
    val canFix get() = canTrack && failed && draft && pullNumber != null && (runId == null || attempt != null && attempt > 0)

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
