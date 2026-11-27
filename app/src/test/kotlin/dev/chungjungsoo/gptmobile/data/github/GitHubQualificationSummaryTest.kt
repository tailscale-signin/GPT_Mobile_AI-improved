package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubQualificationSummaryTest {
    private fun row(status: String, required: Boolean = true) = buildJsonObject {
        put("required", required)
        put("status", status)
        put("acceptance_state", if (status == "PASSED") "PASS" else "UNVERIFIED")
    }

    @Test fun capturedQualificationCountsRemainDistinctFromIndependentTaskCriteria() {
        val rows = List(46) { row("PASSED") } + List(73) { row("BLOCKED") } + List(7) { row("UNTESTED") } + List(6) { row("OUT_OF_SCOPE", false) }
        val result = GitHubQualificationSummary.snapshot("Test all authorized GitHub integration tools", JsonArray(rows))
        for ((name, count) in mapOf("required" to 126, "passed" to 46, "blocked" to 73, "untested" to 7, "failed" to 0, "remaining" to 80, "excluded" to 6)) {
            assertEquals(name, JsonPrimitive(count), result[name])
        }
        assertEquals(JsonPrimitive(true), result["task_criteria_are_separate"])
        assertEquals(JsonPrimitive(false), result["permissions_changed"])
    }

    @Test fun scopesFollowAcceptedIntentAndNeverPromoteCachedOrUntestedRows() {
        val rows = JsonArray(listOf(row("PASSED"), row("CACHED"), row("PREFLIGHT_REJECTED"), row("CONDITIONAL")))
        val cases = mapOf(
            "Test integration read only" to "READ_ONLY_INSPECTION",
            "Test the supported fixture test matrix [github-workflow-preset:test_integration]" to "SUPPORTED_FIXTURE_QUALIFICATION",
            "Test all authorized tools and permitted writes" to "FULL_LIVE_QUALIFICATION"
        )
        for ((task, scope) in cases) {
            val result = GitHubQualificationSummary.snapshot(task, rows)
            assertEquals(JsonPrimitive(scope), result["scope"])
            for (outcome in listOf("passed", "untested", "failed", "blocked")) assertEquals(JsonPrimitive(1), result[outcome])
        }
    }
}
