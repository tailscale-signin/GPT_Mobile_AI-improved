package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLookupCompletionTest {
    private fun receipt(
        data: JsonObject,
        arguments: JsonObject = buildJsonObject {
            put("action", "list")
            put("state", "open")
        }
    ) = buildJsonObject {
        put("receipt_id", "source")
        put("repository", "owner/repo")
        put("repository_role", "WRITE_DESTINATION")
        put("tool", "github_pr__selected")
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("observed_at_ms", 1000)
        put("epoch", 0)
        put("arguments", arguments)
        put("result", data)
    }
    private fun empty(complete: Boolean = true) = buildJsonObject {
        put("pull_requests", JsonArray(emptyList()))
        put("page", 1)
        put("has_more", !complete)
        put("complete", complete)
    }
    private fun render(row: JsonObject) = GitHubLookupObservation.render("List open PRs", mapOf("source" to row), "owner/repo", "main", 1001, 0)

    @Test fun aCompleteEmptyQueryIsAnAnswerAndAnIncompleteQueryIsNot() {
        val (text, report) = requireNotNull(render(receipt(empty())))
        assertTrue(text.contains("No open pull requests"))
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        assertNull(render(receipt(empty(false))))
        assertNull(GitHubLookupObservation.render("Summarize open PRs", mapOf("source" to receipt(empty())), "owner/repo", "main", 1001, 0))
        assertNull(
            render(
                receipt(
                    buildJsonObject {
                        put("pull_requests", JsonArray(emptyList()))
                        put("page", 1)
                    }
                )
            )
        )
    }

    @Test fun sourceTrustRepositoryFreshnessEpochAndFullBodyAreRequired() {
        val valid = receipt(empty())
        for ((key, value) in listOf(
            "trusted" to JsonPrimitive(false),
            "success" to JsonPrimitive(false),
            "verification_eligible" to JsonPrimitive(false),
            "repository" to JsonPrimitive("owner/another"),
            "repository_role" to JsonPrimitive("READ_SOURCE"),
            "observed_at_ms" to JsonPrimitive(0),
            "epoch" to JsonPrimitive(1),
            "review_projection" to JsonPrimitive(true)
        )) {
            assertNull(key, render(JsonObject(valid + (key to value))))
        }
        assertNull(
            render(
                receipt(
                    empty(),
                    buildJsonObject {
                        put("action", "list")
                        put("state", "closed")
                    }
                )
            )
        )
        assertNull(
            render(
                receipt(
                    empty(),
                    buildJsonObject {
                        put("action", "list")
                        put("state", "open")
                        put("base", "other")
                    }
                )
            )
        )
    }

    @Test fun aLargeRequestedCountCannotBecomeCompleteBecauseOneApiPageWasCapped() {
        val data = buildJsonObject {
            put(
                "pull_requests",
                JsonArray(
                    (1..100).map { number ->
                        buildJsonObject {
                            put("number", number)
                            put("title", "PR $number")
                            put("state", "open")
                        }
                    }
                )
            )
            put("page", 1)
            put("has_more", true)
        }
        val args = buildJsonObject {
            put("action", "list")
            put("sort", "created")
            put("direction", "desc")
            put("per_page", 100)
        }
        val source = mapOf("source" to receipt(data, args))
        assertNull(GitHubLookupObservation.render("List the last 200 PRs", source, "owner/repo", "main", 1001, 0))
        assertNotNull(GitHubLookupObservation.render("List the last 5 PRs", source, "owner/repo", "main", 1001, 0))
    }
}
