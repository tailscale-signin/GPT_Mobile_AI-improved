package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubInvestigationRecoveryRunnerTest {
    @Test fun unknownWriteOutcomeDoesNotActivateAutomaticCiRecovery() = runTest {
        var writes = 0
        var modelRounds = 0
        val write = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Fixture write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                writes++
                return AgentToolResult(callId, ToolResultContent.Text("Write outcome is unknown"), true, errorCode = "DISPATCH_OUTCOME_UNKNOWN")
            }
        }
        val ci = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_ci")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(JsonPrimitive("workflows"), arguments["action"])
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("total_count", 57) }), false)
            }
        }
        val context = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_context")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Unknown writes must not activate automatic investigation reads")
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                modelRounds++
                check(modelRounds <= 7)
                when {
                    modelRounds == 1 -> emit(ProviderEvent.ToolCall("uncertain-write", "github_write", JsonObject(emptyMap())))
                    tools.isNotEmpty() -> emit(ProviderEvent.ToolCall("workflows-$modelRounds", "github_ci", buildJsonObject { put("action", "workflows") }))
                    else -> emit(ProviderEvent.TextDelta("The write requires reconciliation. Retained observations do not establish completion."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, githubTaskRequest = "Diagnose CI failures", maxToolCalls = 32))
            .run(session, listOf(write, ci, context)).toList()
        assertEquals(1, writes)
        assertEquals(7, modelRounds)
        assertFalse(events.filterIsInstance<AgentRunEvent.ToolStarted>().any { it.call.callId.startsWith("ci-investigation-read-") })
    }

    @Test fun investigationReadsStopAtTheAcceptedCallCeiling() = runTest {
        var calls = 0
        var modelRounds = 0
        val read = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_ci")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls++
                assertEquals(JsonPrimitive("workflows"), arguments["action"])
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("total_count", 57) }), false)
            }
        }
        val context = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_context")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls++
                assertEquals(4, calls)
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("commit_sha", "a".repeat(40)) }), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                modelRounds++
                check(modelRounds <= 6)
                if (tools.isEmpty()) {
                    assertEquals(4, calls)
                    emit(ProviderEvent.TextDelta("The accepted four-call allowance ended after pinning the revision. Runs, jobs and logs remain unobserved."))
                } else {
                    emit(ProviderEvent.ToolCall("workflows-$modelRounds", "github_ci", buildJsonObject { put("action", "workflows") }))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, githubTaskRequest = "Diagnose CI failures", maxToolCalls = 4))
            .run(session, listOf(read, context)).toList()
        assertEquals(4, calls)
        assertEquals(6, modelRounds)
        assertEquals(1, events.filterIsInstance<AgentRunEvent.ToolStarted>().count { it.call.callId.startsWith("ci-investigation-read-") })
        assertFalse(events.filterIsInstance<AgentRunEvent.ToolStarted>().any { it.call.arguments["action"] == JsonPrimitive("runs") })
    }

    @Test fun repeatedWorkflowPagesWithTwentyEightCallsRemainingAdvanceToRunsJobsAndLogs() = runTest {
        val head = "a".repeat(40)
        val executed = mutableListOf<JsonObject>()
        var modelRounds = 0
        val ci = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_ci")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executed += arguments
                val data = when (arguments["action"]) {
                    JsonPrimitive("workflows") -> buildJsonObject {
                        put("total_count", 57)
                        put("page", arguments["page"] ?: JsonPrimitive(1))
                    }
                    JsonPrimitive("runs") -> {
                        assertEquals(JsonPrimitive(head), arguments["commit_sha"])
                        buildJsonObject {
                            put(
                                "workflow_runs",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("id", 61)
                                            put("run_attempt", 2)
                                            put("head_sha", head)
                                            put("status", "completed")
                                            put("conclusion", "failure")
                                        }
                                    )
                                )
                            )
                        }
                    }
                    JsonPrimitive("jobs") -> {
                        assertEquals(JsonPrimitive(61L), arguments["run_id"])
                        assertEquals(JsonPrimitive(2), arguments["run_attempt"])
                        buildJsonObject {
                            put("run_id", 61)
                            put("run_attempt", 2)
                            put("head_sha", head)
                            put(
                                "jobs",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("id", 71)
                                            put("status", "completed")
                                            put("conclusion", "failure")
                                        }
                                    )
                                )
                            )
                        }
                    }
                    JsonPrimitive("logs") -> {
                        assertEquals(JsonPrimitive(71L), arguments["job_id"])
                        assertEquals(JsonPrimitive(2), arguments["run_attempt"])
                        assertEquals(JsonPrimitive(head), arguments["expected_head_sha"])
                        buildJsonObject {
                            put("total_lines", 50)
                            put(
                                "excerpts",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("line", 30)
                                            put("text", "test assertion failed")
                                        }
                                    )
                                )
                            )
                        }
                    }
                    else -> error("Unexpected action: $arguments")
                }
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.operation.v1")
                            put("data", data)
                            if (arguments["action"] == JsonPrimitive("workflows") && arguments["page"] == null) {
                                put(
                                    "continuation_call",
                                    buildJsonObject {
                                        put("tool", "github_ci")
                                        put(
                                            "arguments",
                                            buildJsonObject {
                                                put("action", "workflows")
                                                put("page", 2)
                                                put("per_page", 30)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    ),
                    false
                )
            }
        }
        val context = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_context")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(4, executed.size)
                assertEquals(28, 32 - executed.size)
                executed += arguments
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("commit_sha", head) }), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                modelRounds++
                assertTrue("A repeated workflow read must not tear down independent reads", tools.isNotEmpty())
                if (executed.none { it["action"] == JsonPrimitive("logs") }) {
                    check(modelRounds <= 5) { "The stalled workflow loop did not select its bounded CI recovery" }
                    emit(ProviderEvent.ToolCall("model-workflows-$modelRounds", "github_ci", buildJsonObject { put("action", "workflows") }))
                } else {
                    assertTrue(exchanges.any { it.calls.any { call -> call.arguments["action"] == JsonPrimitive("jobs") } })
                    emit(ProviderEvent.TextDelta("Observed failed run 61 attempt 2, job 71 and its log. No new tests were executed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, githubTaskRequest = "Diagnose CI failures", maxToolCalls = 32, maxReplayTokens = 8192))
            .run(session, listOf(ci, context)).toList()
        assertEquals(listOf("workflows", "workflows", "workflows", "workflows", "status", "runs", "jobs", "logs").map(::JsonPrimitive), executed.map { it["action"] })
        assertEquals(6, modelRounds)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }
}
