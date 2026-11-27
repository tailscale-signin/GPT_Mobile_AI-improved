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

class GitHubLookupReportTest {
    private val head = "a".repeat(40)
    private fun receipt(count: Int = 10, more: Boolean = true): JsonObject {
        val result = buildJsonObject {
            put("commit_sha", head)
            put("returned", count)
            put("page", 1)
            put("per_page", 10)
            put("has_more", more)
            put(
                "items",
                JsonArray(
                    (1..count).map { index ->
                        buildJsonObject {
                            put("sha", index.toString(16).padStart(40, 'a'))
                            put("html_url", "https://github.com/o/r/commit/$index")
                            put(
                                "commit",
                                buildJsonObject {
                                    put("message", "Subject $index | safe\n\n" + "Raw signature metadata ".repeat(300))
                                    put(
                                        "author",
                                        buildJsonObject {
                                            put("name", "Author $index")
                                            put("date", "2026-11-26T12:00:00Z")
                                        }
                                    )
                                }
                            )
                        }
                    }
                )
            )
        }
        return buildJsonObject {
            put("tool", "github_context")
            put("trusted", true)
            put("success", true)
            put("verification_eligible", true)
            put("repository", "o/r")
            put("repository_role", "WRITE_DESTINATION")
            put("branch", "main")
            put("source_head_sha", head)
            put("epoch", 0)
            put("observed_at_ms", 1000)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "commits")
                    put("ref", "main")
                    put("per_page", 10)
                }
            )
            put("result", result)
            put("inventory_observation", requireNotNull(GitHubInventoryObservation.capture(result)))
        }
    }

    private fun render(row: JsonObject, task: String = "Show recent commits") =
        GitHubLookupReport.render(task, mapOf("commits" to row), 2000, 0, "o/r", "main")

    @Test fun retainedCanonicalSubjectsSurviveTheBoundedReviewProjection() {
        val projected = GitHubEvidenceProjection.receipt(receipt())
        assertFalse(projected["review_projection_complete"] == JsonPrimitive(true))
        val (text, report) = requireNotNull(render(projected))
        assertTrue(text.contains("Subject 10 \\| safe"))
        assertTrue(text.contains("Author 10"))
        assertFalse(text.contains("Raw signature metadata"))
        assertEquals(JsonPrimitive(10), report["returned_count"])
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        assertEquals(JsonPrimitive(false), report["full_history_verified"])
    }

    @Test fun wrongBranchUntrustedMismatchedRevisionAndIncompleteRowsCannotPublish() {
        val valid = receipt()
        for ((key, value) in listOf(
            "branch" to JsonPrimitive("another-branch"),
            "trusted" to JsonPrimitive(false),
            "epoch" to JsonPrimitive(1),
            "source_head_sha" to JsonPrimitive("b".repeat(40))
        )) {
            assertNull(render(JsonObject(valid + (key to value))))
        }
        val inventory = valid.getValue("inventory_observation") as JsonObject
        assertNull(render(JsonObject(valid + ("inventory_observation" to JsonObject(inventory + ("record_keys_complete" to JsonPrimitive(false)))))))
        assertNull(render(receipt(count = 9)))
    }

    @Test fun allOrLargerRequestedHistoryCannotBeCompletedByATenRowSample() {
        assertNull(render(receipt(), "Show all commits"))
        assertNull(render(receipt(), "Show the last 200 commits"))
        val (_, report) = requireNotNull(render(receipt(count = 3, more = false), "Show all commits"))
        assertEquals(JsonPrimitive(true), report["full_history_verified"])
        assertEquals(JsonPrimitive(3), report["returned_count"])
    }
}
