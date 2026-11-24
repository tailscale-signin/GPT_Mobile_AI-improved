package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPullRequestHealthTest {
    private val head = "a".repeat(40)
    private fun check(
        id: String = "check-1",
        name: String = "Required verification",
        conclusion: String = "SUCCESS",
        status: String = "COMPLETED",
        run: String = "run-1",
        number: Int = 1,
        attempt: Int = 1,
        workflow: String = "workflow-1",
        app: String = "app-1",
        event: String = "pull_request",
        branch: String = "branch-1"
    ) = buildJsonObject {
        put("__typename", "CheckRun")
        put("id", id)
        put("name", name)
        put("status", status)
        put("conclusion", conclusion)
        put("isRequired", true)
        put(
            "checkSuite",
            buildJsonObject {
                put("id", "suite-$run")
                put("commit", buildJsonObject { put("oid", head) })
                put("app", buildJsonObject { put("id", app) })
                put("branch", buildJsonObject { put("id", branch) })
                put(
                    "workflowRun",
                    buildJsonObject {
                        put("id", run)
                        put("runNumber", number)
                        put("runAttempt", attempt)
                        put("event", event)
                        put("workflow", buildJsonObject { put("id", workflow) })
                    }
                )
            }
        )
    }

    private fun response(
        merge: String = "CLEAN",
        review: String = "APPROVED",
        headSha: String = head,
        conclusion: String = "SUCCESS",
        status: String = "COMPLETED",
        more: Boolean = false,
        count: Int? = null,
        required: String = "true",
        draft: Boolean = false,
        checks: List<JsonObject> = listOf(JsonObject(check(conclusion = conclusion, status = status) + ("isRequired" to Json.parseToJsonElement(required)))),
        rollup: String = "SUCCESS"
    ): JsonObject = Json.parseToJsonElement(
        """{"data":{"repository":{"pullRequest":{
        "state":"OPEN","isDraft":$draft,"headRefOid":"$headSha","baseRefOid":"${"b".repeat(40)}",
        "mergeable":"MERGEABLE","mergeStateStatus":"$merge","reviewDecision":"$review",
        "commits":{"nodes":[{"commit":{"oid":"$headSha","statusCheckRollup":{"state":"$rollup","contexts":{
        "totalCount":${count ?: checks.size},"pageInfo":{"hasNextPage":$more},"nodes":${JsonArray(checks)}}}}}]}}}}}"""
    ).jsonObject

    @Test fun baseAdvanceBetweenFileReviewAndHealthLookupRequiresFreshEvidence() {
        val health = gitHubPullRequestHealth(response(), head, "c".repeat(40))
        assertEquals("STALE", health.state)
        assertFalse(health.eligible)
        assertFalse(health.githubPermitsMerging)
        assertFalse(health.requestedValidationPassed)
        assertTrue(gitHubPullRequestHealth(response(), head, "b".repeat(40)).eligible)
    }

    @Test fun greenChecksCannotOverrideGitHubMergeRules() {
        for (merge in listOf("BLOCKED", "BEHIND", "DIRTY", "UNKNOWN", "UNSTABLE", "HAS_HOOKS")) {
            assertFalse(merge, gitHubPullRequestHealth(response(merge = merge), head).eligible)
        }
        for (review in listOf("REVIEW_REQUIRED", "CHANGES_REQUESTED", "UNKNOWN")) {
            assertFalse(review, gitHubPullRequestHealth(response(review = review), head).eligible)
        }
        val draft = gitHubPullRequestHealth(response(draft = true), head)
        assertFalse(draft.eligible)
        assertEquals(1, draft.requiredChecks)
        assertEquals(null, gitHubPullRequestHealth(JsonObject(emptyMap()), head).requiredChecks)
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
        for (conclusion in listOf("FAILURE", "CANCELLED", "TIMED_OUT", "STALE")) {
            val health = gitHubPullRequestHealth(response(conclusion = conclusion), head)
            assertEquals("FAILED", health.state)
            assertEquals(listOf("Required verification"), health.failingChecks)
        }
    }

    @Test fun terminalConfigurationBlockersRetainTheirNamesAndPreventMerge() {
        for (conclusion in listOf("STARTUP_FAILURE", "ACTION_REQUIRED", "SKIPPED", "NEUTRAL")) {
            val health = gitHubPullRequestHealth(response(conclusion = conclusion), head)
            assertEquals(conclusion, "BLOCKED", health.state)
            assertEquals(listOf("Required verification"), health.failingChecks)
            assertFalse(health.eligible)
            assertFalse(health.requestedValidationPassed)
        }
    }

    @Test fun failedChecksTakePrecedenceAndSupersededBlockersCannotVetoCurrentSuccess() {
        val blockerName = "Configuration " + "x".repeat(200)
        val failed = check(id = "failed", name = "Behavior tests", conclusion = "FAILURE")
        val blocked = check(id = "blocked", name = blockerName, conclusion = "ACTION_REQUIRED", workflow = "workflow-2", run = "other-run")
        val mixed = gitHubPullRequestHealth(response(checks = listOf(blocked, failed)), head)
        assertEquals("FAILED", mixed.state)
        assertEquals(listOf("Behavior tests", blockerName.take(160)), mixed.failingChecks)
        assertFalse(mixed.requestedValidationPassed)
        val obsolete = check(id = "old", conclusion = "STARTUP_FAILURE")
        val current = check(id = "current", run = "run-2", number = 2)
        val recovered = gitHubPullRequestHealth(response(checks = listOf(obsolete, current)), head)
        assertTrue(recovered.eligible)
        assertTrue(recovered.failingChecks.isEmpty())
        assertEquals(1, recovered.checkSelection.supersededChecks)
    }

    @Test fun partialGraphQlResponsesAndUnavailableRulesFailClosed() {
        val partial = JsonObject(response() + ("errors" to Json.parseToJsonElement("""[{"message":"denied"}]""")))
        assertFalse(gitHubPullRequestHealth(partial, head).eligible)
        assertFalse(gitHubPullRequestHealth(JsonObject(emptyMap()), head).eligible)
        assertFalse(gitHubPullRequestHealth(JsonObject(mapOf("data" to JsonPrimitive("unavailable"))), head).eligible)
    }

    @Test fun supersededFailureDoesNotTurnCurrentRunningChecksIntoFailure() {
        val previous = check(id = "old", conclusion = "CANCELLED")
        val current = check(id = "current", run = "run-2", number = 2, status = "IN_PROGRESS", conclusion = "")
        val health = gitHubPullRequestHealth(response(checks = listOf(previous, current), rollup = "FAILURE"), head)
        assertEquals("WAITING", health.state)
        assertTrue(health.failingChecks.isEmpty())
        assertFalse(health.requestedValidationPassed)
        assertFalse(health.eligible)
        assertEquals(1, health.requiredChecks)
        assertEquals(2, health.checkSelection.observedChecks)
        assertEquals(1, health.checkSelection.applicableChecks)
        assertEquals(1, health.checkSelection.supersededChecks)
        assertEquals(listOf("run-2"), health.checkSelection.selectedWorkflowRunIds)
        assertEquals(listOf("run-1"), health.checkSelection.supersededWorkflowRunIds)
    }

    @Test fun currentSuccessStillRequiresSuccessfulRawRollupAndGitHubPermission() {
        val checks = listOf(check(id = "old", conclusion = "FAILURE"), check(id = "new", run = "run-2", number = 2))
        val inconsistent = gitHubPullRequestHealth(response(checks = checks, rollup = "FAILURE"), head)
        assertEquals("UNKNOWN", inconsistent.state)
        assertEquals("FAILURE", inconsistent.checkSelection.rawRollupState)
        assertFalse(inconsistent.requestedValidationPassed)
        assertEquals("BLOCKED", gitHubPullRequestHealth(response(checks = checks, merge = "BLOCKED"), head).state)
        val health = gitHubPullRequestHealth(response(checks = checks), head)
        assertTrue(health.eligible)
        assertTrue(health.requestedValidationPassed)
        assertEquals("SUPERSEDED_WORKFLOW_RUNS_EXCLUDED", health.checkSelection.reasonCode)
    }

    @Test fun aNewFailureCannotInheritTheOldSuccessfulRun() {
        val checks = listOf(check(id = "old"), check(id = "new", run = "run-2", number = 2, conclusion = "FAILURE"))
        val health = gitHubPullRequestHealth(response(checks = checks), head)
        assertEquals("FAILED", health.state)
        assertEquals(listOf("Required verification"), health.failingChecks)
        assertFalse(health.requestedValidationPassed)
    }

    @Test fun identicalNamesFromDifferentWorkflowIdentitiesCannotSupersedeEachOther() {
        val failed = check(id = "old", conclusion = "FAILURE", number = 100)
        val unrelated = listOf(
            check(id = "new", run = "run-2", number = 200, workflow = "other-workflow"),
            check(id = "new", run = "run-2", number = 200, app = "other-app"),
            check(id = "new", run = "run-2", number = 200, event = "push"),
            check(id = "new", run = "run-2", number = 200, branch = "other-branch")
        )
        for (other in unrelated) {
            val health = gitHubPullRequestHealth(response(checks = listOf(failed, other)), head)
            assertEquals("FAILED", health.state)
            assertEquals(0, health.checkSelection.supersededChecks)
            assertEquals(2, health.requiredChecks)
        }
    }

    @Test fun incompleteIdentityAndMismatchedRevisionRemainUnknown() {
        val original = check()
        val suite = original.getValue("checkSuite").jsonObject
        val run = suite.getValue("workflowRun").jsonObject
        val invalidSuites = listOf(
            JsonObject(suite - "id"),
            JsonObject(suite - "app"),
            JsonObject(suite - "branch"),
            JsonObject(suite - "workflowRun"),
            JsonObject(suite + ("commit" to buildJsonObject { put("oid", "c".repeat(40)) }))
        ) + listOf("id", "workflow", "event", "runNumber", "runAttempt").map { key ->
            JsonObject(suite + ("workflowRun" to JsonObject(run - key)))
        }
        val invalidChecks = invalidSuites.map { JsonObject(original + ("checkSuite" to it)) } + JsonObject(original - "checkSuite") + JsonObject(original - "id")
        for (invalid in invalidChecks) {
            val health = gitHubPullRequestHealth(response(checks = listOf(invalid)), head)
            assertEquals("UNKNOWN", health.state)
            assertEquals("UNKNOWN", health.checkSelection.state)
            assertFalse(health.eligible)
            assertFalse(health.requestedValidationPassed)
        }
    }

    @Test fun sameRunAttemptMetadataDoesNotProveWhichDuplicateCheckIsCurrent() {
        val old = check(id = "old", conclusion = "FAILURE", attempt = 2)
        val current = check(id = "current", attempt = 2)
        val health = gitHubPullRequestHealth(response(checks = listOf(old, current)), head)
        assertEquals("UNKNOWN", health.state)
        assertEquals("CHECK_ATTEMPT_IDENTITY_AMBIGUOUS", health.checkSelection.reasonCode)
        assertFalse(health.eligible)
    }

    @Test fun inconsistentRunIdentityAndChangingAttemptsRemainUnknown() {
        val scenarios = listOf(
            listOf(check(id = "one"), check(id = "two", run = "another-run")),
            listOf(check(id = "one"), check(id = "two", name = "lint", attempt = 2)),
            listOf(check(id = "one"), check(id = "two", name = "lint", number = 2))
        )
        for (checks in scenarios) {
            val health = gitHubPullRequestHealth(response(checks = checks), head)
            assertEquals("UNKNOWN", health.state)
            assertFalse(health.requestedValidationPassed)
        }
    }

    @Test fun missingCurrentMatrixVariantCannotInheritAnOldSuccess() {
        val checks = listOf(
            check(id = "old-linux", name = "test (linux)"),
            check(id = "old-mac", name = "test (mac)"),
            check(id = "new-linux", name = "test (linux)", run = "run-2", number = 2)
        )
        val health = gitHubPullRequestHealth(response(checks = checks), head)
        assertEquals("UNKNOWN", health.state)
        assertEquals("LATEST_RUN_CHECK_COVERAGE_INCOMPLETE", health.checkSelection.reasonCode)
        assertFalse(health.requestedValidationPassed)
        val complete = checks + check(id = "new-mac", name = "test (mac)", run = "run-2", number = 2, status = "QUEUED", conclusion = "")
        val pending = gitHubPullRequestHealth(response(checks = complete), head)
        assertEquals("WAITING", pending.state)
        assertEquals(2, pending.requiredChecks)
        assertEquals(2, pending.checkSelection.supersededChecks)
    }

    @Test fun aRunningWorkflowWithoutItsAggregateIsPendingAndCannotUseHistoricalGreen() {
        val checks = listOf(
            check(id = "old-aggregate", conclusion = "FAILURE", run = "run-3630", number = 3630),
            check(id = "old-tests", name = "Unit tests", conclusion = "CANCELLED", run = "run-3630", number = 3630),
            check(id = "green-aggregate", run = "run-3631", number = 3631),
            check(id = "green-tests", name = "Unit tests", run = "run-3631", number = 3631),
            check(id = "current-tests", name = "Unit tests", status = "IN_PROGRESS", conclusion = "", run = "run-3632", number = 3632)
        )
        val health = gitHubPullRequestHealth(response(checks = checks, rollup = "FAILURE"), head)
        assertEquals("WAITING", health.state)
        assertEquals("INCOMPLETE", health.checkSelection.state)
        assertEquals(1, health.checkSelection.unreportedCurrentChecks)
        assertEquals(null, health.requiredChecks)
        assertTrue(health.failingChecks.isEmpty())
        assertFalse(health.requestedValidationPassed)
        assertFalse(health.eligible)
    }

    @Test fun externalAppChecksAreRetainedWithoutInventingWorkflowSupersession() {
        val external = check(id = "external", conclusion = "FAILURE", app = "external-app").let {
            JsonObject(it + ("checkSuite" to JsonObject(it.getValue("checkSuite").jsonObject + ("workflowRun" to JsonNull))))
        }
        val health = gitHubPullRequestHealth(response(checks = listOf(external, check())), head)
        assertEquals("FAILED", health.state)
        assertEquals(2, health.checkSelection.applicableChecks)
        assertEquals(0, health.checkSelection.supersededChecks)
    }

    @Test fun checkSelectionReportKeepsBoundedRunEvidence() {
        val checks = (1..25).map { index -> check(id = "check-$index", run = "run-$index", workflow = "workflow-$index") }
        val health = gitHubPullRequestHealth(response(checks = checks), head)
        assertTrue(health.eligible)
        assertEquals(25, health.checkSelection.applicableChecks)
        assertEquals(20, health.checkSelection.selectedWorkflowRunIds.size)
        assertTrue(health.checkSelection.workflowRunIdsTruncated)
        val exported = Json.encodeToJsonElement(GitHubPullRequestHealth.serializer(), health).jsonObject
        assertEquals(JsonPrimitive(25), exported.getValue("checkSelection").jsonObject["observedChecks"])
    }
}
