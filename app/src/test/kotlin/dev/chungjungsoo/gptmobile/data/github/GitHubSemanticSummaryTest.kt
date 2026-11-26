package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSemanticSummaryTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun confirmedCopilotStateIsBoundToTheSelectedTask() {
        val args = buildJsonObject {
            put("action", "copilot_task_status")
            put("owner", "owner")
            put("repo", "work")
            put("task_id", "task-7")
        }
        val task = buildJsonObject {
            put("schema", "github.copilot-task.v1")
            put("execution_owner", "GITHUB_CLOUD")
            put("orchestration_owner", "ANDROID_DIRECT")
            put("task_id", "task-7")
            put("task", buildJsonObject { put("state", "queued") })
        }
        val summary = GitHubSemanticSummary.from("github_pr", args, task)
        assertEquals(JsonPrimitive("queued"), summary.getValue("copilot_observation").jsonObject["phase"])
        assertFalse(GitHubSemanticSummary.from("github_pr", JsonObject(args + ("task_id" to JsonPrimitive("different"))), task).containsKey("copilot_observation"))
    }

    @Test fun pathOnlySearchKeepsTheQueryPinnedBlobAndIncompleteCoverageAfterCompaction() {
        val request = json("""{"query":"Conversation","mode":"paths","path_prefix":"app/src/","path_suffix":".kt","fuzzy_match":false}""")
        val source = json("""{"schema":"github.result.v1","data":{"commit_sha":"${"a".repeat(40)}","matches_complete":false,"items":[{"path":"app/src/ConversationIcon.kt","sha":"${"b".repeat(40)}"}],"returned":1}}""")
        val summary = GitHubSemanticSummary.from("github_search__account", request, source)
        val compact = GitHubSemanticSummary.bounded(summary, 1400)
        assertEquals(request, compact["search_identity"])
        assertEquals(JsonPrimitive("a".repeat(40)), compact["commit_sha"])
        assertEquals(JsonPrimitive(false), compact["search_complete"])
        assertEquals(JsonPrimitive(false), compact["source_read_verified"])
        val observation = compact.getValue("path_observations").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("app/src/ConversationIcon.kt"), observation["path"])
        assertEquals(JsonPrimitive("b".repeat(40)), observation["sha"])
        assertEquals(JsonPrimitive("/data/items/0"), observation["pointer"])
        assertTrue(compact.toString().length <= 1400)
    }

    @Test fun boundedPathPreviewNeverClaimsAllSixteenPathsAreStillVisible() {
        val source = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put("matches_complete", true)
            put(
                "items",
                JsonArray(
                    (1..16).map { index ->
                        buildJsonObject {
                            put("path", "app/src/" + "nested/".repeat(20) + "Conversation$index.kt")
                            put("sha", "b".repeat(40))
                        }
                    }
                )
            )
        }
        val summary = GitHubSemanticSummary.bounded(GitHubSemanticSummary.from("github_search", json("""{"query":"Conversation","mode":"paths"}"""), source), 1400)
        assertTrue(summary.toString().length <= 1400)
        assertEquals(JsonPrimitive(16), summary["paths_observed"])
        assertEquals(JsonPrimitive(false), summary["path_preview_complete"])
        assertEquals(JsonPrimitive(true), summary["search_complete"])
        assertTrue(summary.getValue("path_observations").jsonArray.isNotEmpty())
    }

    @Test fun omittedPreviewsNeverGrowPastTheExactBudgetWhenCompletenessChanges() {
        for (kind in listOf("path", "branch")) {
            val summary = buildJsonObject {
                put("${kind}_observations", JsonArray(listOf(buildJsonObject { put("path", "x".repeat(500)) })))
                put("${kind}_preview_complete", true)
                put("unrelated_metadata", "x".repeat(500))
            }
            for (budget in 40..150) {
                val bounded = GitHubSemanticSummary.bounded(summary, budget)
                assertTrue("$kind preview exceeded $budget characters", bounded.toString().length <= budget)
                assertFalse(bounded["${kind}_preview_complete"] == JsonPrimitive(true))
            }
        }
    }

    @Test fun branchObservationDoesNotTurnPageOneIntoCompleteInventory() {
        val summary = GitHubSemanticSummary.from("github_context", json("""{"action":"branches"}"""), json("""{"branches":[{"name":"feature/repair","commit":{"sha":"${"c".repeat(40)}"}}],"page":1,"has_more":true}"""))
        assertEquals(JsonPrimitive(false), summary["branch_inventory_complete"])
        assertEquals(JsonPrimitive("feature/repair"), summary.getValue("branch_observations").jsonArray.single().jsonObject["name"])
        assertEquals(JsonPrimitive("c".repeat(40)), summary.getValue("branch_observations").jsonArray.single().jsonObject["sha"])
    }

    @Test fun issueSymptomsSurviveWithTheirResourceIdentityAndExactSourcePointer() {
        val source = json("""{"schema":"github.result.v1","data":{"items":[{"number":2229,"title":"Verifier cast drops the final answer","state":"open","body":"A Long cannot be cast to Integer during final verification."}],"has_more":false}}""")
        val summary = GitHubSemanticSummary.from("github_issue", json("""{"action":"list"}"""), source)
        val issue = summary.getValue("resource_observations").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(2229), issue["number"])
        assertEquals(JsonPrimitive("Verifier cast drops the final answer"), issue["title"])
        assertEquals(JsonPrimitive("/data/items/0"), issue["pointer"])
        assertTrue(issue.getValue("reported_excerpt").toString().contains("during final verification"))
        assertEquals(JsonPrimitive(GitHubEvidenceBook.digest(source.toString())), summary["source_result_sha256"])
        assertTrue(summary.toString().length <= 2400)
    }

    @Test fun sourceContinuityRetainsAnExcerptWithoutUpgradingItToACompleteReadOrDiagnosis() {
        val content = "val next = currentIndex - 1\n".repeat(80)
        val source = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put(
                "items",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "src/Search.kt")
                            put("start_line", 754)
                            put("end_line", 833)
                            put("content", content)
                            put("range_complete", true)
                        }
                    )
                )
            )
        }
        val summary = GitHubSemanticSummary.from("github_read", json("""{"action":"ranges"}"""), source)
        val excerpt = summary.getValue("source_excerpts").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("src/Search.kt"), excerpt["path"])
        assertEquals(JsonPrimitive("/items/0/content"), excerpt["pointer"])
        assertEquals(JsonPrimitive(content.take(480)), excerpt["excerpt"])
        assertEquals(JsonPrimitive(false), excerpt["excerpt_complete"])
        assertEquals(JsonPrimitive("a".repeat(40)), summary["commit_sha"])
        assertFalse("completion_verified" in summary)
    }

    @Test fun zeroSearchMatchesRetainIncompleteCoverageAndActualProgressFieldNames() {
        val source = json("""{"commit_sha":"${"a".repeat(40)}","items":[],"returned":0,"scanned_files":0,"interval_start":206,"interval_end":206,"total_candidate_files":6279,"matches_complete":false,"stop_reason":"time_budget"}""")
        val summary = GitHubSemanticSummary.from("github_search", json("""{"query":"continuation"}"""), source)
        assertEquals(JsonPrimitive(false), summary["search_complete"])
        assertEquals(JsonPrimitive(206), summary["interval_end"])
        assertEquals(JsonPrimitive(6279), summary["total_candidate_files"])
        val complete = GitHubSemanticSummary.from("github_search", json("""{"query":"continuation"}"""), JsonObject(source + ("matches_complete" to JsonPrimitive(true))))
        assertEquals(JsonPrimitive(true), complete["search_complete"])
    }

    @Test fun oversizedLegacySummaryKeepsUsefulIssueObservationInsteadOfDroppingEverything() {
        val summary = buildJsonObject {
            put("schema", "github.semantic-summary.v1")
            put("operation", "github_issue")
            put("resource_observations", JsonArray(listOf(json("""{"number":42,"title":"Continue from the saved diagnosis","state":"open"}"""))))
            put("unrelated_metadata", "x".repeat(4000))
        }
        val bounded = GitHubSemanticSummary.bounded(summary)
        assertTrue(bounded.toString().length <= 2400)
        assertEquals(summary["resource_observations"], bounded["resource_observations"])
        assertEquals(JsonPrimitive(true), bounded["summary_truncated"])
    }

    @Test fun deferredSearchFilesRemainAnExplicitCoverageObligation() {
        val source = json("""{"matches_complete":false,"returned":0,"scanned_files":1,"total_deferred_files":1,"no_progress_attempts":2,"deferred_files":[{"path":"src/Slow.kt","sha":"${"b".repeat(40)}","reason":"REPEATED_READ_DEADLINE","attempts":2}]}""")
        val summary = GitHubSemanticSummary.from("github_search", json("""{"query":"retry"}"""), source)
        assertEquals(JsonPrimitive(false), summary["search_complete"])
        assertEquals(JsonPrimitive(1), summary["total_deferred_files"])
        assertEquals(JsonPrimitive(2), summary["no_progress_attempts"])
        assertEquals(JsonPrimitive("src/Slow.kt"), summary.getValue("deferred_files").jsonArray.single().jsonObject["path"])
    }
}
