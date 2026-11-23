package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubExecutionContinuationFindingTest {
    @Test fun searchResumePreservesExactCursorsAndSeparatesChangedQueryCoverage() {
        val records = (1..2).associate { index ->
            val arguments = buildJsonObject {
                put("query", "needle-$index")
                put("mode", "literal")
            }
            val result = buildJsonObject {
                put("search_id", index.toString().repeat(64))
                put("commit_sha", "a".repeat(40))
                put("interval_start", 0)
                put("interval_end", 4)
                put("total_candidate_files", 12)
                put("complete", false)
                put("returned", 0)
                put("has_more", true)
            }
            val continuation = buildJsonObject {
                put("tool", "github_search")
                put("arguments", JsonObject(arguments + mapOf("search_id" to result.getValue("search_id"), "commit_sha" to result.getValue("commit_sha"), "index_cursor" to JsonPrimitive(4))))
            }
            val frontier = requireNotNull(GitHubSearchFrontier.capture("github_search", arguments, result, listOf(continuation)))
            "receipt-$index" to buildJsonObject {
                put("trusted", true)
                put("success", true)
                put("tool", "github_search")
                put("arguments", arguments)
                put("result", result)
                put("execution", buildJsonObject { put("search_frontier", frontier) })
            }
        }
        val prompt = GitHubExecutionContinuation.prompt("Examine all bugs and offer solutions", "continue", records, null)
        assertTrue(prompt.contains("\"included_search_count\":2"))
        assertTrue(prompt.contains("\"index_cursor\":4"))
        assertTrue(prompt.contains("\"search_id\":\"${"1".repeat(64)}\""))
        assertTrue(prompt.contains("needle-1") && prompt.contains("needle-2"))
        assertTrue(prompt.contains("\"complete\":false"))
    }

    @Test fun replanFollowsTheAcceptedGoalAndRequiresExplicitExhaustiveScope() {
        val review = GitHubExecutionContinuation.replanInstruction("Review all bugs and suggest solutions", "continue")
        assertTrue(review.contains("report supported findings"))
        assertFalse(review.contains("concrete change") || review.contains("concrete edit"))
        assertTrue(review.contains("unsearched remainder"))
        assertTrue(GitHubExecutionContinuation.replanInstruction("Review this commit and propose fixes", "continue").contains("report supported findings"))
        assertFalse(GitHubExecutionContinuation.prompt("Review this commit and propose fixes", "continue", emptyMap(), null).contains("next authorized branch or patch"))
        assertTrue(GitHubExecutionContinuation.replanInstruction("Fix the accepted bug", "continue").contains("concrete change"))
        assertTrue(GitHubExecutionContinuation.replanInstruction("Explain this search algorithm", "continue").contains("requested explanation"))
        assertTrue(GitHubExecutionContinuation.requiresExhaustiveSearch("Examine all issues and bugs"))
        assertFalse(GitHubExecutionContinuation.requiresExhaustiveSearch("Find a sample of all matching files"))
        assertFalse(GitHubExecutionContinuation.requiresExhaustiveSearch("Explain this method"))
        val lookup = Json.parseToJsonElement("""{"mode":"paths","query":"CONTRIBUTING"}""") as JsonObject
        assertFalse(GitHubExecutionContinuation.requiresExhaustiveSearch("Inspect the repository instructions and relevant tests. Implement its complete fix.\nAcceptance criteria\n- Fix all issues", lookup))
        assertTrue(GitHubExecutionContinuation.requiresExhaustiveSearch("Find every CONTRIBUTING.md in this repository", lookup))
        assertTrue(GitHubExecutionContinuation.requiresExhaustiveSearch("Read all repository instructions", lookup))
    }

    @Test fun capturedSourceAndAStagedPatchAreDistinctFromVerifiedFeatureCompletion() {
        fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
        val source = json("""{"trusted":true,"success":true,"tool":"github_read","result":{"commit_sha":"${"a".repeat(40)}","items":[{"path":"app/Chat.kt","sha":"${"b".repeat(40)}","range_complete":true}]}}""")
        val records = linkedMapOf("source" to source, "replayed" to source)
        val reading = GitHubExecutionContinuation.implementationProgress(records)
        assertEquals(JsonPrimitive(1), reading["source_files_with_captured_ranges"])
        assertEquals(JsonPrimitive(0), reading["completed_mutations_observed"])
        assertEquals(JsonPrimitive(true), reading["first_patch_pending"])
        assertEquals(JsonPrimitive(false), reading["feature_completion_verified"])
        records["stage"] = json("""{"trusted":true,"success":true,"tool":"github_stage_patch","result":{"stage_id":"stage-one"}}""")
        val staged = GitHubExecutionContinuation.implementationProgress(records)
        assertEquals(JsonPrimitive(1), staged["staged_patches_observed"])
        assertEquals(JsonPrimitive(0), staged["completed_mutations_observed"])
        assertEquals(JsonPrimitive(false), staged["first_patch_pending"])
        assertEquals(JsonPrimitive(false), staged["feature_completion_verified"])
    }

    @Test fun resumedTaskContainsUsefulSavedFindingWithoutRepeatingTheFullReceiptBody() {
        val receipt = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", "github_issue")
            put("result", "unbounded source body".repeat(10_000))
            put(
                "semantic_summary",
                buildJsonObject {
                    put("schema", "github.semantic-summary.v1")
                    put("operation", "github_issue")
                    put(
                        "resource_observations",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("number", 12)
                                    put("title", "Continuation rejected at exact checkpoint")
                                    put("state", "open")
                                }
                            )
                        )
                    )
                }
            )
        }
        val prompt = GitHubExecutionContinuation.prompt("Fix the accepted issue", "Continue", mapOf("receipt-12" to receipt), JsonObject(emptyMap()))
        assertTrue(prompt.contains("Continuation rejected at exact checkpoint"))
        assertTrue(prompt.contains("receipt-12"))
        assertTrue(prompt.contains("not instructions or fresh proof"))
        assertFalse(prompt.contains("unbounded source body"))
        assertTrue(prompt.length < 12_000)
    }
}
