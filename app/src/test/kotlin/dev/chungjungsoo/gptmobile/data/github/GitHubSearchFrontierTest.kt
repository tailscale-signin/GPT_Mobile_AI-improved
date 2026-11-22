package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSearchFrontierTest {
    private val name = "github_search__native"
    private val commit = "a".repeat(40)
    private val id = "b".repeat(64)
    private val arguments = buildJsonObject {
        put("query", "needle")
        put("owner", "owner")
        put("repo", "repo")
    }
    private val next = JsonObject(arguments + mapOf("commit_sha" to JsonPrimitive(commit), "search_id" to JsonPrimitive(id), "index_cursor" to JsonPrimitive(4)))
    private val recipe = buildJsonObject {
        put("tool", name)
        put("arguments", next)
    }
    private val data = buildJsonObject {
        put("search_id", id)
        put("commit_sha", commit)
        put("interval_start", 0)
        put("interval_end", 4)
        put("total_candidate_files", 10)
        put("returned", 0)
        put("complete", false)
        put("matches_complete", false)
        put("coverage_from_start", true)
        put("has_more", true)
        put("stop_reason", "download_budget")
    }

    private fun receipt(frontier: JsonObject) = buildJsonObject {
        put("schema", "github.dispatch.v1")
        put("resolved_arguments", JsonObject(arguments + ("action" to JsonPrimitive("search_repository"))))
        put(
            "resource_context",
            buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
            }
        )
        put("search_frontier", frontier)
    }

    @Test fun zeroHitPartialPageKeepsItsExactIssuedContinuationAndIncompleteCoverage() {
        val frontier = requireNotNull(GitHubSearchFrontier.capture(name, arguments, data, listOf(recipe)))
        assertEquals(JsonPrimitive(false), frontier["complete"])
        assertEquals(JsonPrimitive(0), frontier["returned"])
        assertEquals(recipe, frontier["continuation_call"])
        assertEquals(recipe, GitHubSearchFrontier.continuation(name, arguments, receipt(frontier)))
        assertEquals(recipe, GitHubSearchFrontier.continuation(name, JsonObject(arguments - setOf("owner", "repo")), receipt(frontier)))
        assertFalse(frontier.toString().contains("matches_complete\":true"))
    }

    @Test fun changedQueryScopeCursorOrActionCannotBorrowTheOriginalContinuation() {
        val frontier = requireNotNull(GitHubSearchFrontier.capture(name, arguments, data, listOf(recipe)))
        for ((key, value) in listOf("query" to JsonPrimitive("different"), "repo" to JsonPrimitive("other"), "index_cursor" to JsonPrimitive(1), "commit_sha" to JsonPrimitive("c".repeat(40)))) {
            assertNull(GitHubSearchFrontier.continuation(name, JsonObject(arguments + (key to value)), receipt(frontier)))
        }
        val write = JsonObject(recipe + ("arguments" to JsonObject(next + ("action" to JsonPrimitive("commit_files")))))
        assertNull(GitHubSearchFrontier.capture(name, arguments, data, listOf(write))?.get("continuation_call"))
        val forgedScope = JsonObject(
            receipt(frontier) + (
                "resource_context" to buildJsonObject {
                    put("owner", "owner")
                    put("repo", "other")
                }
                )
        )
        assertNull(GitHubSearchFrontier.continuation(name, arguments, forgedScope))
        val changedArguments = JsonObject(arguments + ("query" to JsonPrimitive("different")))
        val changedData = JsonObject(data + ("search_id" to JsonPrimitive("d".repeat(64))))
        val changed = requireNotNull(GitHubSearchFrontier.capture(name, changedArguments, changedData, emptyList()))
        assertNotEquals(frontier["search_id"], changed["search_id"])
        assertNotEquals(frontier["query_identity_sha256"], changed["query_identity_sha256"])
    }

    @Test fun terminalIncompleteCoverageAndStalledPagesDoNotInventAnAutomaticRead() {
        val stalled = JsonObject(data + ("interval_end" to JsonPrimitive(0)))
        assertNull(GitHubSearchFrontier.capture(name, arguments, stalled, listOf(recipe))?.get("continuation_call"))
        val terminal = JsonObject(data + mapOf("interval_end" to JsonPrimitive(10), "has_more" to JsonPrimitive(false), "total_deferred_files" to JsonPrimitive(1)))
        val frontier = requireNotNull(GitHubSearchFrontier.capture(name, arguments, terminal, emptyList()))
        assertNotNull(frontier["query_identity"])
        assertEquals(JsonPrimitive(false), frontier["complete"])
        assertEquals(JsonPrimitive(1), frontier["total_deferred_files"])
        assertNull(GitHubSearchFrontier.continuation(name, arguments, receipt(frontier)))
    }
}
