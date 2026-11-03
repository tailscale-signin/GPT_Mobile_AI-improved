package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubPaginationBoundary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStatusAuditRegressionTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun releaseContractAcceptsObserved64BitRunAndSupportedPageSize() {
        val schema = GitHubOperationTool.definition("github_release").inputSchema
        assertTrue(ToolArgumentValidator.errors(json("""{"action":"status","workflow_run_id":41626680355}"""), schema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(json("""{"action":"list","per_page":30}"""), schema).isEmpty())
        assertFalse(ToolArgumentValidator.errors(json("""{"action":"list","per_page":101}"""), schema).isEmpty())
    }

    @Test fun branchSchemaRequiresSafetyLeaseAndDoesNotTreatCommitShaAsOne() {
        val schema = GitHubOperationTool.definition("github_commit").inputSchema
        val args = json("""{"action":"branch","branch":"isolated/test","commit_sha":"${"a".repeat(40)}"}""")
        assertFalse(ToolArgumentValidator.errors(args, schema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(JsonObject(args + ("expected_head_sha" to JsonPrimitive("a".repeat(40)))), schema).isEmpty())
        assertFalse(ToolArgumentValidator.errors(json("""{"action":"commit","branch":"isolated/test","expected_head_sha":"${"a".repeat(40)}"}"""), schema).isEmpty())
    }

    @Test fun differentUpstreamPrPagesDoNotStallAnEmptyIssueFilter() {
        val boundary = GitHubPaginationBoundary()
        fun page(id: Int) = json("""{"items":[],"upstream_record_ids":[$id],"upstream_returned":30,"has_more":true}""")
        assertFalse(boundary.repeatsRemotePage("github_issue", json("""{"page":1}"""), page(790)))
        assertFalse(boundary.repeatsRemotePage("github_issue", json("""{"page":2}"""), page(791)))
        assertTrue(boundary.repeatsRemotePage("github_issue", json("""{"page":3}"""), page(791)))
        val unknown = GitHubPaginationBoundary()
        assertFalse(unknown.repeatsRemotePage("github_issue", json("""{"page":1}"""), json("""{"items":[],"upstream_returned":30}""")))
        assertFalse(unknown.repeatsRemotePage("github_issue", json("""{"page":2}"""), json("""{"items":[],"upstream_returned":30}""")))
    }

    @Test fun stalledTraversalHasOneTerminalStateInDisplayEvidenceAndReceipt() = runTest {
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "fixture", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Json(json("""{"items":[],"upstream_record_ids":[790],"has_more":true,"continuation":{"page":3}}""")), isError = false)
        }
        val tool = GitHubOperationTool("github_issue", backend, "audit-pagination")
        tool.execute("first", json("""{"action":"list","page":1}"""))
        val result = tool.execute("second", json("""{"action":"list","page":2}"""))
        assertEquals("GITHUB_PAGINATION_STALLED", result.errorCode)
        for (content in listOf(result.content, result.retainedContent)) {
            val value = (content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive(false), value["has_more"])
            assertEquals(JsonPrimitive(false), value["complete"])
            assertFalse(value.containsKey("continuation"))
        }
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("has_more"))
    }

    @Test fun resourceBranchIsIndependentOfSelectedWorkspace() = runTest {
        val book = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_ci"), now = { 1000 })
        book.observe(ProviderEvent.ToolCall("run", "github_ci", json("""{"action":"run","run_id":41626680355}""")), AgentToolResult("run", ToolResultContent.Json(json("""{"id":41626680355,"head_branch":"release/bounded","head_sha":"${"a".repeat(40)}","status":"in_progress"}""")), isError = false))
        val receipt = book.snapshot().first.values.single()
        assertEquals(JsonPrimitive("main"), receipt["workspace_branch"])
        assertEquals(JsonPrimitive("release/bounded"), receipt["resource_branch"])
        assertEquals(JsonPrimitive("release/bounded"), GitHubTaskCoverage.rows(book.snapshot().first).single()["branch"])
    }

    @Test fun removedToolKeepsItsHistoricalReceiptWithAnExplicitProofExclusion() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        val first = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_release"), now = { 1000 }, save = { id, row -> saved[id] = row })
        first.observe(ProviderEvent.ToolCall("release", "github_release", json("""{"action":"list"}""")), AgentToolResult("release", ToolResultContent.Json(json("""{"items":[],"has_more":false}""")), isError = false))
        val restored = GitHubEvidenceBook("binding", "owner/repo", "main", emptySet(), load = { saved[it] }, retainedIds = { saved.keys.toList() })
        restored.restore()
        val receipt = restored.snapshot().first.values.single()
        assertEquals(JsonPrimitive(true), receipt["success"])
        assertEquals(JsonPrimitive(false), receipt["verification_eligible"])
        assertEquals(JsonPrimitive("TOOL_NOT_AUTHORIZED_IN_CURRENT_CATALOG"), receipt["exclusion_reason"])
        assertEquals(1, GitHubTaskCoverage.rows(restored.snapshot().first).size)
    }

    @Test fun statusUsesInformationalAcceptanceAndPrStateIsNotCheckHealth() {
        assertTrue(GitHubRequestCompletion.informational("status"))
        val receipt = json("""{"tool":"github_pr","arguments":{"action":"headers"},"success":true,"trusted":true,"observed_at_ms":1000,"epoch":0,"result":{"state":"open"}}""")
        val (text, _) = GitHubEvidencePolicy.render(json("""{"claims":[{"kind":"fact","receipt_id":"pr","pointer":"/state","value":"open"}]}"""), mapOf("pr" to receipt), 1000, 0, "Show PR state")
        assertTrue(text.contains("Pull request state"))
        assertFalse(text.contains("Observed check state"))
    }

    @Test fun issueReadsNeverBorrowAPullRequestNumber() {
        val tool = GitHubOperationTool.definition("github_issue")
        val pr = json("""{"trusted":true,"success":true,"tool":"github_pr","arguments":{"action":"list"},"result":{"items":[{"number":790}]}}""")
        val emptyIssues = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list"},"result":{"items":[]}}""")
        assertEquals(null, GitHubSelfTestReads.next(listOf(tool), listOf(pr, emptyIssues)))
        val issues = json("""{"trusted":true,"success":true,"tool":"github_issue","arguments":{"action":"list"},"result":{"items":[{"number":42}]}}""")
        val next = requireNotNull(GitHubSelfTestReads.next(listOf(tool), listOf(pr, issues)))
        assertEquals(JsonPrimitive(42), next.arguments["issue_number"])
        assertEquals(JsonPrimitive("headers"), next.arguments["action"])
    }

    @Test fun logPlanningSelectsCompletedJobsAndPreservesTheObservedId() {
        val definition = AgentToolDefinition("github_ci", "logs", json("""{"type":"object","properties":{"action":{"const":"logs"}}}"""))
        fun jobs(status: String) = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"jobs"},"result":{"jobs":[{"id":125885674437,"status":"$status"}]}}""")
        assertEquals(null, GitHubSelfTestReads.next(listOf(definition), listOf(jobs("in_progress"))))
        val next = requireNotNull(GitHubSelfTestReads.next(listOf(definition), listOf(jobs("completed"))))
        assertEquals(JsonPrimitive(125885674437L), next.arguments["job_id"])
    }

    @Test fun cumulativeCoveragePreservesFailedAttemptsAfterRecovery() {
        fun receipt(id: String, success: Boolean) = json("""{"receipt_id":"$id","trusted":true,"success":$success,"dispatched":true,"tool":"github_release","arguments":{"action":"list"},"result":{"items":[]}}""")
        val rows = GitHubTestChecklist.rows(mapOf("failed" to receipt("failed", false), "passed" to receipt("passed", true)), listOf(GitHubOperationTool.definition("github_release")))
        val row = rows.map { it.jsonObject }.single { it["id"] == JsonPrimitive("github_release:list") }
        assertEquals(JsonPrimitive("RECOVERED"), row["status"])
        assertTrue(row["historical_failure_ids"].toString().contains("failed"))
        assertTrue(GitHubTestChecklist.render(mapOf("failed" to receipt("failed", false))).contains("failed: 1"))
    }

    @Test fun disposableFixtureRequiresOwnershipProofBeforeCleanup() {
        val tools = listOf("github_context", "github_commit").map { GitHubOperationTool.definition(it) }
        val status = json("""{"receipt_id":"status","binding":"task","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"branch":"main","commit_sha":"${"a".repeat(40)}","repository":{"default_branch":"main"}}}""")
        val create = requireNotNull(GitHubSelfTestFixtures.next("test all tools", tools, mapOf("status" to status)))
        assertEquals(JsonPrimitive("main"), create.arguments["from_branch"])
        assertTrue(ToolArgumentValidator.errors(create.arguments, tools.first { it.name == create.name }.inputSchema).isEmpty())
        assertEquals(JsonPrimitive("branch"), create.arguments["action"])
        assertEquals(JsonPrimitive("a".repeat(40)), create.arguments["expected_head_sha"])
        val collision = JsonObject(mapOf("trusted" to JsonPrimitive(true), "success" to JsonPrimitive(true), "tool" to JsonPrimitive(create.name), "arguments" to create.arguments, "result" to json("""{"created":false,"already_exists":true}""")))
        assertEquals(null, GitHubSelfTestFixtures.next("test all tools", tools, mapOf("status" to status, "collision" to collision)))
        assertEquals(null, GitHubSelfTestFixtures.next("test all tools read-only", tools, mapOf("status" to status)))
    }

    @Test fun statusRefreshesOnlyExpiredMutableObservation() {
        val tool = GitHubOperationTool.definition("github_context")
        val old = json("""{"tool":"github_context","trusted":true,"success":true,"observed_at_ms":1000,"arguments":{"action":"status"},"result":{"commit_sha":"${"a".repeat(40)}"}}""")
        assertEquals(JsonPrimitive("checks"), GitHubStatusRequest.nextCall(listOf(tool), mapOf("old" to old), 1001L)?.arguments?.get("action"))
        assertEquals(JsonPrimitive("status"), GitHubStatusRequest.nextCall(listOf(tool), mapOf("old" to old), 200000L)?.arguments?.get("action"))
        assertEquals(JsonPrimitive("status"), GitHubStatusRequest.nextCall(listOf(tool), mapOf("old" to old), 1001L, 1L)?.arguments?.get("action"))
        val excluded = JsonObject(old + ("verification_eligible" to JsonPrimitive(false)))
        assertEquals(JsonPrimitive("status"), GitHubStatusRequest.nextCall(listOf(tool), mapOf("old" to excluded), 1001L)?.arguments?.get("action"))
    }

    @Test fun duplicateReceiptsDoNotCountAsContinuationProgress() {
        val first = json("""{"receipt_id":"one","call_id":"one","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result_sha256":"same","result":{"branch":"main"}}""")
        val second = JsonObject(first + mapOf("receipt_id" to JsonPrimitive("two"), "call_id" to JsonPrimitive("two")))
        val tools = listOf(GitHubOperationTool.definition("github_context"))
        assertEquals(GitHubTestChecklist.progressDigest("test all tools", mapOf("one" to first), tools), GitHubTestChecklist.progressDigest("test all tools", mapOf("one" to first, "two" to second), tools))
    }

    @Test fun compactProgressCountsEvaluatedChecksAndSurvivesPresentationFiltering() {
        val checkpoint = json("""{"capabilities":[{"status":"PASSED"},{"status":"RECOVERED"},{"status":"FAILED"},{"status":"BLOCKED"},{"status":"UNTESTED"}]}""")
        val summary = GitHubTestChecklist.progressSummary(checkpoint, null)
        assertTrue(summary.startsWith("Checks: 2/5 complete; 1 failed; 1 blocked."))
        assertEquals(summary, GitHubProgressPresentation.preview(summary))
    }

    @Test fun pullRequestStateCannotVerifyAClaimAboutCheckHealth() {
        val receipt = json("""{"tool":"github_pr","arguments":{"action":"headers"},"trusted":true,"success":true,"observed_at_ms":1000,"epoch":0,"result":{"state":"open"}}""")
        val claim = json("""{"receipt_id":"pr","pointer":"/state","value":"open","resource_type":"check_run","field_type":"check_run.conclusion"}""")
        val result = GitHubEvidencePolicy.verify(claim, mapOf("pr" to receipt), 1001L, 0)
        assertEquals(JsonPrimitive("NOT_MEASURED"), result["status"])
        assertEquals(JsonPrimitive("FIELD_SEMANTICS_MISMATCH"), result["reason"])
        assertEquals(JsonPrimitive("pull_request.state"), result["field_type"])
        val correct = JsonObject(claim + mapOf("resource_type" to JsonPrimitive("pull_request"), "field_type" to JsonPrimitive("pull_request.state")))
        assertEquals(JsonPrimitive("VERIFIED"), GitHubEvidencePolicy.verify(correct, mapOf("pr" to receipt), 1001L, 0)["status"])
    }

    @Test fun retainedActionIsRevalidatedAgainstTheCurrentContractWithoutDeletingHistory() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        val first = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_release"), now = { 1000 }, save = { id, row -> saved[id] = row })
        val call = ProviderEvent.ToolCall("release", "github_release", json("""{"action":"list"}"""))
        val result = AgentToolResult("release", ToolResultContent.Json(json("""{"items":[],"has_more":false}""")), isError = false)
        first.observe(call, result)
        val contract = json("""{"type":"object","properties":{"action":{"enum":["status"]}}}""")
        for (restoreAll in listOf(true, false)) {
            val restored = GitHubEvidenceBook("binding", "owner/repo", "main", setOf("github_release"), load = { saved[it] }, retainedIds = { saved.keys.toList() }, currentToolContracts = mapOf("github_release" to contract))
            if (restoreAll) restored.restore() else restored.observe(call, result)
            val receipt = restored.snapshot().first.values.single()
            assertEquals(JsonPrimitive(true), receipt["success"])
            assertEquals(JsonPrimitive(false), receipt["verification_eligible"])
            assertEquals(JsonPrimitive("TOOL_ARGUMENT_CONTRACT_CHANGED"), receipt["exclusion_reason"])
            assertEquals(JsonPrimitive(true), saved.values.single()["verification_eligible"])
        }
    }

    @Test fun identifierBoundsDoNotRoundThroughFloatingPoint() {
        val schema = json("""{"type":"object","properties":{"id":{"type":"integer","minimum":1,"maximum":9223372036854775807}}}""")
        assertTrue(ToolArgumentValidator.errors(json("""{"id":9223372036854775807}"""), schema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(json("""{"id":9223372036854775808}"""), schema).isNotEmpty())
        assertTrue(ToolArgumentValidator.errors(json("""{"id":9007199254740992.5}"""), schema).isNotEmpty())
    }

    @Test fun automaticReadsRespectActionRestrictedCatalogs() {
        val tool = AgentToolDefinition("github_ci", "logs", json("""{"type":"object","properties":{"action":{"const":"logs"}}}"""))
        assertEquals(null, GitHubTestChecklist.nextCall("test all tools", listOf(tool), emptyMap()))
        assertEquals(JsonPrimitive("github_ci:logs"), GitHubTestChecklist.rows(emptyMap(), listOf(tool)).single().jsonObject["id"])
    }
}
