package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPullRequestHealthTest {
    private val head = "a".repeat(40)
    private fun response(
        merge: String = "CLEAN",
        review: String = "APPROVED",
        headSha: String = head,
        conclusion: String = "SUCCESS",
        status: String = "COMPLETED",
        more: Boolean = false,
        count: Int = 1,
        required: String = "true",
        draft: Boolean = false
    ): JsonObject = Json.parseToJsonElement(
        """{"data":{"repository":{"pullRequest":{
        "state":"OPEN","isDraft":$draft,"headRefOid":"$headSha","baseRefOid":"${"b".repeat(40)}",
        "mergeable":"MERGEABLE","mergeStateStatus":"$merge","reviewDecision":"$review",
        "commits":{"nodes":[{"commit":{"oid":"$headSha","statusCheckRollup":{"state":"SUCCESS","contexts":{
        "totalCount":$count,"pageInfo":{"hasNextPage":$more},"nodes":[{"__typename":"CheckRun","name":"Required verification",
        "status":"$status","conclusion":"$conclusion","isRequired":$required}]}}}}]}}}}}"""
    ).jsonObject

    @Test fun greenChecksCannotOverrideGitHubMergeRules() {
        for (merge in listOf("BLOCKED", "BEHIND", "DIRTY", "UNKNOWN", "UNSTABLE", "HAS_HOOKS")) {
            assertFalse(merge, gitHubPullRequestHealth(response(merge = merge), head).eligible)
        }
        for (review in listOf("REVIEW_REQUIRED", "CHANGES_REQUESTED", "UNKNOWN")) {
            assertFalse(review, gitHubPullRequestHealth(response(review = review), head).eligible)
        }
        assertFalse(gitHubPullRequestHealth(response(draft = true), head).eligible)
    }

    @Test fun onlyCompleteCurrentEvidenceCanBecomeEligible() {
        val health = gitHubPullRequestHealth(response(), head)
        assertTrue(health.eligible)
        assertEquals(1, health.requiredChecks)
        assertFalse(gitHubPullRequestHealth(response(more = true), head).eligible)
        assertFalse(gitHubPullRequestHealth(response(count = 2), head).eligible)
        assertFalse(gitHubPullRequestHealth(response(required = "null"), head).eligible)
        assertEquals("STALE", gitHubPullRequestHealth(response(headSha = "c".repeat(40)), head).state)
    }

    @Test fun pendingCancelledAndFailedChecksBlockMerge() {
        assertFalse(gitHubPullRequestHealth(response(status = "QUEUED"), head).eligible)
        for (conclusion in listOf("FAILURE", "CANCELLED", "TIMED_OUT", "STALE", "STARTUP_FAILURE", "ACTION_REQUIRED")) {
            val health = gitHubPullRequestHealth(response(conclusion = conclusion), head)
            assertEquals("FAILED", health.state)
            assertEquals(listOf("Required verification"), health.failingChecks)
        }
    }

    @Test fun partialGraphQlResponsesAndUnavailableRulesFailClosed() {
        val partial = JsonObject(response() + ("errors" to Json.parseToJsonElement("""[{"message":"denied"}]""")))
        assertFalse(gitHubPullRequestHealth(partial, head).eligible)
        assertFalse(gitHubPullRequestHealth(JsonObject(emptyMap()), head).eligible)
        assertFalse(gitHubPullRequestHealth(JsonObject(mapOf("data" to JsonPrimitive("unavailable"))), head).eligible)
    }
}
