package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GitHubLiveStatus

internal fun githubCheckState(status: String): String = when (status.lowercase()) {
    "success", "passed" -> "Passed"
    "failure", "failed", "timed_out", "action_required", "startup_failure" -> "Failed"
    "in_progress", "running", "queued", "waiting", "pending", "requested" -> "Running"
    "skipped" -> "Skipped"
    "cancelled", "canceled" -> "Cancelled"
    else -> "Unknown"
}

internal data class GitHubStatusSummary(
    val headline: String,
    val badge: String,
    val passed: Int,
    val running: Int,
    val failed: Int,
    val other: Int,
    val measured: Boolean,
    val stale: Boolean
)

/** Counts describe observed workflow runs, never expanded jobs or merge approval. */
internal fun gitHubStatusSummary(state: GitHubLiveStatus, now: Long): GitHubStatusSummary {
    val counts = state.rows.filter { it.parentId == null }.groupingBy { githubCheckState(it.status) }.eachCount()
    val passed = counts["Passed"] ?: 0
    val running = counts["Running"] ?: 0
    val failed = counts["Failed"] ?: 0
    val other = counts.filterKeys { it !in setOf("Passed", "Running", "Failed") }.values.sum()
    val measured = state.checkedAt != null && state.phase != "LOADING"
    val stale = state.phase in setOf("STALE", "OFFLINE", "AUTHORIZATION") || measured && state.checkedAt?.let { now - it > 60_000 } == true
    val headline = when {
        state.phase == "AUTHORIZATION" -> "Reconnect GitHub"
        !measured && state.phase == "LOADING" -> "Checking your repository"
        !measured -> "Waiting for GitHub"
        stale -> "Refresh needed"
        failed > 0 -> "Checks need attention"
        running > 0 -> "Checks are running"
        other > 0 -> "Review check results"
        passed > 0 -> "Loaded checks passed"
        else -> "No checks to show"
    }
    val badge = when {
        stale && measured -> "Saved"
        stale -> "Unavailable"
        measured -> "Updated"
        else -> "Checking"
    }
    return GitHubStatusSummary(headline, badge, passed, running, failed, other, measured, stale)
}
