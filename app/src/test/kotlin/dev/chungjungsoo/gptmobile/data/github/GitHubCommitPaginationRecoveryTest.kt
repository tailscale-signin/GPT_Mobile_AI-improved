package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubPaginationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCommitPaginationRecoveryTest {
    private val head = "a".repeat(40)
    private val name = "github_context__selected"
    private val request = buildJsonObject {
        put("action", "commits")
        put("owner", "owner")
        put("repo", "project")
        put("ref", "main")
        put("page", 68)
        put("per_page", 100)
    }
    private val arguments = JsonObject(request + mapOf("page" to JsonPrimitive(3), "ref" to JsonPrimitive(head), "commit_sha" to JsonPrimitive(head)))
    private val correction = buildJsonObject {
        put("tool", name)
        put("arguments", arguments)
    }
    private val progress = buildJsonObject {
        put("schema", "github.pagination-progress.v1")
        put("remote_page", 68)
        put("terminal_page", 68)
        put("remote_pages_observed", JsonArray(listOf(1, 2, 68).map(::JsonPrimitive)))
        put("first_unobserved_page", 3)
        put("unique_records_observed", 222)
        put("duplicate_records_on_page", 0)
        put("contiguous_from_first_page", false)
        put("upstream_changed_during_traversal", false)
        put("complete", false)
    }
    private val data = buildJsonObject {
        put("commit_sha", head)
        put("correction_call", correction)
        put("pagination_progress", progress)
        put("complete", false)
        put("has_more", false)
    }
    private fun receipt(arguments: JsonObject, result: JsonObject, success: Boolean) = buildJsonObject {
        put("trusted", true)
        put("dispatched", true)
        put("success", success)
        put("tool", name)
        put("binding", "accepted-task")
        put("repository", "owner/project")
        put("arguments", arguments)
        put("result", result)
        if (!success) put("error_code", "GITHUB_PAGINATION_INCOMPLETE")
    }
    private fun observations() = linkedMapOf(
        "page1" to receipt(JsonObject(request + ("page" to JsonPrimitive(1))), JsonObject(emptyMap()), true),
        "page2" to receipt(JsonObject(request + ("page" to JsonPrimitive(2))), JsonObject(emptyMap()), true),
        "terminal68" to receipt(request, data, false)
    )

    @Test fun terminalPage68WithOnlyThreeObservedPagesStillFailsCoverage() {
        val boundary = GitHubPaginationBoundary()
        fun native(page: Int) = JsonObject(request + mapOf("action" to JsonPrimitive("list_commits"), "page" to JsonPrimitive(page), "ref" to JsonPrimitive(head)))
        fun page(start: Int, count: Int, more: Boolean) = buildJsonObject {
            put("commit_sha", head)
            put("has_more", more)
            put("items", JsonArray((start until start + count).map { index -> buildJsonObject { put("sha", index.toString(16).padStart(40, '0')) } }))
        }
        assertEquals(null, boundary.assessPage("github_context", native(1), page(1, 100, true)).errorCode)
        assertEquals(null, boundary.assessPage("github_context", native(2), page(101, 100, true)).errorCode)
        val terminal = boundary.assessPage("github_context", native(68), page(6701, 22, false))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", terminal.errorCode)
        assertEquals(JsonPrimitive(false), terminal.data["complete"])
        val observed = terminal.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(222), observed["unique_records_observed"])
        assertEquals(JsonPrimitive(0), observed["duplicate_records_on_page"])
        assertEquals(JsonPrimitive(false), observed["contiguous_from_first_page"])
        assertEquals(JsonPrimitive(false), observed["upstream_changed_during_traversal"])
        assertEquals(JsonPrimitive(3), observed["first_unobserved_page"])
    }

    @Test fun commitGapIsRetainedWithoutTurningASmokeTestIntoAFullHistoryTraversal() {
        val records = observations()
        val tools = listOf(GitHubOperationTool.definition("github_context", name))
        assertEquals(listOf(correction), GitHubTestChecklist.pendingPages(records))
        val sampled = GitHubTestChecklist.paginationResume("Test all GitHub tools", records, tools, 100)
        assertEquals(JsonPrimitive("BOUNDED_SMOKE_SAMPLE"), sampled["mode"])
        assertEquals(JsonPrimitive(2), sampled["remote_samples_spent"])
        assertEquals(JsonPrimitive(false), sampled["complete"])
        assertEquals(JsonPrimitive(1), sampled["pending_count"])
        assertFalse("next_call" in sampled)
        val exhaustive = "Test all GitHub tools and complete remaining pagination"
        assertEquals(correction, GitHubTestChecklist.paginationResume(exhaustive, records, tools, 100)["next_call"])
        assertFalse("next_call" in GitHubTestChecklist.paginationResume(exhaustive, records, emptyList(), 100))
        assertFalse("next_call" in GitHubTestChecklist.paginationResume(exhaustive, records, tools, 0))
        records["corrected"] = receipt(arguments, JsonObject(emptyMap()), true)
        assertTrue(GitHubTestChecklist.pendingPages(records.toMap()).isEmpty())
        assertFalse("next_call" in GitHubTestChecklist.paginationResume(exhaustive, records.toMap(), tools, 100))
    }

    @Test fun largeCommitRowsCannotEraseThePinnedGapRecipe() {
        val large = buildJsonObject {
            put("items", JsonArray(List(100) { buildJsonObject { put("message", "x".repeat(1024)) } }))
            data.forEach { (key, value) -> put(key, value) }
        }
        val projected = GitHubEvidenceProjection.receipt(receipt(request, large, false))
        assertEquals(JsonPrimitive(false), projected["review_projection_complete"])
        assertEquals(correction, projected.getValue("result").jsonObject["correction_call"])
        assertEquals(progress, projected.getValue("result").jsonObject["pagination_progress"])
        assertEquals(listOf(correction), GitHubTestChecklist.pendingPages(mapOf("projected" to projected)))
        assertEquals(JsonPrimitive(head), projected.getValue("result").jsonObject["commit_sha"])
    }

    @Test fun commitGapCannotChangeConnectionQueryOrAnAlreadyPinnedRevision() {
        fun pending(body: JsonObject = data, original: JsonObject = request, trusted: Boolean = true): List<JsonObject> =
            GitHubTestChecklist.pendingPages(mapOf("failure" to JsonObject(receipt(original, body, false) + ("trusted" to JsonPrimitive(trusted)))))
        for ((field, value) in listOf("owner" to JsonPrimitive("elsewhere"), "repo" to JsonPrimitive("other"), "per_page" to JsonPrimitive(30), "page" to JsonPrimitive(4), "ref" to JsonPrimitive("main"))) {
            val changed = JsonObject(correction + ("arguments" to JsonObject(arguments + (field to value))))
            assertTrue(field, pending(JsonObject(data + ("correction_call" to changed))).isEmpty())
        }
        val otherConnection = JsonObject(correction + ("tool" to JsonPrimitive("github_context__other")))
        assertTrue(pending(JsonObject(data + ("correction_call" to otherConnection))).isEmpty())
        assertTrue(pending(original = JsonObject(request + ("commit_sha" to JsonPrimitive("b".repeat(40))))).isEmpty())
        assertTrue(pending(original = JsonObject(request + ("ref" to JsonPrimitive("b".repeat(40))))).isEmpty())
        assertTrue(pending(original = JsonObject(request + mapOf("commit_sha" to JsonPrimitive(head), "ref" to JsonPrimitive("b".repeat(40))))).isEmpty())
        assertTrue(pending(JsonObject(data - "commit_sha")).isEmpty())
        assertTrue(pending(JsonObject(data + ("pagination_progress" to JsonObject(progress + ("upstream_changed_during_traversal" to JsonPrimitive(true)))))).isEmpty())
        assertTrue(pending(trusted = false).isEmpty())
    }

    @Test fun immutableShaCaseIsValidatedWithoutRewritingTheIssuedRecipe() {
        val uppercase = JsonPrimitive(head.uppercase())
        val exact = JsonObject(correction + ("arguments" to JsonObject(arguments + mapOf("ref" to uppercase, "commit_sha" to uppercase))))
        val body = JsonObject(data + mapOf("commit_sha" to uppercase, "correction_call" to exact))
        assertEquals(listOf(exact), GitHubTestChecklist.pendingPages(mapOf("failure" to receipt(request, body, false))))
    }
}
