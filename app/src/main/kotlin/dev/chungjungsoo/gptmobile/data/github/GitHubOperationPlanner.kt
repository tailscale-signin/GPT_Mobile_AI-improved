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
        // A noun such as "release notes" or "staging" does not request publication.
        // Scope each verb to its own clause so "fix the bug but do not merge" keeps the fix.
        val negated = Regex("""^(?:(?:please|also)\s+)*(?:do not|don['’]t|never|without|no)\b""")
        val clauses = normalized.split(Regex("""[.!?;\n]|\b(?:then|but|and)\b""")).flatMap { clause ->
            clause.split(',').map(String::trim).takeWhile { !negated.containsMatchIn(it) }
        }
        val readOnly = GitHubChecklistScope.readOnly(goal)
        fun requested(verbs: String): String? {
            if (readOnly) return null
            val verb = Regex("""^(?:(?:please|also|now|finally)\s+|(?:can|could|would) you\s+|i (?:want|need) you to\s+)*(?:$verbs)\b""")
            return clauses.firstNotNullOfOrNull { verb.find(it)?.value }
        }
        fun requests(verbs: String) = requested(verbs) != null
        val wantsRelease = requests("""publish(?: (?:it|(?:(?:this|the|a|our|an?) (?:signed |new )?)?release|(?:(?:the|this) )?version|v?\d)|$)|release(?! notes\b)|tag""") &&
            !Regex("""(?i)\b(?:do not|don't|never|without) (?:publish|publishing|release|releasing|tag|tagging)\b""").containsMatchIn(goal)
        val prTarget = """(?:(?:new|draft)\s+)*(?:pr|pull request)(?:\s+draft)?(?!\s*(?:#|\d)|\s+(?:review|comment|file|details)\b)"""
        val requestedPr = requested("""(?:create|raise|prepare|submit)\s+(?:(?:a|the)\s+)?$prTarget|open\s+(?:a\s+|new\s+|draft\s+)$prTarget|push(?:\s+(?:it|this|them|the (?:changes|branch)))?\s+as\s+(?:a\s+)?$prTarget""")
        val prStep = requestedPr?.let {
            if (Regex("""\b(?:draft (?:pr|pull request)|(?:pr|pull request) draft)\b""").containsMatchIn(it)) "draft_pr" else "create_pr"
        }
        val wantsMerge = requests("""merge(?![\s-]+(?:polic(?:y|ies)|strateg(?:y|ies)|conflicts?|checks?|queue|status)\b)""")
        val wantsWrite = requests("fix|repair|patch|change|implement|update|refactor|add|stage")
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
            if (GitHubStatusRequest.matches(goal)) {
                add(step("repository_status", "Read the current branch and exact head, open issue/PR counts and latest workflow observation."))
                add(step("head_checks", "Read checks for that exact head. Keep skipped, pending, failed and successful checks distinct; retain unknown required-check policy."))
                add(step("recent_activity", "Read a bounded sample of recent commits and open pull requests, then report its coverage."))
                return@buildJsonArray
            }
            if (GitHubReviewComparison.matches(goal)) add(step("review_comparison", GitHubReviewComparison.INSTRUCTION))
            if (wantsWrite) add(step("prerequisites", "Check the selected source revision, readable files, available test command and environment, and write scope once. Continue an independent step when a dependency blocks another."))
            add(step("search_repository", "Use the persistent lexical index and explicit continuation until relevant paths are covered."))
            add(step("read_ranges", "Load a bounded multi-file bundle concurrently, inspect its revision-bound import graph and related tests, and retain evidence handles and immutable blob SHAs. Lexical hints do not establish compiler resolution."))
            if (prStep != null) {
                add(step("pr_context", "Resolve the working head and base, then check for an existing matching pull request before creation."))
            } else if (wantsMerge) {
                add(step("pr_context", "Resolve the requested open pull request and verify its repository, base and head SHA. If the target is missing or ambiguous, report that before merging."))
            } else if (listOf("review", "pull request", "pr ").any(normalized::contains)) {
                add(step("pr_context", "Page PR files and checks with expected_head so a moving PR cannot mix revisions."))
            }
            if (listOf("debug", "failure", "ci", "build").any(normalized::contains)) add(step(if (wantsWrite) "baseline_ci_failure_digest" else "ci_failure_digest", "Read the latest failed job at the selected branch and inspect failure context throughout the bounded log."))
            if (wantsWrite) {
                add(step("select_defect", "Select one actionable correctness issue with a source range, expected behavior, and the observation that would disprove it.", "read_ranges"))
                add(step("regression", "Choose a narrow failing case or record the exact missing execution prerequisite.", "select_defect"))
                add(step("branch_head", "Read the working-branch head immediately before preparing writes."))
                add(step("stage_patch", "Stage exact anchored edits against the original blob SHA and inspect the bounded preview.", "branch_head", "select_defect"))
                add(step("validation", "Inspect added-text findings and related test-generation context. Add behavior, boundary and recovery regressions where warranted; run the repository lint, compiler, tests and static checks. Record the executed target, revision and result.", "stage_patch", "regression"))
                add(step("commit_staged_patch", "Commit the reviewed stage with its original expected_head_sha; never force-update."))
                add(step("diff", "Inspect the resulting compare data and changed-file statistics."))
                add(step("ci_failure_digest", "Inspect bounded full CI logs for late failures at the new commit, expanding relevant line ranges."))
            }
            if (prStep != null) {
                val dependencies = if (wantsWrite) arrayOf("commit_staged_patch", "diff", "pr_context") else arrayOf("pr_context")
                add(step(prStep, "Create or update the requested ${if (prStep == "draft_pr") "draft " else ""}pull request at the verified head and retain its URL.", *dependencies))
            }
            if (wantsMerge) {
                val target = prStep ?: "pr_context"
                val dependencies = if (wantsWrite) arrayOf(target, "ci_failure_digest") else arrayOf(target)
                add(step("merge_gate", "Verify required checks and reviews on the current PR head. Merge that exact SHA under repository protections.", *dependencies))
            }
            if (wantsRelease) {
                add(step("release_status", "Check whether the requested tag/release already exists and identify the repository release workflow."))
                add(step("publish_release", "Use the native publish_release action. Prefer the repository release workflow; use direct release creation only when no release workflow applies."))
                add(step("release_verify", "Call release_status after publication to verify the workflow result, final release and assets."))
            }
        }
    }

    private fun step(id: String, description: String, vararg dependencies: String) = buildJsonObject {
        if (dependencies.isNotEmpty()) put("depends_on", JsonArray(dependencies.map { kotlinx.serialization.json.JsonPrimitive(it) }))
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
