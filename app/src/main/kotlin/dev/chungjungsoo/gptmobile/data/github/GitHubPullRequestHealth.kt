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
    val requiredChecks: Int = 0,
    val failingChecks: List<String> = emptyList(),
    val observedAt: Long = System.currentTimeMillis()
) {
    val eligible: Boolean get() = state == "ELIGIBLE"
}

internal val gitHubPullRequestHealthQuery = """
    query WorkspacePullRequestHealth(${'$'}owner: String!, ${'$'}repo: String!, ${'$'}number: Int!) {
      repository(owner: ${'$'}owner, name: ${'$'}repo) {
        pullRequest(number: ${'$'}number) {
          state isDraft headRefOid baseRefOid mergeable mergeStateStatus reviewDecision
          commits(last: 1) { nodes { commit { oid statusCheckRollup {
            state contexts(first: 100) {
              totalCount pageInfo { hasNextPage }
              nodes {
                __typename
                ... on CheckRun { name status conclusion isRequired(pullRequestNumber: ${'$'}number) }
                ... on StatusContext { context state isRequired(pullRequestNumber: ${'$'}number) }
              }
            }
          } } } }
        }
      }
    }
""".trimIndent()

internal fun gitHubPullRequestHealth(response: JsonObject, expectedHead: String): GitHubPullRequestHealth {
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
        review = pr.text("reviewDecision") ?: "NOT_REQUIRED"
    )
    if (!health.baseSha.matches(Regex("[a-fA-F0-9]{40}"))) return health.copy(detail = "PR base revision is unavailable.")
    if (health.headSha != expectedHead) return health.copy(state = "STALE", detail = "The PR head changed; refresh before continuing.")
    if (pr.text("state") != "OPEN") return health.copy(state = "CLOSED", detail = "This pull request is no longer open.")
    if (pr.flag("isDraft") != false) return health.copy(state = "DRAFT", detail = "Mark the pull request ready for review before merging.")
    val merge = pr.text("mergeStateStatus")
    if (pr.text("mergeable") == "CONFLICTING" || merge == "DIRTY") return health.copy(state = "CONFLICTS", detail = "Resolve merge conflicts and validate the updated branch.")
    if (merge == "BEHIND") return health.copy(state = "BEHIND", detail = "The branch is behind its base. Update it and rerun validation.")
    if (health.review in setOf("REVIEW_REQUIRED", "CHANGES_REQUESTED")) return health.copy(state = "REVIEW_REQUIRED", detail = "GitHub requires review: ${health.review.lowercase().replace('_', ' ')}.")
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
    val checks = nodes.map { it as? JsonObject ?: return health }
    if (checks.any { it.flag("isRequired") == null }) return health.copy(detail = "Required-check information is unavailable.")
    val failed = checks.filter {
        it.text("conclusion") in setOf("FAILURE", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE") ||
            it.text("state") in setOf("ERROR", "FAILURE")
    }.map { (it.text("name") ?: it.text("context") ?: "Unnamed check").take(160) }
    val result = health.copy(requiredChecks = checks.count { it.flag("isRequired") == true }, failingChecks = failed.take(10))
    if (failed.isNotEmpty()) return result.copy(state = "FAILED", detail = "Fix failing checks before merging.")
    val passed = checks.all {
        when (it.text("__typename")) {
            "CheckRun" -> it.text("status") == "COMPLETED" && it.text("conclusion") in setOf("SUCCESS", "NEUTRAL", "SKIPPED")
            "StatusContext" -> it.text("state") == "SUCCESS"
            else -> false
        }
    }
    val hasSuccess = checks.any { it.text("conclusion") == "SUCCESS" || it.text("state") == "SUCCESS" }
    if (!hasSuccess || !passed || rollup.text("state") != "SUCCESS") return result.copy(state = "WAITING", detail = "CI is pending or unverified for the current head.")
    if (merge != "CLEAN" || pr.text("mergeable") != "MERGEABLE") return result.copy(state = "BLOCKED", detail = "GitHub merge state: ${merge ?: "UNKNOWN"}. Review branch rules, deployments or approvals.")
    if (health.review !in setOf("APPROVED", "NOT_REQUIRED")) return result.copy(detail = "Review state is unknown.")
    return result.copy(state = "ELIGIBLE", detail = "GitHub reports this revision eligible. Rules and head are checked again when merging.")
}
