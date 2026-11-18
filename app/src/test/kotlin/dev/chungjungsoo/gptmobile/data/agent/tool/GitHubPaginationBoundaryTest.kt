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

    @Test fun singletonContextsPreserveNestedExpansionWithoutRemotePagination() {
        val boundary = GitHubPaginationBoundary()
        val source = json("""{"has_more":true,"pull_request":{"number":1349},"branch":{"name":"main"}}""")
        for ((operation, action) in listOf(
            "github_pr" to "pr_context",
            "github_context" to "get_commit_detail",
            "github_context" to "branch_dependencies"
        )) {
            val args = json("""{"action":"$action","verify_live":true}""")
            assertFalse(boundary.expectsNumberedRemotePage(operation, args))
            val result = boundary.assessPage(operation, args, source)
            assertEquals(null, result.errorCode)
            assertEquals(source, result.data)
            assertFalse(result.data.containsKey("pagination_progress"))
            assertEquals(null, boundary.completedPage(operation, json("""{"action":"$action","page":2}""")))
        }
        assertTrue(boundary.expectsNumberedRemotePage("github_ci", json("""{"action":"runs"}""")))
        val invalid = boundary.assessPage("github_ci", json("""{"action":"runs"}"""), json("""{"has_more":true,"total_count":1}"""))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", invalid.errorCode)
    }

    @Test fun requestedPrFilesCannotReportAnEmptyTerminalPageWith82ChangedFiles() {
        val boundary = GitHubPaginationBoundary()
        for (page in listOf(1, 3)) {
            val arguments = json("""{"action":"pr_context","pull_number":1534,"page":$page,"verify_live":true}""")
            val output = json(
                """{"files_total":82,"files_included":true,"files_returned":0,"files":[],"page":$page,"has_more":false,"files_continuation":{"page":4}}"""
            )
            val assessed = boundary.assessPage("github_pr", arguments, output)
            assertEquals("GITHUB_PAGINATION_INCOMPLETE", assessed.errorCode)
            assertEquals(JsonPrimitive("MISSING_REMOTE_RECORD_COLLECTION"), assessed.data["reason"])
            assertEquals(JsonPrimitive(false), assessed.data["complete"])
            assertFalse(assessed.data.containsKey("files_continuation"))
            assertEquals(
                JsonPrimitive("NOT_MEASURED"),
                assessed.data.getValue("pagination_progress").jsonObject["acceptance_status"]
            )
        }
    }

    @Test fun skippedPrFilesAndActuallyEmptyReviewsStayDistinctFromMissingRequestedFiles() {
        val boundary = GitHubPaginationBoundary()
        val pr = json("""{"action":"pr_context","pull_number":1534,"page":1}""")
        val omitted = json("""{"files_total":82,"files_included":false,"files":[],"has_more":false}""")
        val omittedResult = boundary.assessPage("github_pr", pr, omitted)
        assertEquals(null, omittedResult.errorCode)
        assertEquals(omitted, omittedResult.data)
        assertFalse(omittedResult.data.containsKey("pagination_progress"))

        val genuinelyEmpty = json(
            """{"files_total":0,"files_included":true,"files_returned":0,"files":[],"page":1,"has_more":false}"""
        )
        assertEquals(null, boundary.assessPage("github_pr", pr, genuinelyEmpty).errorCode)

        val reviewArgs = json("""{"action":"pull_request_reviews","pull_number":1534,"page":1}""")
        val emptyReviews = boundary.assessPage(
            "github_pr",
            reviewArgs,
            json("""{"reviews":[],"returned":0,"has_more":false}""")
        )
        assertEquals(null, emptyReviews.errorCode)
        val progress = emptyReviews.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(0), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), progress["acceptance_status"])

        val missingReviews = GitHubPaginationBoundary().assessPage(
            "github_pr",
            reviewArgs,
            json("""{"has_more":false,"total_count":1}""")
        )
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", missingReviews.errorCode)
        assertEquals(JsonPrimitive("MISSING_REMOTE_RECORD_COLLECTION"), missingReviews.data["reason"])
    }

    @Test fun populatedLastPrFilePageIsAcceptedWithoutClaimingAllPagesOrTestsPassed() {
        val args = json("""{"action":"pr_context","pull_number":1534,"page":3,"verify_live":true}""")
        val rows = (61..82).joinToString(",") { """{"filename":"file$it"}""" }
        val result = json(
            """{"files_total":82,"files_included":true,"files_returned":22,"files":[$rows],"page":3,"has_more":false}"""
        )
        val assessment = GitHubPaginationBoundary().assessPage("github_pr", args, result)
        assertEquals(null, assessment.errorCode)
        assertEquals(result, assessment.data)
        assertFalse(assessment.data.containsKey("pagination_progress"))
    }

    @Test fun recoveryEvidenceIsCountedByRetainedHandleAcrossPages() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"recovery_context","page":1}""")
        val second = json("""{"action":"recovery_context","page":2}""")
        val pageOne = json("""{"evidence":[{"evidence_id":"a","available":false}],"upstream_record_ids":["a"],"total_count":2,"has_more":true}""")
        val pageTwo = json("""{"evidence":[{"evidence_id":"b","available":true}],"upstream_record_ids":["b"],"total_count":2,"has_more":false}""")
        assertEquals(null, boundary.assessPage("github_context", first, pageOne).errorCode)
        val result = boundary.assessPage("github_context", second, pageTwo)
        assertEquals(null, result.errorCode)
        assertEquals(JsonPrimitive(2), result.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        assertEquals(JsonPrimitive(true), result.data.getValue("pagination_progress").jsonObject["complete"])
    }

    @Test fun singletonPrContextDoesNotInventASyntheticSecondRemotePage() = runBlocking {
        var dispatched = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "test", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Json(json("""{"has_more":true,"pull_request":{"number":1349}}""")), false)
            }
        }
        val tool = GitHubOperationTool("github_pr", backend, "single-pr-context")
        val result = tool.execute("single", json("""{"action":"context","pull_number":1349}"""))
        assertFalse(result.toString(), result.isError)
        assertEquals(1, dispatched)
        val data = (result.content as ToolResultContent.Json).value.jsonObject
        assertFalse(data.containsKey("pagination_progress"))
        assertFalse(data.containsKey("remote_continuations"))
        val original = data.getValue("data").jsonObject
        assertEquals(JsonPrimitive(true), original["has_more"])
        assertFalse(original.containsKey("continuation"))
    }

    @Test fun nestedCommitChecksRequireRealRowsAndCompleteRemoteCoverage() {
        val boundary = GitHubPaginationBoundary()
        val first = boundary.assessPage(
            "github_context",
            json("""{"action":"checks","page":1,"per_page":2}"""),
            json("""{"has_more":true,"check_details":{"total_count":3,"check_runs":[{"id":1},{"id":2}]}}""")
        )
        assertEquals(null, first.errorCode)
        val last = boundary.assessPage(
            "github_context",
            json("""{"action":"checks","page":2,"per_page":2}"""),
            json("""{"has_more":false,"check_details":{"total_count":3,"check_runs":[{"id":3}]}}""")
        )
        assertEquals(null, last.errorCode)
        val progress = last.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(3), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["complete"])

        val missing = GitHubPaginationBoundary().assessPage(
            "github_context",
            json("""{"action":"checks","page":1}"""),
            json("""{"has_more":false,"check_details":{"total_count":1}}""")
        )
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", missing.errorCode)
    }

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

    @Test fun missingRemoteCollectionWithReportedRecordsIsNeverAcceptedAsComplete() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"list_workflow_runs","page":1,"per_page":30}""")
        val missing = boundary.assessPage("github_ci", args, json("""{"has_more":false,"total_count":1}"""))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", missing.errorCode)
        assertEquals(JsonPrimitive("MISSING_REMOTE_RECORD_COLLECTION"), missing.data["reason"])
        assertEquals(JsonPrimitive(1), missing.data.getValue("pagination_progress").jsonObject["reported_total"])
        assertEquals(JsonPrimitive(0), missing.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        assertEquals(JsonPrimitive(false), missing.data.getValue("pagination_progress").jsonObject["complete"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), missing.data.getValue("pagination_progress").jsonObject["acceptance_status"])
    }

    @Test fun terminalPageWithoutEarlierRemotePagesFailsExplicitly() {
        val boundary = GitHubPaginationBoundary()
        val result = boundary.assessPage(
            "github_ci",
            json("""{"action":"list_workflow_runs","page":2,"per_page":1}"""),
            json("""{"has_more":false,"total_count":1,"workflow_runs":[{"id":7}],"continuation":{"action":"list_workflow_runs","page":3}}""")
        )
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", result.errorCode)
        assertEquals(JsonPrimitive(false), result.data["has_more"])
        assertFalse(result.data.containsKey("continuation"))
        val progress = result.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(false), progress["contiguous_from_first_page"])
        assertEquals(JsonPrimitive(false), progress["complete"])
    }

    @Test fun issuedIssueCursorAndExplicitRestDefaultsPreserveOneRemoteTraversal() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"list_issues","page":1,"state":"open","per_page":20}""")
        val second = json("""{"action":"list_issues","page":2,"state":"open","per_page":20,"sort":"created","direction":"asc","after":"issued-opaque-cursor"}""")
        assertEquals(null, boundary.assessPage("github_issue", first, json("""{"has_more":true,"total_count":2,"items":[{"id":1}]}""")).errorCode)
        val final = boundary.assessPage("github_issue", second, json("""{"has_more":false,"total_count":2,"items":[{"id":2}]}"""))
        assertEquals(null, final.errorCode)
        assertEquals(JsonPrimitive(true), final.data.getValue("pagination_progress").jsonObject["complete"])
    }

    @Test fun jobLogWindowsCannotBeMistakenForIncompleteRemoteCollections() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"get_job_logs","job_id":8,"start_line":1,"end_line":2}""")
        val lines = json("""{"has_more":true,"excerpts":[{"line":1,"text":"error"}]}""")
        assertFalse(boundary.expectsNumberedRemotePage("github_ci", args))
        assertEquals(null, boundary.assessPage("github_ci", args, lines).errorCode)
        assertEquals(lines, boundary.assessPage("github_ci", args, lines).data)
    }

    @Test fun emptyTerminalWithoutExplicitZeroTotalFailsExplicitly() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"list_workflow_runs","page":1}""")
        val unknown = boundary.assessPage("github_ci", args, json("""{"has_more":false}"""))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", unknown.errorCode)
        assertEquals(JsonPrimitive("MISSING_REMOTE_RECORD_COLLECTION"), unknown.data["reason"])
        val explicitZero = boundary.assessPage("github_ci", args, json("""{"has_more":false,"total_count":0}"""))
        assertEquals(null, explicitZero.errorCode)
    }

    @Test fun missingRemoteCollectionWhileMorePagesAdvertisedIsIncompleteButEmptyTerminalIsAllowed() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"list_workflow_runs","page":1}""")
        val missing = boundary.assessPage("github_ci", args, json("""{"has_more":true}"""))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", missing.errorCode)
        assertEquals(JsonPrimitive(true), missing.data["upstream_has_more"])
        val empty = boundary.assessPage("github_ci", args, json("""{"has_more":false,"total_count":0}"""))
        assertEquals(null, empty.errorCode)
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

    @Test fun verifiedSecondPageRetainsTheFirstPageOfTheSameRemoteTraversal() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"runs","owner":"owner","repo":"repo","page":1,"per_page":2,"verify_live":true}""")
        val second = json("""{"action":"runs","owner":"owner","repo":"repo","page":2,"per_page":2,"verify_live":true}""")
        assertEquals(null, boundary.completedPage("github_ci", first))
        boundary.assessPage("github_ci", first, json("""{"has_more":true,"total_count":2,"workflow_runs":[{"id":1}]}"""))
        assertEquals(null, boundary.completedPage("github_ci", second))
        val result = boundary.assessPage("github_ci", second, json("""{"has_more":false,"total_count":2,"workflow_runs":[{"id":2}]}"""))
        assertEquals(null, result.errorCode)
        val progress = result.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(2), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["contiguous_from_first_page"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), progress["remote_pages_observed"])
    }

    @Test fun sixLiveWorkflowPagesCompleteOne170RunTraversal() {
        val boundary = GitHubPaginationBoundary()
        val action = "github_ci"
        for (page in 1..6) {
            val args = json("""{"action":"runs","owner":"owner","repo":"repo","page":$page,"per_page":30,"verify_live":true}""")
            assertEquals(null, boundary.completedPage(action, args))
            val first = (page - 1) * 30 + 1
            val last = minOf(page * 30, 170)
            val rows = (first..last).joinToString(",") { """{"id":$it}""" }
            val reply = json("""{"has_more":${page < 6},"total_count":170,"workflow_runs":[$rows]}""")
            val result = boundary.assessPage(action, args, reply)
            assertEquals("page $page: ${result.errorCode}", null, result.errorCode)
            val progress = result.data.getValue("pagination_progress").jsonObject
            assertEquals(JsonPrimitive(page == 6), progress["complete"])
            assertEquals(JsonPrimitive(last), progress["unique_records_observed"])
            assertEquals(JsonArray((1..page).map(::JsonPrimitive)), progress["remote_pages_observed"])
        }
    }

    @Test fun pullRequestCommentsAreCountedAsRealPaginatedRows() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"get_pull_request_comments","owner":"o","repo":"r","pull_number":1534,"page":1}""")
        val second = json("""{"action":"get_pull_request_comments","owner":"o","repo":"r","pull_number":1534,"page":2}""")
        val rows = (1..30).joinToString(",") { """{"id":$it}""" }
        val firstReply = json("""{"has_more":true,"returned":30,"comments":[$rows]}""")
        val firstAssessment = boundary.assessPage("github_pr", first, firstReply)
        assertEquals(null, firstAssessment.errorCode)
        assertEquals(JsonPrimitive(false), firstAssessment.data.getValue("pagination_progress").jsonObject["complete"])
        val finalReply = json("""{"has_more":false,"returned":2,"comments":[{"id":31},{"id":32}]}""")
        val final = boundary.assessPage("github_pr", second, finalReply)
        assertEquals(null, final.errorCode)
        val progress = final.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(32), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(true), progress["contiguous_from_first_page"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), progress["remote_pages_observed"])
    }

    @Test fun emptyFirstPullRequestCommentsPageCompletesWithoutInventingRows() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"get_pull_request_comments","owner":"o","repo":"r","pull_number":1534}""")
        val output = boundary.assessPage("github_pr", args, json("""{"has_more":false,"returned":0,"comments":[]}"""))
        assertEquals(null, output.errorCode)
        assertEquals(JsonPrimitive(true), output.data.getValue("pagination_progress").jsonObject["complete"])
        assertEquals(JsonPrimitive(0), output.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
    }

    @Test fun laterEmptyTerminalCannotEraseEarlierNonemptyPage() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"runs","page":1,"per_page":30,"verify_live":true}""")
        val next = json("""{"action":"runs","page":2,"per_page":30,"verify_live":true}""")
        assertEquals(null, boundary.completedPage("github_ci", first))
        boundary.assessPage("github_ci", first, json("""{"has_more":true,"total_count":1,"workflow_runs":[{"id":7}]}"""))
        val result = boundary.assessPage("github_ci", next, json("""{"has_more":false,"total_count":0}"""))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", result.errorCode)
        assertEquals(JsonPrimitive(false), result.data.getValue("complete"))
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

    @Test fun inventedSavedViewCursorOffersOnlyFreshAuthorizedReadWithoutSourceLeak() {
        val views = GitHubResultViews()
        val original = AgentToolResult(
            "original",
            ToolResultContent.Json(json("""{"items":[{"id":1}],"has_more":false}""")),
            false
        )
        val presented = views.present(
            "scope",
            "github_pr",
            "github_pr__original",
            original,
            json("""{"action":"list","owner":"owner","repo":"repo"}""")
        )
        val page = (presented.content as ToolResultContent.Json).value.jsonObject
        val issued = page.getValue("recovery_call").jsonObject
            .getValue("arguments").jsonObject
            .getValue("view_cursor").let { (it as JsonPrimitive).content }
        val invented = issued.substringBefore(':') + ":9999:2048"

        val result = views.read("scope", "github_pr", "github_pr__original", invented, "invented")
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        assertEquals("SAVED_VIEW_UNAVAILABLE", result.errorCode)
        val body = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive("UNISSUED_CURSOR"), body["reason"])
        assertEquals(JsonPrimitive("START_FRESH_SCOPED_READ"), body["recovery_action"])
        assertEquals(JsonPrimitive(false), body["mutation_replay_allowed"])
        assertFalse(body.containsKey("correction_call"))
        assertFalse(body.containsKey("source_recovery_call"))
        assertFalse(body.toString().contains(issued))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("saved_view_cursor_issued"))
    }

    @Test fun changedFileIdentityUsesFilenameEvenWhenBlobShasMatch() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"changed_since","base":"main","commit_sha":"head","page":1}""")
        val reply = json(
            """{"files_total":2,"files":[
                {"filename":"src/First.kt","sha":"same-blob"},
                {"filename":"src/Second.kt","sha":"same-blob"}
            ],"tree_complete":true,"has_more":false}"""
        )
        val result = boundary.assessPage("github_context", args, reply)
        assertEquals(null, result.errorCode)
        val progress = result.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(2), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(2), progress["reported_total"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), progress["acceptance_status"])
    }

    @Test fun changedFileCoverageRemainsContiguousAcrossOneHundredFilePages() {
        val boundary = GitHubPaginationBoundary()
        val first = json("""{"action":"changed_since","base":"main","commit_sha":"head","page":1}""")
        val second = json("""{"action":"changed_since","base":"main","commit_sha":"head","page":2}""")
        fun page(start: Int, end: Int, hasMore: Boolean) = buildJsonObject {
            put("files_total", 105)
            put("tree_complete", true)
            put("has_more", hasMore)
            put(
                "files",
                JsonArray(
                    (start..end).map { i ->
                        buildJsonObject {
                            put("filename", "src/File$i.kt")
                            put("sha", "shared-blob")
                        }
                    }
                )
            )
        }
        val initial = boundary.assessPage("github_context", first, page(1, 100, true))
        assertEquals(null, initial.errorCode)
        assertEquals(JsonPrimitive(false), initial.data.getValue("pagination_progress").jsonObject["complete"])
        val last = boundary.assessPage("github_context", second, page(101, 105, false))
        assertEquals(null, last.errorCode)
        val progress = last.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(105), progress["unique_records_observed"])
        assertEquals(JsonPrimitive(105), progress["reported_total"])
        assertEquals(JsonPrimitive(true), progress["complete"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), progress["remote_pages_observed"])
    }

    @Test fun mutableBaseRefAndPinnedContinuationShareOneComparisonTraversal() {
        val boundary = GitHubPaginationBoundary()
        val head = "a".repeat(40)
        val base = "b".repeat(40)
        fun request(value: String, page: Int, includeRef: Boolean) = buildJsonObject {
            put("action", "changed_since")
            put("owner", "Owner")
            put("repo", "Repo")
            put("base", value)
            put("base_sha", value)
            put("commit_sha", head)
            put("page", page)
            put("include_patches", false)
            if (includeRef) put("ref", "working-branch")
        }
        fun response(from: Int, to: Int, hasMore: Boolean) = buildJsonObject {
            put("base_sha", base)
            put("head_sha", head)
            put("files_total", 103)
            put("has_more", hasMore)
            put("tree_complete", true)
            put(
                "files",
                JsonArray(
                    (from..to).map { i ->
                        buildJsonObject {
                            put("filename", "src/File$i.kt")
                            put("sha", "shared-content")
                        }
                    }
                )
            )
        }
        val first = request("main", 1, true)
        val continuation = request(base, 2, false)
        val assessedFirst = boundary.assessPage("github_context", first, response(1, 100, true))
        assertEquals(null, assessedFirst.errorCode)
        val last = boundary.assessPage("github_context", continuation, response(101, 103, false))
        assertEquals(null, last.errorCode)
        assertEquals(JsonPrimitive(true), last.data.getValue("pagination_progress").jsonObject["complete"])
        assertEquals(JsonPrimitive(103), last.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        assertEquals(2, boundary.completedPage("github_context", request(base, 3, false)))
        val fresh = boundary.assessPage("github_context", first, response(1, 100, true))
        assertEquals(JsonPrimitive(100), fresh.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        assertEquals(JsonPrimitive(false), fresh.data.getValue("pagination_progress").jsonObject["complete"])
    }

    @Test fun truncatedGitTreeNeverQualifiesACompleteComparison() {
        val boundary = GitHubPaginationBoundary()
        val args = json("""{"action":"changed_since","base":"main","commit_sha":"head","page":1}""")
        val truncated = json(
            """{"files_total":1,"tree_complete":false,
                "files":[{"filename":"src/Visible.kt","sha":"a"}],"has_more":false}"""
        )
        val result = boundary.assessPage("github_context", args, truncated)
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", result.errorCode)
        val progress = result.data.getValue("pagination_progress").jsonObject
        assertEquals(JsonPrimitive(false), progress["complete"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), progress["acceptance_status"])
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
