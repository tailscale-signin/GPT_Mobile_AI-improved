package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerUserDecisionTest {
    @Test fun decisionAndWriteInOneBatchCannotRace() = runBlocking {
        var writes = 0
        var questions = 0
        val question = tool("github_decide", setOf(AgentToolCapability.USER_DECISION)) { id ->
            questions++
            AgentToolResult(id, ToolResultContent.Text("User chose compact"), false, dispatched = false)
        }
        val write = tool("github_write") { id ->
            writes++
            AgentToolResult(id, ToolResultContent.Text("written"), false)
        }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(ProviderEvent.ToolCall("write", "github_write", buildJsonObject {}))
                    emit(ProviderEvent.ToolCall("question", "github_decide", buildJsonObject {}))
                } else {
                    assertTrue(exchanges.last().results.first { it.callId == "write" }.isError)
                    emit(ProviderEvent.TextDelta("The compact option is selected; dependent work was not dispatched in the question batch."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner().run(session, listOf(question, write)).toList()
        assertEquals(1, questions)
        assertEquals(0, writes)
    }

    @Test fun interruptedLocalDecisionStaysQueuedInsteadOfBecomingAnUnknownWrite() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "decision")
        val began = CompletableDeferred<Unit>()
        val waiting = tool("github_decide", setOf(AgentToolCapability.USER_DECISION)) {
            began.complete(Unit)
            CompletableDeferred<AgentToolResult>().await()
        }
        val calls = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ToolCall("q", "github_decide", buildJsonObject {}))
                emit(ProviderEvent.Completed)
            }
        }
        val run = async { AgentRunner(checkpoint = checkpoint).run(calls, listOf(waiting)).toList() }
        began.await()
        run.cancelAndJoin()
        assertEquals(1, checkpoint.recoverySnapshot().queuedCount)
        assertEquals(0, checkpoint.recoverySnapshot().unknownCount)
        var answered = 0
        val restored = tool("github_decide", setOf(AgentToolCapability.USER_DECISION)) { id ->
            answered++
            AgentToolResult(id, ToolResultContent.Text("Saved user choice"), false, dispatched = false)
        }
        val final = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(exchanges.toString().contains("Saved user choice"))
                emit(ProviderEvent.TextDelta("Continuing with your choice."))
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(checkpoint = checkpoint).run(final, listOf(restored)).toList()
        assertEquals(1, answered)
    }

    private fun tool(name: String, capabilities: Set<AgentToolCapability> = emptySet(), action: suspend (String) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, name, buildJsonObject {}, capabilities)
        override val managesExecutionBudget = true
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = action(callId)
    }
}
