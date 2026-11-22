package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiFailureObservationTest {
    private val head = "a".repeat(40)
    private val arguments = buildJsonObject {
        put("action", "runs")
        put("head_sha", head)
        put("status", "failure")
    }
    private val result = buildJsonObject {
        put("page", 1)
        put("returned", 0)
        put("total_count", 0)
        put("has_more", false)
        put("traversal_complete", true)
        put("workflow_runs", JsonArray(emptyList()))
    }

    @Test fun completeEmptyFailureQueryRetainsItsRevisionAndCanBeExplainedWithoutAnotherModelCall() {
        assertEquals(head, GitHubCiFailureObservation.emptyRevision(arguments, result))
        val summary = GitHubSemanticSummary.from("github_ci", arguments, result)
        assertEquals(JsonPrimitive(true), summary["failure_query_complete"])
        assertEquals(JsonPrimitive(0), summary["failed_runs_observed"])
        val text = GitHubPartialReport.render(listOf(JsonObject(summary + ("receipt_id" to JsonPrimitive("zero-failures")))))
        assertTrue(text, text.contains("No failed workflow runs"))
        assertTrue(text, text.contains(head))
        assertTrue(text, text.contains("Required checks and newly executed tests remain separate"))
        assertFalse(text.contains("all checks passed"))
    }

    @Test fun additionalFiltersMalformedCountsAndIncompleteOrMovingCoverageCannotProveAbsence() {
        for (change in listOf(
            "workflow_id" to JsonPrimitive(12),
            "branch" to JsonPrimitive("other"),
            "created" to JsonPrimitive(">2026-11-01"),
            "page" to JsonPrimitive(2),
            "status" to JsonPrimitive("success"),
            "head_sha" to JsonPrimitive("main"),
            "commit_sha" to JsonPrimitive("b".repeat(40))
        )) {
            assertNull(GitHubCiFailureObservation.emptyRevision(JsonObject(arguments + change), result))
        }
        for (change in listOf(
            "page" to JsonPrimitive(2),
            "returned" to JsonPrimitive(1),
            "total_count" to JsonPrimitive(1),
            "has_more" to JsonPrimitive(true),
            "traversal_complete" to JsonPrimitive(false),
            "pagination_inconsistent" to JsonPrimitive(true),
            "history_truncated" to JsonPrimitive(true),
            "head_sha" to JsonPrimitive("b".repeat(40)),
            "pagination_progress" to buildJsonObject { put("upstream_changed_during_traversal", true) }
        )) {
            assertNull(GitHubCiFailureObservation.emptyRevision(arguments, JsonObject(result + change)))
        }
        assertNull(GitHubCiFailureObservation.emptyRevision(arguments, JsonObject(result - "total_count")))
    }
}
