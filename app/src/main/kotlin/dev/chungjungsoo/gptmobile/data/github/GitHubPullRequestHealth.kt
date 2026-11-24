package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A timestamped GitHub decision, never a permission to bypass server-side rules. */
@Serializable
internal data class GitHubPullRequestHealth(
    val state: String = "UNKNOWN",
    val detail: String = "GitHub merge eligibility has not been verified.",
    val headSha: String = "",
    val baseSha: String = "",
    val review: String = "UNKNOWN",
    val requiredChecks: Int? = null,
    val failingChecks: List<String> = emptyList(),
    val observedAt: Long = System.currentTimeMillis(),
    val githubPermitsMerging: Boolean = false,
    val requestedValidationPassed: Boolean = false,
    val checkSelection: GitHubCheckSelectionEvidence = GitHubCheckSelectionEvidence()
) {
    val eligible: Boolean get() = state == "ELIGIBLE"
}

internal val gitHubPullRequestHealthQuery = """
    query WorkspacePullRequestHealth(${'$'}owner: String!, ${'$'}repo: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
      repository(owner: ${'$'}owner, name: ${'$'}repo) {
        pullRequest(number: ${'$'}number) {
          state isDraft headRefOid baseRefOid mergeable mergeStateStatus reviewDecision
          commits(last: 1) { nodes { commit { oid statusCheckRollup {
            state contexts(first: 100, after: ${'$'}cursor) {
              totalCount pageInfo { hasNextPage endCursor }
              nodes {
                __typename
                ... on CheckRun {
                  id name status conclusion isRequired(pullRequestNumber: ${'$'}number)
                  checkSuite {
                    id commit { oid } app { id } branch { id }
                    workflowRun { id runNumber runAttempt event workflow { id } }
                  }
                }
                ... on StatusContext { id context state isRequired(pullRequestNumber: ${'$'}number) }
              }
            }
          } } } }
        }
      }
    }
""".trimIndent()

