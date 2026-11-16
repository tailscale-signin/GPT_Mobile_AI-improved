package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolObservationRetention
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

class GitHubPaginationBoundaryTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun repositoryRecoveryUsesTheSameAuthorizedConnectionAlias() = runBlocking {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Json(json("""{"schema":"github.repository-admission.v1","dispatched":false,"recovery_call":{"tool":"github_context","arguments":{"action":"resolve","owner":"o","repo":"r","refresh":true}}}""")),
                true,
                errorCode = "GITHUB_REPOSITORY_UNAVAILABLE",
                dispatched = false
            )
        }
        val tool = GitHubOperationTool("github_ci", backend, "scoped-recovery", "github_ci__selected")
        val result = tool.execute("blocked", JsonObject(emptyMap()))
        assertFalse(result.dispatched)
        val recovery = (result.content as ToolResultContent.Json).value.jsonObject.getValue("data").jsonObject.getValue("recovery_call").jsonObject
        assertEquals(JsonPrimitive("github_context__selected"), recovery["tool"])
        assertEquals(json("""{"action":"resolve","owner":"o","repo":"r","refresh":true}"""), recovery["arguments"])
    }

    @Test fun reorderedNonAdjacentDuplicatePagesCannotInventTraversalProgress() {
        val boundary = GitHubPaginationBoundary()
        fun args(page: Int) = json("""{"action":"list_workflows","page":$page,"per_page":2}""")
        assertFalse(boundary.repeatsRemotePage("github_ci", args(1), json("""{"workflows":[{"id":1},{"id":2}]}""")))
        assertFalse(boundary.repeatsRemotePage("github_ci", args(2), json("""{"workflows":[{"id":3},{"id":4}]}""")))
        val repeated = boundary.assessPage("github_ci", args(3), json("""{"has_more":true,"total_count":8,"workflows":[{"id":2},{"id":1}]}"""))
        assertEquals("GITHUB_PAGINATION_STALLED", repeated.errorCode)
        val progress = repeated.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(4), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(0), progress["new_records_on_page"])
        assertEquals(JsonPrimitive(false), progress["complete"])
        assertFalse(repeated.data.containsKey("continuation"))
    }

    @Test fun allRunPagesShareCoverageWhenNativeContinuationsAddWorkspaceRef() {
        val boundary = GitHubPaginationBoundary()
        var final: GitHubPaginationBoundary.Assessment? = null
        for (page in 1..7) {
            val args = buildJsonObject {
                put("action", "list_workflow_runs")
                put("owner", if (page == 1) "Owner" else "owner")
                put("repo", "repo")
                put("commit_sha", "a".repeat(40))
                put("per_page", 30)
                put("page", page)
                if (page > 1) put("ref", "main")
            }
            val data = buildJsonObject {
                put("has_more", page < 7)
                put("total_count", 206)
                put("workflow_runs", JsonArray(((page - 1) * 30 + 1..minOf(page * 30, 206)).map { buildJsonObject { put("id", it) } }))
            }
            final = boundary.assessPage("github_ci", args, data)
            assertEquals(null, final.errorCode)
        }
        val progress = requireNotNull(final).data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(206), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["contiguous_from_first_page"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonPrimitive(false), progress["upstream_changed_during_traversal"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), progress["acceptance_status"])
    }

    @Test fun aDifferentRunFilterCannotBorrowAnotherTraversalFirstPage() {
        val baseline = json("""{"action":"list_workflow_runs","owner":"owner","repo":"repo","commit_sha":"${"a".repeat(40)}","branch":"main","status":"failure","workflow_id":"build.yml","per_page":30}""")
        for ((field, changed) in mapOf("repo" to "other", "commit_sha" to "b".repeat(40), "branch" to "release", "status" to "success", "workflow_id" to "release.yml")) {
            val boundary = GitHubPaginationBoundary()
            boundary.assessPage("github_ci", baseline, json("""{"has_more":true,"total_count":2,"workflow_runs":[{"id":1}]}"""))
            val next = JsonObject(baseline + mapOf(field to JsonPrimitive(changed), "page" to JsonPrimitive(2), "ref" to JsonPrimitive("main")))
            val observed = boundary.assessPage("github_ci", next, json("""{"has_more":false,"total_count":2,"workflow_runs":[{"id":2}]}"""))
            val progress = observed.data.getValue("pagination_progress").jsonObject
            assertEquals(field, JsonPrimitive(1), progress["unique_records_observed"])
            assertEquals(field, JsonPrimitive(false), progress["complete"])
        }
    }

    @Test fun blankCommitAliasDoesNotHideDistinctNativeHeadFilters() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"list_workflow_runs","owner":"owner","repo":"repo","commit_sha":"","head_sha":"${"a".repeat(40)}","per_page":30}""")
        boundary.assessPage("github_ci", first, json("""{"has_more":true,"total_count":2,"workflow_runs":[{"id":1}]}"""))
        val next = JsonObject(first + mapOf("head_sha" to JsonPrimitive("b".repeat(40)), "page" to JsonPrimitive(2)))
        val observed = boundary.assessPage("github_ci", next, json("""{"has_more":false,"total_count":2,"workflow_runs":[{"id":2}]}"""))
        val progress = observed.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(1), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(false), progress["complete"])
    }

    @Test fun partialOverlapsAreDeduplicatedAndAnUnaccountedReportedTotalStaysIncomplete() {
        val boundary = GitHubPaginationBoundary()
        boundary.assessPage("github_ci", json("""{"action":"list_workflows","page":1,"per_page":2}"""), json("""{"has_more":true,"total_count":4,"workflows":[{"id":1},{"id":2}]}"""))
        val final = boundary.assessPage("github_ci", json("""{"action":"list_workflows","page":2,"per_page":2}"""), json("""{"has_more":false,"total_count":4,"workflows":[{"id":2},{"id":3}]}"""))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", final.errorCode)
        assertEquals(json("""{"workflows":[{"id":3}]}""")["workflows"], final.data["workflows"])
        assertEquals(JsonPrimitive(3), final.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        assertEquals(JsonPrimitive(false), final.data.getValue("pagination_progress").jsonObject["complete"])
    }

    @Test fun omittedWorkflowDefaultsAndExplicitContinuationShareOneTraversal() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                requests += arguments
                val body = if (arguments["page"] == JsonPrimitive(2)) {
                    """{"has_more":false,"total_count":2,"workflows":[{"id":2}]}"""
                } else {
                    """{"has_more":true,"total_count":2,"workflows":[{"id":1}],"continuation":{"action":"list_workflows","page":2,"per_page":30}}"""
                }
                return AgentToolResult(callId, ToolResultContent.Json(json(body)), false)
            }
        }
        val tool = GitHubOperationTool("github_ci", backend, "exact-pagination")
        val first = tool.execute("first", JsonObject(emptyMap()))
        val continuation = (first.content as ToolResultContent.Json).value.jsonObject.getValue("continuation_call").jsonObject
        assertEquals(JsonPrimitive("github_ci"), continuation["tool"])
        val final = tool.execute("second", continuation.getValue("arguments").jsonObject)
        assertFalse(final.toString(), final.isError)
        val progress = final.executionReceipt!!.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(2), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), progress["acceptance_status"])
        assertEquals(listOf(JsonPrimitive("list_workflows"), JsonPrimitive("list_workflows")), requests.map { it["action"] })
    }

    @Test fun repeatedRemoteRowsStopTraversalButDistinctPagesAndLocalFragmentsRemainAllowed() = runBlocking {
        var requests = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                requests++
                return AgentToolResult(callId, ToolResultContent.Json(json("""{"has_more":true,"workflow_runs":[{"id":1}]}""")), false)
            }
        }
        val tool = GitHubOperationTool("github_ci", backend, "repeated-pages")
        assertFalse(tool.execute("first", json("""{"action":"runs","page":1}""")).isError)
        assertEquals("GITHUB_PAGINATION_STALLED", tool.execute("repeat", json("""{"action":"runs","page":2}""")).errorCode)
        val blocked = tool.execute("later", json("""{"action":"runs","page":3}"""))
        assertEquals("GITHUB_PAGINATION_STALLED", blocked.errorCode)
        assertFalse(blocked.dispatched)
        assertEquals(2, requests)
        assertFalse(tool.execute("refresh", json("""{"action":"runs","page":1}""")).isError)
        val boundary = GitHubPaginationBoundary()
        assertFalse(boundary.repeatsRemotePage("github_ci", json("""{"page":1}"""), json("""{"workflow_runs":[1]}""")))
        assertFalse(boundary.repeatsRemotePage("github_ci", json("""{"page":2}"""), json("""{"workflow_runs":[2]}""")))
        assertFalse(boundary.repeatsRemotePage("github_ci", json("""{"view_cursor":"saved:1"}"""), json("""{"workflow_runs":[2]}""")))
    }

    @Test fun verifiedSecondPageCannotInheritTheOldFirstPageTraversal() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"runs","owner":"owner","repo":"repo","page":1,"per_page":2}""")
        val second = json("""{"action":"runs","owner":"owner","repo":"repo","page":2,"per_page":2,"verify_live":true}""")
        boundary.assessPage("github_ci", first, json("""{"has_more":true,"total_count":2,"workflow_runs":[{"id":1}]}"""))
        assertEquals(null, boundary.completedPage("github_ci", second))
        val fresh = boundary.assessPage("github_ci", second, json("""{"has_more":false,"total_count":2,"workflow_runs":[{"id":2}]}"""))
        val progress = fresh.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(1), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(false), progress["contiguous_from_first_page"])
        assertEquals(JsonPrimitive(false), progress["complete"])
        assertEquals(JsonArray(listOf(JsonPrimitive(2))), progress["remote_pages_observed"])
    }

    @Test fun terminalPagePreventsRemoteOverrunButAllowsRefreshAndDifferentScope() {
        var time = 0L
        val boundary = GitHubPaginationBoundary { time }
        val first = json("""{"action":"runs","owner":"o","repo":"r","workflow_id":"build.yml","page":1}""")
        val second = JsonObject(first + ("page" to JsonPrimitive(2)))
        boundary.observe("github_ci", first, json("""{"has_more":false}"""))
        assertEquals(1, boundary.completedPage("github_ci", second))
        assertEquals(null, boundary.completedPage("github_ci", JsonObject(second + ("workflow_id" to JsonPrimitive("release.yml")))))
        assertEquals(null, boundary.completedPage("github_ci", JsonObject(second + ("repo" to JsonPrimitive("other")))))
        assertEquals(null, boundary.completedPage("github_ci", JsonObject(second + ("view_cursor" to JsonPrimitive("local")))))
        time = 30_000_000_001L
        assertEquals(null, boundary.completedPage("github_ci", second))
        boundary.observe("github_ci", first, json("""{"has_more":false}"""))
        assertEquals(null, boundary.completedPage("github_ci", first))
        assertEquals(null, boundary.completedPage("github_ci", second))
    }

    @Test fun operationStopsAfterLastRemotePageAndRetainsMeasuredTrace() = runBlocking {
        var requests = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                requests++
                return AgentToolResult(callId, ToolResultContent.Json(json("""{"has_more":false,"workflow_runs":[]}""")), false)
            }
        }
        val tool = GitHubOperationTool("github_ci", backend, "scope")
        val first = tool.execute("one", json("""{"action":"runs","page":1}"""))
        assertTrue(first.executionReceipt!!.containsKey("tool_trace"))
        val next = tool.execute("two", json("""{"action":"runs","page":2}"""))
        assertFalse(next.dispatched)
        assertFalse(next.isError)
        assertEquals(1, requests)
        assertEquals(JsonPrimitive(true), (next.content as ToolResultContent.Json).value.jsonObject["pagination_complete"])
        tool.execute("refresh", json("""{"action":"runs","page":1}"""))
        assertEquals(2, requests)
        val invalid = tool.execute("ambiguous", json("""{"action":"status","workflow_id":"build.yml"}"""))
        assertEquals("INVALID_ARGUMENTS", invalid.errorCode)
        assertFalse(invalid.dispatched)
        assertEquals(2, requests)
    }

    @Test fun retainedJsonTextRemainsStructuredAndBounded() {
        val original = ToolResultContent.Text("""{"items":[${List(3000) { "\"${"😀".repeat(20)}\"" }.joinToString(",")}]}""")
        val bounded = ToolObservationRetention.content(original, 65536)
        assertTrue(bounded is ToolResultContent.Json)
        val text = (bounded as ToolResultContent.Json).value.toString()
        assertTrue(text.toByteArray().size <= 65536)
        assertEquals(JsonPrimitive(true), Json.parseToJsonElement(text).jsonObject["observationTruncated"])
    }

    @Test fun savedViewContextMismatchCorrectsToTheIssuingToolAlias() {
        val views = GitHubResultViews()
        val originalArguments = json("""{"action":"list","owner":"owner","repo":"repo"}""")
        val original = AgentToolResult(
            "original",
            ToolResultContent.Json(json("""{"items":[{"id":1}],"has_more":false}""")),
            false
        )
        val presented = views.present("authorized-scope", "github_pr", "github_pr__original", original, originalArguments)
        val initial = (presented.content as ToolResultContent.Json).value.jsonObject
        val cursor = initial.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject
            .getValue("view_cursor") as JsonPrimitive

        val rejected = views.read(
            "authorized-scope",
            "github_pr",
            "github_pr__other",
            cursor.content,
            "mismatched-context",
            json("""{"action":"context","owner":"owner","repo":"repo"}""")
        )
        assertFalse(rejected.dispatched)
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        val body = (rejected.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive("SAVED_VIEW_CONTEXT_MISMATCH"), body["reason"])
        val corrected = body.getValue("correction_call").jsonObject
        assertEquals(JsonPrimitive("github_pr__original"), corrected["tool"])
        assertEquals(cursor, corrected.getValue("arguments").jsonObject["view_cursor"])
        val identity = body.getValue("recovery_identity").jsonObject
        assertEquals(JsonPrimitive("github_pr__original"), identity["original_model_tool"])
        assertEquals(JsonPrimitive("github_pr__other"), identity["requested_model_tool"])
        assertEquals(JsonPrimitive(false), identity["mutation_replay_allowed"])
        assertFalse(rejected.executionReceipt.toString().contains(cursor.content))
    }

    @Test fun savedReadsAcceptMatchingSelectorsAndRejectScopeChanges() {
        val views = GitHubResultViews()
        val request = json("""{"action":"runs","owner":"o","repo":"r","workflow_id":"build.yml","page":1,"per_page":20}""")
        val original = AgentToolResult("first", ToolResultContent.Json(json("""{"workflow_runs":[],"has_more":false}""")), false)
        val page = (views.present("scope", "github_ci", "github_ci", original, request).content as ToolResultContent.Json).value.jsonObject
        val cursor = (page.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor") as JsonPrimitive).content
        val carried = JsonObject(request + ("response_detail" to JsonPrimitive("compact")))
        val resumed = views.read("scope", "github_ci", "github_ci", cursor, "next", carried)
        assertFalse(resumed.isError)
        assertFalse(resumed.dispatched)
        for ((key, value) in mapOf("repo" to JsonPrimitive("other"), "workflow_id" to JsonPrimitive("release.yml"), "page" to JsonPrimitive(2))) {
            assertTrue(views.read("scope", "github_ci", "github_ci", cursor, "bad", JsonObject(carried + (key to value))).isError)
        }
        assertTrue(views.read("other-scope", "github_ci", "github_ci", cursor, "foreign", carried).isError)
    }
}
