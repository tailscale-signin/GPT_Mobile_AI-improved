package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompletionReplayTest {
    private val commit = "a".repeat(40)

    private fun source(vararg texts: String) = buildJsonObject {
        put("commit_sha", commit)
        put(
            "items",
            JsonArray(
                texts.mapIndexed { index, content ->
                    buildJsonObject {
                        put("path", "File$index.kt")
                        put("commit_sha", commit)
                        put("sha", GitHubCommitReconciliation.blobSha(content))
                        put("start_line", 1)
                        put("end_line", content.count { it == '\n' } + 1)
                        put("content", content)
                    }
                }
            )
        )
    }

    @Test fun savedRangesDeliverConsecutiveUnicodeSourceAcrossFilesAndRestore() {
        val texts = listOf((1..130).joinToString("\n") { "val line$it = \"shared read 📘\"" }, "α😀".repeat(1600), "")
        val data = source(*texts.toTypedArray())
        val args = buildJsonObject { put("commit_sha", commit) }
        var views = GitHubResultViews(pageBytes = 4096)
        var result = views.present("scope", "github_read", "github_read", AgentToolResult("batch", ToolResultContent.Json(data), false), args)
        val delivered = linkedMapOf<String, String>()
        val cursors = mutableSetOf<String>()
        do {
            val page = (result.content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive(true), page["source_page"])
            assertEquals(JsonPrimitive(commit), page["commit_sha"])
            assertTrue(page.toString().encodeToByteArray().size <= 4096)
            assertFalse("fragments" in page)
            page.getValue("items").jsonArray.forEach { raw ->
                val row = raw.jsonObject
                val path = row.getValue("path").jsonPrimitive.content
                val before = delivered[path].orEmpty()
                assertEquals(JsonPrimitive(before.length), row["range_offset"])
                assertEquals(JsonPrimitive(1 + before.count { it == '\n' }), row["start_line"])
                delivered[path] = before + row.getValue("content").jsonPrimitive.content
            }
            val cursor = (page["continuation_call"] as? JsonObject)?.get("arguments")?.jsonObject?.get("view_cursor")?.jsonPrimitive?.contentOrNull ?: break
            assertTrue(cursors.add(cursor))
            assertTrue("source should occupy most of each page", cursors.size < 10)
            val snapshot = requireNotNull(views.checkpoint("scope", result)).second
            views = GitHubResultViews(pageBytes = 4096)
            assertFalse(views.restore("other", "github_read", "github_read", cursor, snapshot))
            assertTrue(views.restore("scope", "github_read", "github_read", cursor, snapshot))
            result = views.read("scope", "github_read", "github_read", cursor, "saved")
            assertFalse(result.dispatched)
        } while (true)
        assertEquals(texts, delivered.values.toList())
    }

    @Test fun immutableCachedExcerptSurvivesProjectionWhileMutableOrMismatchedSourceStaysUnverified() {
        val result = source("val answer = 42")
        val raw = buildJsonObject {
            put("tool", "github_read")
            put("trusted", true)
            put("success", true)
            put("immutable", true)
            put("observed_at_ms", JsonNull)
            put("source_head_sha", commit)
            put("arguments", buildJsonObject { put("commit_sha", commit) })
            put("result", result)
            put("result_sha256", GitHubEvidenceBook.digest(result.toString()))
        }
        val claim = buildJsonObject {
            put("receipt_id", "r")
            put("pointer", "/items/0/content#L1-L1")
            put("value", "val answer = 42")
        }
        fun verify(row: JsonObject) = GitHubEvidencePolicy.verify(claim, mapOf("r" to row), 200_000, 4)
        for (row in listOf(raw, GitHubEvidenceProjection.receipt(raw))) {
            assertEquals(JsonPrimitive("VERIFIED"), verify(row)["status"])
            assertEquals(JsonPrimitive("PINNED_SOURCE_IDENTITY"), verify(row)["freshness_basis"])
            assertEquals(JsonNull, verify(row)["observed_at_ms"])
            for ((field, value) in mapOf("immutable" to JsonPrimitive(false), "source_head_sha" to JsonPrimitive("b".repeat(40)), "result_sha256" to JsonPrimitive("0".repeat(64)))) {
                assertEquals(JsonPrimitive("STALE"), verify(JsonObject(row + (field to value)))["status"])
            }
            assertEquals(JsonPrimitive("NOT_MEASURED"), verify(JsonObject(row + ("verification_eligible" to JsonPrimitive(false))))["status"])
        }
    }

    @Test fun expiredWindowRequiresExplicitFreshUserInstructionAndSurvivesRestart() = runBlocking {
        var clock = 1_000_000L
        val stored = mutableMapOf<String, JsonObject>()
        fun open() = GitHubEvidenceBook("task", "owner/repo", "main", emptySet(), now = { clock }, load = { stored[it] }, save = { k, v -> stored[k] = v }, latestDeadlineGrant = { stored["deadline_grant_13"] })
        val task = "Review the branch. Budget: 1 minute."
        val first = open()
        first.retainTaskContract(task, 10, emptyList(), 9)
        clock += 60_000
        first.authorizeTimeWindow("continue", 11, clock / 1000)
        assertEquals(0L, first.remainingTaskMillis())
        first.authorizeTimeWindow("Continue for 10 minutes", 13, clock / 1000)
        assertEquals(600_000L, first.remainingTaskMillis())
        val restored = open()
        restored.retainTaskContract(task, 10, emptyList(), 9)
        clock += 10_000
        restored.authorizeTimeWindow("Continue for 10 minutes", 13, clock / 1000)
        assertEquals(590_000L, restored.remainingTaskMillis())
        assertEquals(JsonPrimitive(1_060_000L), stored.getValue("task_contract")["deadline_at_ms"])
        assertEquals(task, restored.acceptedTaskRequest())
    }

    @Test fun knownPrNumbersAreTypedAndIsolatedFromOtherRepositoriesAndIssues() {
        fun receipt(tool: String, role: String = "WRITE_DESTINATION") = buildJsonObject {
            put("trusted", true)
            put("success", true)
            put("tool", tool)
            put("repository_role", role)
            put(
                "arguments",
                buildJsonObject {
                    put("action", "reviews")
                    put("pull_number", "1970")
                }
            )
            put("result", buildJsonObject { put("reviews", JsonArray(emptyList())) })
        }
        assertEquals(JsonPrimitive(1970), GitHubSelfTestReads.pullNumber(listOf(receipt("github_pr"))))
        assertNull(GitHubSelfTestReads.pullNumber(listOf(receipt("github_issue"), receipt("github_pr", "READ_SOURCE"))))
        assertNotNull(GitHubExecutionLimits.renewedDuration("Continue for 10 minutes"))
        assertNull(GitHubExecutionLimits.renewedDuration("The source says: continue for 10 minutes"))
    }
}
