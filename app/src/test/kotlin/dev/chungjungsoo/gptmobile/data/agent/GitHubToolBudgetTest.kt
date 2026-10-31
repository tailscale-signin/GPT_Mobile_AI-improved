package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

class GitHubToolBudgetTest {
    @Test fun privateReviewReceivesTheOriginalReceiptBeforeSharedBudgetTruncation() = runTest {
        val original = "source line\n".repeat(10000) + "Important finding at the end"
        val evidence = dev.chungjungsoo.gptmobile.data.github.GitHubReviewEvidence()
        val tool = ToolExecutionBudget(AgentRunLimits(githubTask = true)).bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text(original), false)
        })
        val args = buildJsonObject { put("path", "source.txt") }
        val delivered = withContext(ToolReceiptObserver(evidence::observe)) { tool.execute("read", args) }
        assertFalse(delivered.content.toString().contains("Important finding at the end"))
        val receipt = Json.parseToJsonElement(evidence.snapshot().single()).jsonObject
        assertEquals(JsonPrimitive(original), receipt["result"])
        assertEquals(args, receipt["arguments"])
        assertEquals(JsonPrimitive("github_read"), receipt["tool"])
        // Later delivery/replay excerpts must never overwrite the checked execution receipt.
        evidence.observe(ProviderEvent.ToolCall("read", "github_read", args), delivered)
        assertEquals(JsonPrimitive(original), Json.parseToJsonElement(evidence.snapshot().single()).jsonObject["result"])
    }

    @Test fun missingWritePathsGetSpecificRepairWithoutDispatchAndValidCorrectionCanProceed() = runTest {
        var dispatched = 0
        val tool = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 1)).bind(object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_write", "github_write__github_api")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("staged"), false)
            }
        })
        fun arguments(path: String?) = buildJsonObject {
            put("branch", "work")
            put("expected_head_sha", "a".repeat(40))
            put("message", "Repair")
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            path?.let { put("path", it) }
                            put("content", "replacement")
                        }
                    )
                )
            )
        }
        val rejected = tool.execute("missing", arguments(null))
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        assertFalse(rejected.dispatched)
        assertTrue(rejected.content.toString().contains("Each files item must contain its own non-empty repository-relative path"))
        assertTrue(rejected.content.toString().contains("do not guess"))
        assertEquals("INVALID_ARGUMENTS", tool.execute("empty", arguments("")).errorCode)
        assertEquals(0, dispatched)
        assertFalse(tool.execute("corrected", arguments("app/File.kt")).isError)
        assertEquals(1, dispatched)
    }

    @Test fun mismatchedWriteReceiptRequiresReconciliationBeforeAnotherCall() = runTest {
        var dispatched = 0
        val tool = ToolExecutionBudget(AgentRunLimits()).bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_write__github_api", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult("another-call", ToolResultContent.Text("unrelated"), false, dispatched = false)
            }
        })
        val args = buildJsonObject { put("path", "file.txt") }
        val first = tool.execute("first", args)
        assertEquals("TOOL_RESULT_ID_MISMATCH", first.errorCode)
        assertTrue(first.dispatched)
        assertEquals(JsonPrimitive("unknown"), Json.parseToJsonElement(ToolResultEnvelope.encode("github_write", first)).jsonObject["execution"]!!.jsonObject["outcome"])
        val retry = tool.execute("second", args)
        assertEquals("RECONCILIATION_REQUIRED", retry.errorCode)
        assertFalse(retry.dispatched)
        assertEquals(JsonPrimitive("unknown"), Json.parseToJsonElement(ToolResultEnvelope.encode("github_write", retry)).jsonObject["execution"]!!.jsonObject["outcome"])
        assertEquals(1, dispatched)
    }

    @Test fun aZeroGitHubOutputAllowanceDoesNotDispatch() = runTest {
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolOutputBytes = 0))
        val tool = budget.bind(object : AgentTool {
            override val definition = AgentToolDefinition("github_release", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Zero output allowance must stop before dispatch")
        })
        assertFalse(budget.canExecute())
        val result = tool.execute("blocked", JsonObject(emptyMap()))
        assertTrue(result.outputBudgetExhausted)
        assertFalse(result.dispatched)
    }

    @Test fun parallelGitHubPagesDoNotConsumeACumulative24KiBTurnBuffer() = runTest {
        var reads = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                reads++
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("body", "Release notes ".repeat(3000)) }), false)
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 12, maxToolOutputBytes = 24 * 1024))
        val tool = budget.bind(GitHubOperationTool("github_release", backend, "parallel-releases"))
        val first = (1..6).map { page ->
            async {
                tool.execute(
                    "release-$page",
                    buildJsonObject {
                        put("action", "list")
                        put("page", page)
                    }
                )
            }
        }.awaitAll()
        assertEquals(6, reads)
        assertTrue(first.sumOf { it.content.toString().encodeToByteArray().size } > 24 * 1024)
        for (result in first) {
            assertFalse(result.isError)
            assertFalse(result.outputBudgetExhausted)
            assertEquals(null, result.toolResultBudgetLimitBytes)
            val next = (result.content as ToolResultContent.Json).value.jsonObject["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
            val saved = tool.execute("saved-${result.callId}", next)
            assertFalse(saved.isError)
            assertFalse(saved.outputBudgetExhausted)
            val envelope = (saved.content as ToolResultContent.Json).value.jsonObject
            assertTrue(envelope.containsKey("fragments"))
            assertFalse(envelope.containsKey("replay_compacted"))
        }
        assertEquals(6, reads)
        val blocked = tool.execute("over-call-limit", buildJsonObject { put("action", "list") })
        assertTrue(blocked.toolCallBudgetExhausted)
        assertEquals(6, reads)
    }

    @Test fun issueDiscoveryIsAdmittedAndInventoryPagesCoverEveryAuthorizedTool() = runTest {
        val github = GitHubOperationTool.names.map { GitHubOperationTool.definition(it, it + "__github_api") }
        val extra = (1..30).map { AgentToolDefinition("other_$it", "Other authorized tool", JsonObject(emptyMap())) }
        val definitions = github + extra
        val catalog = ProgressiveToolCatalog(definitions, "List open issues")
        assertTrue(catalog.selected(definitions).any { it.name == "github_issue__github_api" })
        val seen = mutableSetOf<String>()
        var request = JsonObject(emptyMap())
        while (true) {
            val result = catalog.execute("inventory-${seen.size}", request)
            val page = (result.content as ToolResultContent.Json).value.jsonObject
            val inventory = page["authorized_inventory"] as JsonArray
            assertTrue(inventory.size <= 12)
            inventory.forEach { item ->
                assertEquals(JsonPrimitive("not tested"), item.jsonObject["operational_status"])
                assertTrue(seen.add(item.jsonObject["name"]!!.let { (it as JsonPrimitive).content }))
            }
            val next = page["continuation_call"] as? JsonObject ?: break
            request = next["arguments"]!!.jsonObject
        }
        assertEquals(definitions.map { it.name }.toSet(), seen)
        assertFalse(GitHubOperationTool.taskTools("My issue is that I cannot sleep").contains("github_issue"))
    }
}
