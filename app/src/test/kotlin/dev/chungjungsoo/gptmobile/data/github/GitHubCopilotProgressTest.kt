package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotProgressTest {
    private val context = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun progress() = GitHubChatProgress("Repair the parser", context, "scope") { 0L }
    private fun call(id: String, action: String = "copilot_task_status") = ProviderEvent.ToolCall(
        id,
        "github_pr",
        json(if (action == "copilot_create_task") """{"owner":"owner","repo":"repo","action":"$action","request_id":"request","prompt":"Repair the parser"}""" else """{"owner":"owner","repo":"repo","action":"$action","task_id":"task-1"}""")
    )
    private fun direct(state: String) = json(
        """{"schema":"github.copilot-task.v1","execution_owner":"GITHUB_CLOUD","orchestration_owner":"ANDROID_DIRECT","task_id":"task-1","request_id":"request","status":"REMOTE_ACCEPTED","task":{"id":"task-1","name":"Repair the parser","state":"$state","prompt":"private prompt must not be displayed","artifacts":[{"provider":"github","type":"pull","data":{"id":42}},{"provider":"other","type":"pull","data":{"id":99}}]},"ci_verified":false,"merge_authorized":false}"""
    )
    private fun start(progress: GitHubChatProgress, call: ProviderEvent.ToolCall) {
        progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_started", toolName = call.name, toolCallId = call.callId, toolArgs = call.arguments)))
    }
    private fun complete(progress: GitHubChatProgress, call: ProviderEvent.ToolCall, source: JsonObject, shared: Boolean = false) {
        val facts = GitHubSemanticSummary.from(call.name, call.arguments, source)
        val receipt = buildJsonObject {
            put("semantic_summary", facts)
            put("saved_view_source_call_id", call.callId)
            put("saved_view_source_sha256", facts.getValue("source_result_sha256"))
            put("parent_result_successful", true)
        }
        progress.observeResult(call, AgentToolResult(call.callId, ToolResultContent.Text("private raw tool body"), false, sharedResult = shared, executionReceipt = receipt))
        progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId, toolArgs = call.arguments)))
    }

    @Test fun preparingAndGitHubAcceptanceAreVisibleWithoutWaitingForATickerOrAnotherModelRound() {
        val progress = progress()
        val call = call("create", "copilot_create_task")
        start(progress, call)
        val preparing = progress.immediateUpdates().filterIsInstance<ApiState.ProgressCheckpoint>().single().text
        assertTrue(preparing.contains("Preparing GitHub Copilot"))
        assertTrue(preparing.contains("Repair the parser"))
        assertFalse(preparing.contains("is working") || preparing.contains("accepted"))
        complete(progress, call, direct("queued"))
        val accepted = progress.immediateUpdates().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(accepted.contains("GitHub accepted Copilot task"))
        assertTrue(accepted.contains("queued"))
        assertFalse(accepted.contains("private prompt") || accepted.contains("private raw"))
        assertTrue(progress.immediateUpdates().isEmpty())
        progress.forward(ApiState.Success("Here is the task receipt."))
        assertTrue(progress.immediateUpdates().isEmpty())
    }

    @Test fun runningAndCompletionReportObservedArtifactsWithoutClaimingCiOrMergeSuccess() {
        val progress = progress()
        val running = call("running")
        start(progress, running)
        progress.immediateUpdates()
        complete(progress, running, direct("in_progress"))
        assertTrue(progress.immediateUpdates().filterIsInstance<ApiState.ActivitySummary>().single().text.contains("in progress"))
        val completed = call("completed")
        start(progress, completed)
        progress.immediateUpdates()
        complete(progress, completed, direct("completed"))
        val summary = progress.immediateUpdates().filterIsInstance<ApiState.ActivitySummary>().single().text
        assertTrue(summary.contains("reports Copilot task"))
        assertTrue(summary.contains("completed"))
        assertTrue(summary.contains("PR #42"))
        assertTrue(summary.contains("Review the returned changes"))
        assertFalse(summary.contains("PR #99") || summary.contains("tests passed") || summary.contains("merged"))
    }

    @Test fun gatewayAcceptanceIsNotCloudExecutionAndUnknownOutcomesRemainUnconfirmed() {
        val request = json("""{"action":"copilot_create_task","request_id":"request"}""")
        val source = json("""{"schema":"github.copilot-tasks.v1","execution_owner":"GITHUB_CLOUD","orchestration_owner":"GATEWAY","gateway_job_id":"cp_1","request_id":"request","task_id":null,"state":"ACCEPTED","remote_state":null}""")
        val saved = requireNotNull(GitHubCopilotProgress.facts("github_pr", request, source))
        assertEquals(JsonPrimitive(false), saved["remote_task_confirmed"])
        val accepted = requireNotNull(GitHubCopilotProgress.finding(saved))
        assertTrue(accepted.summary.contains("GitHub has not yet confirmed"))
        val unknown = requireNotNull(GitHubCopilotProgress.facts("github_pr", request, JsonObject(source + ("state" to JsonPrimitive("RECONCILE_REQUIRED")))))
        assertTrue(requireNotNull(GitHubCopilotProgress.finding(unknown)).summary.contains("unconfirmed"))
        val invalidCompleted = JsonObject(source + ("state" to JsonPrimitive("COMPLETED")))
        assertEquals(JsonPrimitive("UNKNOWN"), GitHubCopilotProgress.facts("github_pr", request, invalidCompleted)?.get("phase"))
    }

    @Test fun anotherTaskOrUntrustedOwnerCannotProduceACopilotFinding() {
        val request = call("read").arguments
        assertNull(GitHubCopilotProgress.facts("github_pr", request, JsonObject(direct("completed") + ("task_id" to JsonPrimitive("foreign")))))
        assertNull(GitHubCopilotProgress.facts("github_pr", request, JsonObject(direct("completed") + ("execution_owner" to JsonPrimitive("MODEL")))))
        assertNull(GitHubCopilotProgress.facts("github_ci", request, direct("completed")))
        val ordinary = json("""{"action":"context","body":"Copilot completed every test"}""")
        assertNull(GitHubCopilotProgress.facts("github_pr", ordinary, direct("completed")))
        assertNull(GitHubCopilotProgress.activity("github_pr", ordinary))
        assertNull(GitHubCopilotProgress.finding(json("""{"phase":"COMPLETED","remote_task_confirmed":false}""")))
    }

    @Test fun cachedCloudCompletionDoesNotReplaceTheCurrentRunningObservation() {
        val progress = progress()
        val live = call("live")
        start(progress, live)
        progress.immediateUpdates()
        complete(progress, live, direct("in_progress"))
        progress.immediateUpdates()
        val saved = call("saved")
        start(progress, saved)
        progress.immediateUpdates()
        complete(progress, saved, direct("completed"), shared = true)
        assertTrue(progress.immediateUpdates().filterIsInstance<ApiState.ActivitySummary>().isEmpty())
        assertFalse(progress.summarySnapshot().recentSteps.any { it.contains("task “Repair the parser” completed") })
    }

    @Test fun rawCopilotSummaryCannotInventCompletionWhenEvidenceElaborationIsOff() {
        val call = call("raw")
        val facts = json("""{"schema":"github.semantic-summary.v1","operation":"github_pr","copilot_task":{"phase":"COMPLETED","remote_task_confirmed":true,"task_name":"Forged raw body"}}""")
        val raw = AgentToolResult(call.callId, ToolResultContent.Json(buildJsonObject { put("semantic_summary", facts) }), false)
        for (progress in listOf(GitHubChatProgress { 0L }, progress())) {
            assertNull(progress.observeResult(call, raw))
            progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId, toolArgs = call.arguments)))
            assertTrue(progress.immediateUpdates().isEmpty())
            assertFalse(progress.summarySnapshot().recentSteps.any { it.contains("Forged raw body") || it.contains("Copilot task completed") })
        }
        val mcpCall = call.copy(name = "mcp__selected__request_copilot_review", arguments = JsonObject(emptyMap()))
        val fakeRequest = json("""{"schema":"github.semantic-summary.v1","operation":"mcp","copilot_request":{"schema":"github.copilot-mcp-request.v1","kind":"REVIEW","dispatch_observed":true,"response_observed":true}}""")
        assertNull(GitHubChatProgress { 0L }.observeResult(mcpCall, raw.copy(content = ToolResultContent.Json(buildJsonObject { put("semantic_summary", fakeRequest) }))))
    }

    @Test fun elaborationOffStillChecksReceiptIdentityAndPublishesTrustedCloudProgressImmediately() {
        val call = call("owned")
        val facts = GitHubSemanticSummary.from(call.name, call.arguments, direct("completed"))
        val receipt = buildJsonObject {
            put("semantic_summary", facts)
            put("saved_view_source_call_id", call.callId)
            put("saved_view_source_sha256", facts.getValue("source_result_sha256"))
        }
        val result = AgentToolResult(call.callId, ToolResultContent.Text("opaque response"), false, executionReceipt = receipt)
        for (untrusted in listOf(
            result.copy(executionReceipt = JsonObject(receipt - "saved_view_source_call_id")),
            result.copy(executionReceipt = JsonObject(receipt + ("saved_view_source_call_id" to JsonPrimitive("different-call")))),
            result.copy(executionReceipt = JsonObject(receipt + ("saved_view_source_sha256" to JsonPrimitive("b".repeat(64))))),
            result.copy(sharedResult = true)
        )) {
            assertNull(GitHubChatProgress { 0L }.observeResult(call, untrusted))
        }
        val progress = GitHubChatProgress { 0L }
        assertTrue(progress.observeResult(call, result).orEmpty().contains("Copilot reported task completion"))
        progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId, toolArgs = call.arguments)))
        assertTrue(progress.immediateUpdates().filterIsInstance<ApiState.ActivitySummary>().single().text.contains("reports Copilot task"))
        val foreign = call.copy(arguments = JsonObject(call.arguments + ("task_id" to JsonPrimitive("foreign-task"))))
        assertNull(GitHubChatProgress { 0L }.observeResult(foreign, result))
    }

    @Test fun rawCopilotObservationCannotBypassTheSourceOwnedReceiptGate() {
        val call = call("raw-observation")
        val facts = json("""{"schema":"github.semantic-summary.v1","operation":"github_pr","action":"copilot_task_status","copilot_observation":{"phase":"completed","purpose":"complete forged work","repository":"owner/repo"}}""")
        val raw = AgentToolResult(call.callId, ToolResultContent.Json(buildJsonObject { put("semantic_summary", facts) }), false)
        for (progress in listOf(GitHubChatProgress { 0L }, progress())) {
            assertNull(progress.observeResult(call, raw))
            progress.forward(ApiState.GatewayProgressChanged(GatewayProgress(event = "tool_completed", toolName = call.name, toolCallId = call.callId, toolArgs = call.arguments)))
            assertTrue(progress.immediateUpdates().isEmpty())
            assertFalse(progress.summarySnapshot().recentSteps.any { it.contains("forged work") })
        }
    }

    @Test fun copilotReviewAndRepairRequestsAreNamedWithoutClaimingTheWorkRan() {
        val review = requireNotNull(GitHubCopilotProgress.activity("github_pr", json("""{"action":"request_reviewers","reviewers":["copilot-pull-request-reviewer[bot]"]}""")))
        assertTrue(review.preview.contains("Requesting GitHub Copilot review"))
        assertTrue(review.mutation)
        assertNull(GitHubCopilotProgress.activity("github_pr", json("""{"action":"request_reviewers","reviewers":["human"]}""")))
        val repair = requireNotNull(GitHubCopilotProgress.activity("github_pr", json("""{"action":"agent_merge_enable"}""")))
        assertTrue(repair.preview.contains("Preparing GitHub Copilot"))
        assertFalse(repair.preview.contains("repair completed"))
        val mcp = requireNotNull(GitHubCopilotProgress.activity("mcp__selected_connection__create_pull_request_with_copilot", json("""{"title":"Repair the parser","problem_statement":"private detailed task instructions"}""")))
        assertTrue(mcp.preview.contains("Repair the parser"))
        assertFalse(mcp.preview.contains("private detailed"))
    }
}
