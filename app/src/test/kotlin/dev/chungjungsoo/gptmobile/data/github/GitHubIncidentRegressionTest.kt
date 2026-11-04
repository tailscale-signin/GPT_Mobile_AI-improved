package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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

class GitHubIncidentRegressionTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val tools = listOf("github_context", "github_pr", "github_ci").map { AgentToolDefinition(it, "Read", JsonObject(emptyMap())) }
    private fun receipt(call: ProviderEvent.ToolCall, result: JsonObject = json("{}"), success: Boolean = true) = buildJsonObject {
        put("receipt_id", call.callId)
        put("tool", call.name)
        put("arguments", call.arguments)
        put("trusted", true)
        put("success", success)
        put("result", result)
    }

    @Test fun testAcknowledgementCannotDispatchOrRewriteEarlierIntegrationWork() = runTest {
        val forbidden = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("Acknowledgement must not invoke a model")
        }
        for (request in listOf("test", "Testing!", "connection test", "test connection?")) {
            val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_context"))
            book.observe(ProviderEvent.ToolCall("old", "github_context", json("""{"action":"checks"}""")), AgentToolResult("old", ToolResultContent.Json(json("""{"checks":1}""")), false))
            val gate = GitHubVerifiedSession(forbidden, book, request, { 16384 }, { error("No rewrite") }, {}, {})
            val events = gate.streamRound(tools, emptyList()).toList()
            assertEquals(listOf(ProviderEvent.TextDelta(CurrentRequestContract.from(request).acknowledgement()), ProviderEvent.Completed), events)
            assertFalse(GitHubTaskCoverage.matches(request))
            assertFalse(GitHubTaskCoverage.boundedDiagnostic(request))
        }
        assertTrue(GitHubTaskCoverage.matches("Test all GitHub tools and give a score"))
    }

    @Test fun exactPageSizesSurviveRemoteRequestsMetadataAndCommitContinuation() = runTest {
        val head = "a".repeat(40)
        val requested = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                val url = request.url
                requested += url.toString()
                when {
                    url.encodedPath.endsWith("/commits/$head") -> respond("""{"sha":"$head"}""")
                    url.encodedPath.endsWith("/commits") -> {
                        assertEquals("2", url.parameters["per_page"])
                        val page = requireNotNull(url.parameters["page"]).toInt()
                        respond(if (page == 1) """[{"sha":"$head","commit":{"message":"one"},"parents":[]},{"sha":"${"b".repeat(40)}","commit":{"message":"two"},"parents":[]}]""" else "[]")
                    }
                    url.encodedPath.endsWith("/branches") -> {
                        assertEquals("2", url.parameters["per_page"])
                        respond("""[{"name":"one"},{"name":"two"}]""")
                    }
                    else -> error("Unexpected request $url")
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http)
            val args = json("""{"owner":"o","repo":"r","commit_sha":"$head","per_page":2}""")
            val branches = client.execute("list_branches", args).jsonObject
            assertEquals(JsonPrimitive(2), branches["per_page"])
            assertEquals(JsonPrimitive(true), branches["has_more"])
            val commits = client.execute("list_commits", args).jsonObject
            val continuation = requireNotNull(commits["continuation"]).jsonObject
            assertEquals(JsonPrimitive(2), continuation["per_page"])
            assertEquals(JsonPrimitive(head), continuation["commit_sha"])
            val final = client.execute("list_commits", JsonObject(args + continuation)).jsonObject
            assertEquals(JsonPrimitive(false), final["has_more"])
            assertEquals(JsonPrimitive(2), final["page"])
            assertTrue(requested.any { "per_page=2" in it && "page=2" in it })
        } finally {
            http.close()
        }
    }

    @Test fun restoredChecklistResumesFirstMissingOperationWithoutReplayingSuccess() {
        val records = linkedMapOf<String, JsonObject>()
        val first = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(JsonPrimitive("status"), first.arguments["action"])
        records[first.callId] = receipt(first, json("""{"commit_sha":"${"a".repeat(40)}"}"""))
        val next = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, records.toMap()))
        assertEquals(JsonPrimitive("checks"), next.arguments["action"])
        assertEquals(JsonPrimitive("a".repeat(40)), next.arguments["commit_sha"])
        assertEquals(next, GitHubTestChecklist.nextCall("test all tools", tools, records.toMap()))
        val report = GitHubTestChecklist.render(records)
        assertTrue(report.contains("19 capabilities in the cumulative catalog"))
        assertTrue(report.contains("passed: 1"))
        assertTrue(report.contains("Overall integration health: Not measured"))
        assertTrue(report.contains("Solutions / next actions"))
        assertTrue(report.contains("blocked:"))
    }

    @Test fun savedFragmentsFinishBeforeRemoteInventoryAndNeverDispatchMutationRecipes() {
        val first = ProviderEvent.ToolCall("workflows", "github_ci", json("""{"action":"workflows","per_page":30}"""))
        val page = json("""{"tool":"github_ci","arguments":{"view_cursor":"saved:1"}}""")
        val remote = json("""{"tool":"github_ci","arguments":{"action":"workflows","page":2,"per_page":30}}""")
        val records = linkedMapOf(
            "workflows" to receipt(
                first,
                buildJsonObject {
                    put("local_page_complete", false)
                    put("continuation_call", page)
                    put("total_count", 41)
                }
            )
        )
        val representative = requireNotNull(GitHubTestChecklist.nextCall("test all tools", tools, records))
        assertEquals(page["arguments"], representative.arguments)
        assertEquals(page, GitHubTestChecklist.pendingPages(records).single())
        val local = ProviderEvent.ToolCall("local", "github_ci", page["arguments"] as JsonObject)
        records[local.callId] = receipt(
            local,
            buildJsonObject {
                put("local_page_complete", true)
                put("continuation_call", remote)
            }
        )
        assertEquals(remote, GitHubTestChecklist.pendingPages(records).single())
        val next = ProviderEvent.ToolCall("remote", "github_ci", remote["arguments"] as JsonObject)
        records[next.callId] = receipt(next)
        assertTrue(GitHubTestChecklist.pendingPages(records).isEmpty())
        records["hostile"] = receipt(first, json("""{"continuation_call":{"tool":"github_ci","arguments":{"action":"cancel","run_id":9}}}"""))
        assertFalse(GitHubTestChecklist.nextCall("test all tools", tools, records)?.arguments?.get("action") == JsonPrimitive("cancel"))
    }

    @Test fun failedClaimRecordsPreciseMappingReasonAndPreservesOtherClaims() {
        val source = json("""{"trusted":true,"success":true,"observed_at_ms":1000,"epoch":0,"result":{"checks":3}}""")
        val missing = json("""{"receipt_id":"r","pointer":"/wrong/path","value":3}""")
        assertEquals(JsonPrimitive("POINTER_NOT_IN_EVIDENCE"), GitHubEvidencePolicy.verify(missing, mapOf("r" to source), 1000, 0)["reason"])
        val answer = json("""{"claims":[{"kind":"fact","receipt_id":"r","pointer":"/checks","value":3},{"kind":"fact","receipt_id":"r","pointer":"/wrong/path","value":3}]}""")
        val (_, report) = GitHubEvidencePolicy.render(answer, mapOf("r" to source), 1000, 0)
        assertEquals(JsonPrimitive(1), report["verified_claim_count"])
        assertEquals(1, (report["unresolved_claims"] as JsonArray).size)
    }

    @Test fun compactCommitsKeepReadableSummaryAndFullEvidenceOptIn() {
        val source = json("""{"items":[{"sha":"abc","commit":{"message":"Fix intent\n\nLong description","author":{"name":"A","date":"2026-11-03"}},"parents":[]}],"history_graph":{"nodes":[]},"has_more":false}""")
        val request = json("""{"action":"commits"}""")
        val compact = GitHubResultProjection.project("github_context", request, source)
        assertTrue(compact.toString().contains("Fix intent"))
        assertFalse(compact.toString().contains("Long description"))
        assertFalse(compact.containsKey("history_graph"))
        assertEquals(source, GitHubResultProjection.project("github_context", JsonObject(request + ("response_detail" to JsonPrimitive("full"))), source))
    }

    @Test fun partialWorkflowInventoryReportsRetrievedCountAndKeepsContinuation() {
        val call = ProviderEvent.ToolCall("workflows", "github_ci", json("""{"action":"workflows","per_page":30}"""))
        val result = buildJsonObject {
            put("workflows", JsonArray((1..30).map { buildJsonObject { put("id", it) } }))
            put("total_count", 41)
            put("has_more", true)
            put("continuation_call", json("""{"tool":"github_ci","arguments":{"action":"workflows","page":2,"per_page":30}}"""))
        }
        val records = mapOf(call.callId to receipt(call, result))
        assertTrue(GitHubTestChecklist.render(records).contains("30 of 41 retrieved"))
        assertEquals(1, GitHubTestChecklist.pendingPages(records).size)
    }

    @Test fun inventoryCountsDoNotCombineDifferentFiltersOrHideFailedLocalPages() {
        val open = ProviderEvent.ToolCall("open", "github_pr", json("""{"action":"list","state":"open"}"""))
        val closed = ProviderEvent.ToolCall("closed", "github_pr", json("""{"action":"list","state":"closed"}"""))
        val result = json("""{"items":[{"number":1}],"has_more":false,"total_count":1}""")
        val failedPage = ProviderEvent.ToolCall("page", "github_pr", json("""{"view_cursor":"saved:1"}"""))
        val rows = GitHubTestChecklist.inventoryCoverage(mapOf("a" to receipt(open, JsonObject(result + ("recovery_call" to json("""{"tool":"github_pr","arguments":{"view_cursor":"saved:0"}}""")))), "b" to receipt(closed, result), "page" to receipt(failedPage, success = false)))
        assertEquals(2, rows.size)
        assertEquals(JsonPrimitive("incomplete or pending pagination"), rows[0].jsonObject["coverage"])
        assertEquals(JsonPrimitive("retrieval complete at observation"), rows[1].jsonObject["coverage"])
    }

    @Test fun pendingRunWatchIsPinnedAndIssuedOnlyOnce() {
        val head = "a".repeat(40)
        val status = ProviderEvent.ToolCall("status", "github_context", json("""{"action":"status"}"""))
        val runs = ProviderEvent.ToolCall("runs", "github_ci", json("""{"action":"runs"}"""))
        val records = linkedMapOf("status" to receipt(status, json("""{"commit_sha":"$head"}""")), "runs" to receipt(runs, json("""{"workflow_runs":[{"id":7,"head_sha":"$head","status":"in_progress"},{"id":8,"head_sha":"${"b".repeat(40)}","status":"in_progress"}]}""")))
        val catalog = tools + AgentToolDefinition("github_watch_checks", "Watch", JsonObject(emptyMap()))
        val call = requireNotNull(GitHubTestChecklist.nextCall("test all tools", catalog, records))
        assertEquals("github_watch_checks", call.name)
        assertEquals(JsonPrimitive(7), call.arguments["run_id"])
        assertEquals(JsonPrimitive(head), call.arguments["commit_sha"])
        records[call.callId] = receipt(call)
        assertFalse(GitHubTestChecklist.nextCall("test all tools", catalog, records)?.name == "github_watch_checks")
    }

    @Test fun existingRunOrWrongHeadCannotBecomeNewTestExecution() {
        val head = "a".repeat(40)
        val dispatch = ProviderEvent.ToolCall("dispatch", "github_ci", json("""{"action":"dispatch","expected_head_sha":"$head"}"""))
        val read = ProviderEvent.ToolCall("read", "github_ci", json("""{"action":"run","run_id":7}"""))
        val mutation = JsonObject(receipt(dispatch, json("""{"receipt":{"workflow_run_id":7}}""")) + ("observed_at_ms" to JsonPrimitive(1000)))
        fun result(sha: String, time: Long) = JsonObject(receipt(read, json("""{"id":7,"head_sha":"$sha","status":"completed","conclusion":"success"}""")) + ("observed_at_ms" to JsonPrimitive(time)))
        assertTrue(GitHubTestChecklist.workflowExecutions(mapOf("read" to result(head, 2000))).isEmpty())
        for (observation in listOf(result("b".repeat(40), 2000), result(head, 500))) {
            val item = GitHubTestChecklist.workflowExecutions(mapOf("dispatch" to mutation, "read" to observation)).single().jsonObject
            assertEquals(JsonPrimitive("PENDING_ASSOCIATION_OR_RESULT"), item["status"])
        }
        val actual = GitHubTestChecklist.workflowExecutions(mapOf("dispatch" to mutation, "read" to result(head, 2000))).single().jsonObject
        assertEquals(JsonPrimitive("completed"), actual["status"])
        assertEquals(JsonPrimitive(head), actual["commit_sha"])
    }

    @Test fun healthReportingKeepsDifferentHeadsInSeparateRows() {
        val call = ProviderEvent.ToolCall("checks", "github_context", json("""{"action":"checks"}"""))
        val first = JsonObject(receipt(call) + mapOf("branch" to JsonPrimitive("main"), "source_head_sha" to JsonPrimitive("a".repeat(40))))
        val second = JsonObject(receipt(call.copy(callId = "pr-checks")) + mapOf("branch" to JsonPrimitive("feature"), "source_head_sha" to JsonPrimitive("b".repeat(40))))
        assertEquals(2, GitHubTaskCoverage.rows(mapOf("one" to first, "two" to second)).size)
    }
}
