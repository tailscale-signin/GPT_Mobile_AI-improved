package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic workflow hints for common repository tasks. The model receives
 * a short safe plan instead of rediscovering mutation order on every turn.
 */
object GitHubOperationPlanner {
    fun plan(goal: String): JsonArray {
        val normalized = goal.lowercase()
        val wantsRelease = listOf("release", "publish", "tag").any(normalized::contains)
        val wantsMerge = "merge" in normalized
        val wantsWrite = listOf("fix", "change", "implement", "update", "refactor", "merge", "release", "publish").any(normalized::contains)

        return buildJsonArray {
            add(step("resolve_context", "Pin this task to repository, connection, ref, commit and tree; reuse the saved snapshot."))
            add(step("search_repository", "Use the persistent lexical index and explicit continuation until relevant paths are covered."))
            add(step("read_ranges", "Read required ranges and retain evidence handles, paths and immutable blob SHAs."))
            if (listOf("review", "pull request", "pr ").any(normalized::contains)) add(step("pr_context", "Page PR files and checks with expected_head so a moving PR cannot mix revisions."))
            if (listOf("debug", "failure", "ci", "build").any(normalized::contains)) add(step("ci_failure_digest", "Read the latest failed job at the selected branch and inspect failure context throughout the bounded log."))
            if (wantsWrite) {
                add(step("branch_head", "Read the working-branch head immediately before preparing writes."))
                add(step("stage_patch", "Stage exact anchored edits against the original blob SHA and inspect the bounded preview."))
                add(step("commit_staged_patch", "Commit the reviewed stage with its original expected_head_sha; never force-update."))
                add(step("diff", "Inspect the resulting compare data and changed-file statistics."))
                add(step("ci_failure_digest", "Inspect bounded full CI logs for late failures at the new commit, expanding relevant line ranges."))
                add(step("draft_pr", "Create or update a draft pull request for review."))
            }
            if (wantsMerge) add(step("merge_gate", "Merge only after explicit user intent and required checks/reviews pass."))
            if (wantsRelease) {
                add(step("release_status", "Check whether the requested tag/release already exists and identify the repository release workflow."))
                add(step("publish_release", "Use the native publish_release action. Prefer the repository release workflow; use direct release creation only when no release workflow applies."))
                add(step("release_verify", "Call release_status after publication to verify the workflow result, final release and assets."))
            }
        }
    }

    private fun step(id: String, description: String) = buildJsonObject {
        put("id", id)
        put("description", description)
    }
}
