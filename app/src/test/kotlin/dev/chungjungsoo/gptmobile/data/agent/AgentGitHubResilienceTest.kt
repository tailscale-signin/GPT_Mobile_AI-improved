package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGitHubResilienceTest {
    @Test fun changingInvalidArgumentsCannotKeepTheGitHubRunInAFailureLoop() = runTest {
        var rounds = 0
        var executions = 0
        val read = tool("github_read") { id, _ ->
            executions++
            AgentToolResult(id, ToolResultContent.Text("EVIDENCE_UNAVAILABLE"), true)
        }
        val session = session { tools, exchanges ->
            if (++rounds <= 3) {
                assertTrue(tools.isNotEmpty())
                emit(ProviderEvent.ToolCall("read-$rounds", "github_read", buildJsonObject { put("evidence_id", "invented-$rounds") }))
            } else {
                assertTrue(tools.isEmpty())
                assertTrue(exchanges.last().results.single().content.toString().contains("unfinished work"))
                emit(ProviderEvent.TextDelta("The source handle is unavailable; no changes were applied."))
            }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true, maxToolCalls = 50)).run(session, listOf(read)).toList()
        assertEquals(3, executions)
        assertEquals(4, rounds)
        assertTrue(events.any { it is AgentRunEvent.Notice && it.message.contains("three consecutive") })
        assertCompleted(events)
    }

    @Test fun successfulSavedReadResetsTheConsecutiveFailureBound() = runTest {
        var rounds = 0
        val read = tool("github_read") { id, _ ->
            AgentToolResult(id, ToolResultContent.Text("result-$rounds"), isError = rounds != 3, sharedResult = rounds == 3, dispatched = rounds != 3)
        }
        val session = session { tools, _ ->
            rounds++
            if (rounds <= 5) {
                assertTrue(tools.isNotEmpty())
                emit(ProviderEvent.ToolCall("read-$rounds", "github_read", buildJsonObject { put("path", "File$rounds.kt") }))
            } else {
                assertTrue(tools.isNotEmpty())
                emit(ProviderEvent.TextDelta("The retained source was read."))
            }
            emit(ProviderEvent.Completed)
        }
        assertCompleted(AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(read)).toList())
        assertEquals(6, rounds)
    }

    @Test fun repeatedEmptyGithubRoundsGetOneTextFinalizationWithReceiptsRetained() = runTest {
        var rounds = 0
        var executions = 0
        val write = tool("github_write") { id, _ ->
            executions++
            AgentToolResult(id, ToolResultContent.Text("commit receipt 123"), false)
        }
        val session = session { tools, exchanges ->
            when (++rounds) {
                1 -> emit(ProviderEvent.ToolCall("write", "github_write", JsonObject(emptyMap())))
                2, 3 -> assertTrue(tools.isNotEmpty())
                else -> {
                    assertTrue(tools.isEmpty())
                    assertTrue(exchanges.first().results.single().content.toString().contains("commit receipt 123"))
                    emit(ProviderEvent.TextDelta("The commit completed."))
                }
            }
            emit(ProviderEvent.Completed)
        }
        assertCompleted(AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(write)).toList())
        assertEquals(4, rounds)
        assertEquals(1, executions)
    }

    @Test fun repeatedMalformedGithubToolFramesFinalizeWithoutRepeatingACompletedWrite() = runTest {
        var rounds = 0
        var executions = 0
        val write = tool("github_write") { id, _ ->
            executions++
            AgentToolResult(id, ToolResultContent.Text("saved commit receipt"), false)
        }
        val session = session { tools, exchanges ->
            when (++rounds) {
                1 -> emit(ProviderEvent.ToolCall("write", "github_write", JsonObject(emptyMap())))
                2, 3 -> emit(ProviderEvent.TextDelta("<tool_call>{}</tool_call>"))
                else -> {
                    assertTrue(tools.isEmpty())
                    assertTrue(exchanges.first().results.single().content.toString().contains("saved commit receipt"))
                    emit(ProviderEvent.TextDelta("The commit completed; further tool calls could not be decoded."))
                }
            }
            emit(ProviderEvent.Completed)
        }
        assertCompleted(AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(write)).toList())
        assertEquals(4, rounds)
        assertEquals(1, executions)
    }

    @Test fun finalResponseInstructionsPreserveTheStructuredGithubRecoveryEnvelope() = runTest {
        var rounds = 0
        val body = buildJsonObject {
            put("schema", "github.operation.v1")
            put("operation", "github_read")
            put("data", buildJsonObject { put("evidence_id", "retained-source") })
            put(
                "recovery_call",
                buildJsonObject {
                    put("tool", "github_read")
                    put("arguments", buildJsonObject { put("view_cursor", "saved:0:1024") })
                }
            )
        }
        val read = tool("github_read") { id, _ -> AgentToolResult(id, ToolResultContent.Json(body), false) }
        val session = session { tools, exchanges ->
            if (++rounds == 1) {
                emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
            } else {
                assertTrue(tools.isEmpty())
                val envelope = GitHubReplayRecovery.envelope(exchanges.single().results.single().content)
                assertNotNull(envelope)
                assertEquals(body["data"], envelope!!["data"])
                assertEquals(body["recovery_call"], envelope["recovery_call"])
                assertNotNull(envelope["agent_instruction"])
                val compacted = GitHubReplayRecovery.compact(ToolResultContent.Json(envelope), 1200) as ToolResultContent.Json
                assertNotNull(compacted.value.jsonObject["agent_instruction"])
                emit(ProviderEvent.TextDelta("The source was read."))
            }
            emit(ProviderEvent.Completed)
        }
        assertCompleted(AgentRunner(AgentRunLimits(githubTask = true, maxToolCalls = 1)).run(session, listOf(read)).toList())
    }

    private fun assertCompleted(events: List<AgentRunEvent>) {
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    private fun session(block: suspend FlowCollector<ProviderEvent>.(List<AgentToolDefinition>, List<AgentToolExchange>) -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow { block(tools, exchanges) }
    }

    private fun tool(name: String, execute: suspend (String, JsonObject) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, name, JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = execute(callId, arguments)
    }
}