internal fun gitHubPullRequestHealth(response: JsonObject, expectedHead: String, expectedBase: String? = null): GitHubPullRequestHealth {
    fun JsonObject.obj(key: String) = this[key] as? JsonObject
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    if (response["errors"] is JsonArray && (response["errors"] as JsonArray).isNotEmpty()) {
        return GitHubPullRequestHealth(detail = "GitHub could not verify merge rules. Check repository access and refresh.")
    }
    val pr = response.obj("data")?.obj("repository")?.obj("pullRequest") ?: return GitHubPullRequestHealth()
    if (!pr.containsKey("reviewDecision")) return GitHubPullRequestHealth(detail = "Review state was not returned by GitHub.")
    val health = GitHubPullRequestHealth(
        headSha = pr.text("headRefOid").orEmpty(),
        baseSha = pr.text("baseRefOid").orEmpty(),
        review = pr.text("reviewDecision") ?: "NOT_REQUIRED",
        githubPermitsMerging = pr.text("mergeStateStatus") == "CLEAN" && pr.text("mergeable") == "MERGEABLE" && pr.flag("isDraft") == false
    )
    if (!health.baseSha.matches(Regex("[a-fA-F0-9]{40}"))) return health.copy(detail = "PR base revision is unavailable.")
    if (health.headSha != expectedHead) return health.copy(state = "STALE", detail = "The PR head changed; refresh before continuing.", githubPermitsMerging = false)
    if (expectedBase != null && health.baseSha != expectedBase) return health.copy(state = "STALE", detail = "The PR base changed; refresh the diff and checks before continuing.", githubPermitsMerging = false)
    if (pr.text("state") == "MERGED") return health.copy(state = "MERGED", detail = "This pull request has been merged.")
    if (pr.text("state") != "OPEN") return health.copy(state = "CLOSED", detail = "This pull request is no longer open.")
    val merge = pr.text("mergeStateStatus")
    val blocker = when {
        pr.flag("isDraft") != false -> "DRAFT" to "Mark the pull request ready for review before merging."
        pr.text("mergeable") == "CONFLICTING" || merge == "DIRTY" -> "CONFLICTS" to "Resolve merge conflicts and validate the updated branch."
        merge == "BEHIND" -> "BEHIND" to "The branch is behind its base. Update it and rerun validation."
        health.review in setOf("REVIEW_REQUIRED", "CHANGES_REQUESTED") -> "REVIEW_REQUIRED" to "GitHub requires review: ${health.review.lowercase().replace('_', ' ')}."
        else -> null
    }
    val commits = pr.obj("commits")?.get("nodes") as? JsonArray
    val commit = (commits?.singleOrNull() as? JsonObject)?.obj("commit")
    if (commit?.text("oid") != expectedHead) return health.copy(detail = "Check evidence is missing or belongs to another revision.")
    val rollup = commit.obj("statusCheckRollup") ?: return health.copy(detail = "No CI evidence is available for this revision.")
    val contexts = rollup.obj("contexts") ?: return health
    val nodes = contexts["nodes"] as? JsonArray ?: return health
    val count = (contexts["totalCount"] as? JsonPrimitive)?.intOrNull
    if (contexts.obj("pageInfo")?.flag("hasNextPage") != false || count != nodes.size || nodes.isEmpty()) {
        return health.copy(detail = "The check inventory is empty or incomplete. Review the full PR on GitHub.")
    }
    val observed = nodes.map { it as? JsonObject ?: return health }
    if (observed.any { it.flag("isRequired") == null }) return health.copy(detail = "Required-check information is unavailable.")
    val selection = selectGitHubHealthChecks(observed, expectedHead)
    val selectionEvidence = selection.evidence.copy(rawRollupState = rollup.text("state") ?: "UNKNOWN")
    val checks = selection.checks ?: return health.copy(detail = "The current check identities or workflow coverage are unverified. Refresh the PR before continuing.", checkSelection = selectionEvidence)
    val failed = checks.filter {
        it.text("conclusion") in setOf("FAILURE", "CANCELLED", "TIMED_OUT", "STALE") ||
            it.text("state") in setOf("ERROR", "FAILURE")
    }.map { (it.text("name") ?: it.text("context") ?: "Unnamed check").take(160) }
    val blockedChecks = checks.filter {
        it.text("conclusion") in setOf("ACTION_REQUIRED", "STARTUP_FAILURE") ||
            it.text("status") == "COMPLETED" &&
            it.flag("isRequired") == true &&
            it.text("conclusion") in setOf("SKIPPED", "NEUTRAL")
    }.map { (it.text("name") ?: it.text("context") ?: "Unnamed check").take(160) }
    val passed = checks.all {
        when (it.text("__typename")) {
            "CheckRun" -> it.text("status") == "COMPLETED" && (it.text("conclusion") == "SUCCESS" || it.flag("isRequired") == false && it.text("conclusion") in setOf("NEUTRAL", "SKIPPED"))
            "StatusContext" -> it.text("state") == "SUCCESS"
            else -> false
        }
    }
    val hasSuccess = checks.any { it.text("conclusion") == "SUCCESS" || it.text("state") == "SUCCESS" }
    val coverageVerified = selection.evidence.state == "VERIFIED"
    val validationPassed = coverageVerified && hasSuccess && passed && rollup.text("state") == "SUCCESS"
    val result = health.copy(requiredChecks = if (coverageVerified) checks.count { it.flag("isRequired") == true } else null, failingChecks = (failed + blockedChecks).distinct().take(10), requestedValidationPassed = validationPassed, checkSelection = selectionEvidence)
    if (blocker != null) return result.copy(state = blocker.first, detail = blocker.second)
    if (failed.isNotEmpty()) return result.copy(state = "FAILED", detail = "Fix failing checks before merging.")
    if (blockedChecks.isNotEmpty()) return result.copy(state = "BLOCKED", detail = "A completed check needs workflow configuration or a required job was skipped. Inspect its conclusion before retrying.")
    if (!coverageVerified) {
        val pending = checks.any { it.text("status") in setOf("IN_PROGRESS", "PENDING", "QUEUED", "REQUESTED", "WAITING") || it.text("state") == "PENDING" }
        return result.copy(state = if (pending) "WAITING" else "UNKNOWN", detail = "The latest workflow run has not reported every check. Current checks and complete coverage must be verified before merging.")
    }
    if (hasSuccess && passed && !validationPassed) return result.copy(detail = "Current checks passed, but GitHub's aggregate is not successful. Refresh the PR before merging.")
    if (!validationPassed) return result.copy(state = "WAITING", detail = "CI is pending or unverified for the current head.")
    if (merge != "CLEAN" || pr.text("mergeable") != "MERGEABLE") return result.copy(state = "BLOCKED", detail = "GitHub merge state: ${merge ?: "UNKNOWN"}. Review branch rules, deployments or approvals.")
    if (health.review !in setOf("APPROVED", "NOT_REQUIRED")) return result.copy(detail = "Review state is unknown.")
    return result.copy(state = "ELIGIBLE", requestedValidationPassed = true, detail = "Required checks succeeded and GitHub permits merging this revision. Rules and head are checked again when merging.")
}
