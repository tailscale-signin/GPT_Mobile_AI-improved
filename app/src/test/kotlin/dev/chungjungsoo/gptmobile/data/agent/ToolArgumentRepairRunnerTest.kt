package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentRepairRunnerTest {
    private val definition = AgentToolDefinition(
        "github_issue__fixture",
        "Read issue fixture",
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject { put("issue_number", buildJsonObject { put("type", "integer") }) })
        }
    )

    @Test fun unchangedValidationFailuresFinishAfterOneCorrectionWithoutExecutingOrPoisoningOtherTurns() = runBlocking {
        val runner = AgentRunner(AgentRunLimits(githubTask = true, maxRounds = 12))
        val tool = object : AgentTool {
            override val definition = this@ToolArgumentRepairRunnerTest.definition
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Invalid arguments must not execute")
        }
        repeat(2) {
            var rounds = 0
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    if (tools.isEmpty()) {
                        emit(ProviderEvent.TextDelta("The issue request remains blocked by an undeclared field."))
                    } else {
                        assertTrue("Unchanged validation failures must stop after one unsuccessful correction", rounds <= 2)
                        emit(ProviderEvent.ToolCall("invalid-$rounds", definition.name, buildJsonObject { put("number", rounds) }))
                    }
                    emit(ProviderEvent.Completed)
                }
            }
            val events = runner.run(provider, listOf(tool)).toList()
            assertEquals(3, rounds)
            val receipts = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
            assertEquals(2, receipts.size)
            assertTrue(receipts.all { !it.dispatched })
            assertEquals(JsonPrimitive(true), receipts.last().executionReceipt?.get("argument_repair_exhausted"))
            assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
        }
    }

    @Test fun nativeBudgetAcceptsAValidCorrectionAfterRejectedSchemaProposals() = runBlocking {
        var executed = 0
        val tool = ToolExecutionBudget(AgentRunLimits()).bind(object : AgentTool {
            override val definition = this@ToolArgumentRepairRunnerTest.definition
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executed++
                return AgentToolResult(callId, ToolResultContent.Text("Confirmed issue"), false)
            }
        })
        repeat(3) { index ->
            val result = tool.execute("invalid-$index", buildJsonObject { put("number", index + 1) })
            assertEquals(JsonPrimitive(index >= 1), result.executionReceipt?.get("argument_repair_exhausted"))
        }
        val result = tool.execute("corrected", buildJsonObject { put("issue_number", 4) })
        assertTrue(!result.isError && result.dispatched)
        assertEquals(1, executed)
    }

    @Test fun semanticPrecheckReusesAnUnchangedRejectionButAllowsCorrectedArguments() = runBlocking {
        var executed = 0
        val tool = ToolExecutionBudget(AgentRunLimits()).bind(object : AgentTool {
            override val definition = this@ToolArgumentRepairRunnerTest.definition
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executed++
                return if (arguments["issue_number"] == JsonPrimitive(1)) {
                    AgentToolResult(callId, ToolResultContent.Text("Select an issue admitted for this task."), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
                } else {
                    AgentToolResult(callId, ToolResultContent.Text("Confirmed issue"), false)
                }
            }
        })
        val arguments = buildJsonObject { put("issue_number", 1) }
        val rejected = tool.execute("first", arguments)
        val repeated = tool.execute("new-id", arguments)
        assertTrue(rejected.isError && !rejected.dispatched && !rejected.sharedResult)
        assertEquals("new-id", repeated.callId)
        assertEquals(rejected.content, repeated.content)
        assertEquals("INVALID_ARGUMENTS", repeated.errorCode)
        assertTrue(repeated.isError && !repeated.dispatched && repeated.sharedResult)
        assertEquals(1, executed)

        val corrected = tool.execute("corrected", buildJsonObject { put("issue_number", 2) })
        assertTrue(!corrected.isError && corrected.dispatched)
        assertEquals(2, executed)
    }
}
