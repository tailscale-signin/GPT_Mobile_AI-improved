package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFreshProgressRunnerTest {
    @Test fun freshRetainedRunPagesReachTheGuardBeforeDisplayProjectionDropsTheirRows() = runTest {
        val (calls, events) = runPages(repeatRows = false)
        assertEquals(6, calls)
        assertFalse(events.filterIsInstance<AgentRunEvent.Notice>().any { it.message.contains("Four rounds added no new") })
        assertEquals(6, events.filterIsInstance<AgentRunEvent.ToolStarted>().size)
    }

    @Test fun changingPageArgumentsCannotHideRepeatedRunIdentities() = runTest {
        val (calls, events) = runPages(repeatRows = true)
        assertEquals(5, calls)
        assertTrue(events.filterIsInstance<AgentRunEvent.Notice>().any { it.message.contains("Four rounds added no new") })
    }

    private suspend fun runPages(repeatRows: Boolean): Pair<Int, List<AgentRunEvent>> {
        var dispatched = 0
        var rounds = 0
        val head = "a".repeat(40)
        val ci = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_ci", "github_ci__github_api")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                val page = ++dispatched
                val payload = buildJsonObject {
                    put("page", page)
                    put("per_page", 30)
                    put("returned", 30)
                    put("total_count", 224)
                    put("has_more", true)
                    put(
                        "workflow_runs",
                        JsonArray(
                            (1..30).map { offset ->
                                buildJsonObject {
                                    put("id", if (repeatRows) offset else page * 100 + offset)
                                    put("head_sha", head)
                                    put("run_attempt", 1)
                                    put("status", "completed")
                                    put("conclusion", "skipped")
                                }
                            }
                        )
                    )
                }
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.saved-observation.v1")
                            put("note", "Full workflow page retained")
                        }
                    ),
                    false,
                    retainedContent = ToolResultContent.Json(payload),
                    executionReceipt = buildJsonObject {
                        put("retained_original_complete", true)
                        put(
                            "resource_context",
                            buildJsonObject {
                                put("owner", "owner")
                                put("repo", "repo")
                                put("commit_sha", head)
                            }
                        )
                    }
                )
            }
        }
        val session = object : AgentProviderSession {
            override val handlesToolResultRecovery = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds <= 8)
                if (tools.isEmpty() || dispatched == 6) {
                    emit(ProviderEvent.TextDelta("Retained workflow observations are partial; skipped runs do not establish passed checks."))
                } else {
                    emit(
                        ProviderEvent.ToolCall(
                            "page-${dispatched + 1}",
                            ci.definition.name,
                            buildJsonObject {
                                put("action", "runs")
                                put("owner", "owner")
                                put("repo", "repo")
                                put("commit_sha", head)
                                put("page", dispatched + 1)
                                put("per_page", 30)
                            }
                        )
                    )
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, githubTaskRequest = "Inspect workflow run history", maxToolCalls = 32, maxReplayResultTokens = 64, maxRetainedEvidenceTokens = 1024))
            .run(session, listOf(ci)).toList()
        return dispatched to events
    }
}
