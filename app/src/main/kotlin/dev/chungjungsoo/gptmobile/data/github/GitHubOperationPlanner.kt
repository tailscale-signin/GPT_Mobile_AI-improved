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
        val lifecycle = GitHubRepositoryLifecycle.intent(goal)

        return buildJsonArray {
            if (lifecycle != null) {
                add(step("repository_identity", "Confirm the intended GitHub account and explicit source/destination names, owner_type and visibility. Obtain missing required inputs before submitting the operation."))
                add(lifecycleStep("repository_lifecycle_capabilities", "capabilities", "Inspect supported repository operations. History import and local clone require a qualified online gateway with Git and the selected account."))
                if (lifecycle != "create_repository") add(step("repository_source_revision", "Keep source_owner, source_repo and source_ref separate from the destination. Pin the source commit before copying; retain that immutable revision across retries."))
                if (lifecycle == "import_repository") add(step("repository_copy_mode", "Use the explicitly requested copy_mode: snapshot copies one complete tree; history preserves the requested Git history, branches and tags through the gateway. Resolve a missing choice before creating the destination."))
                add(lifecycleStep(lifecycle, lifecycle.removeSuffix("_repository"), "Submit the exact repository operation and retain its creation receipt or gateway operation_id. An interrupted response requires reconciliation through that same retained identity."))
                add(lifecycleStep("repository_lifecycle_status", "status", "Verify this conversation's retained expected_repository_id and original operation, or gateway operation_id. Check destination identity and visibility; imports require the exact source tree or requested history, and clones require the pinned checked-out SHA and workspace ID. PENDING is incomplete."))
                if (!GitHubRepositoryAcceptance.requiresAdditionalEvidence(goal)) return@buildJsonArray
                add(
                    buildJsonObject {
                        put("id", "select_verified_repository")
                        put("user_action", "CHANGE_REPOSITORY")
                        put("user_input_required", true)
                        put("automatic_dispatch", false)
                        put("description", "After verifying creation or transfer, stop at this selection boundary. Use Change repository to select the verified destination and exact connection/ref, then resume the saved implementation request. The creation receipt and unfinished requirements remain retained. This step is a user action, not a tool operation; resolve_context cannot switch a task away from its selected destination. Implementation, configuration and test evidence are still required after selection.")
                    }
                )
                return@buildJsonArray
            }
            add(step("resolve_context", "Pin this task to repository, connection, ref, commit and tree; reuse the saved snapshot."))
            if (GitHubReviewComparison.matches(goal)) add(step("review_comparison", GitHubReviewComparison.INSTRUCTION))
            add(step("search_repository", "Use the persistent lexical index and explicit continuation until relevant paths are covered."))
            add(step("read_ranges", "Load a bounded multi-file bundle concurrently, inspect its revision-bound import graph and related tests, and retain evidence handles and immutable blob SHAs. Lexical hints do not establish compiler resolution."))
            if (listOf("review", "pull request", "pr ").any(normalized::contains)) add(step("pr_context", "Page PR files and checks with expected_head so a moving PR cannot mix revisions."))
            if (listOf("debug", "failure", "ci", "build").any(normalized::contains)) add(step("ci_failure_digest", "Read the latest failed job at the selected branch and inspect failure context throughout the bounded log."))
            if (wantsWrite) {
                add(step("branch_head", "Read the working-branch head immediately before preparing writes."))
                add(step("stage_patch", "Stage exact anchored edits against the original blob SHA and inspect the bounded preview."))
                add(step("validation", "Inspect added-text findings and related test-generation context. Add behavior, boundary and recovery regressions where warranted; run the repository lint, compiler, tests and static checks. NOT_RUN is not validation success."))
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

    private fun lifecycleStep(id: String, action: String, description: String) = buildJsonObject {
        put("id", id)
        put("tool", "github_repository")
        put("action", action)
        put("description", description)
    }
}
