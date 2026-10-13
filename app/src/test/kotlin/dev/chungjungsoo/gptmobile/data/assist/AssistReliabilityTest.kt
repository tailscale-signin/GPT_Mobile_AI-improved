package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistReliabilityTest {
    private fun tool(content: ToolResultContent) = object : AgentTool {
        override val definition = AgentToolDefinition("source", "Synthetic source", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, content, false)
    }

    @Test
    fun ix04FullGoalTailSurvivesAndProviderSchemaIsUnchanged() = runBlocking {
        val source = ToolResultContent.Json(JsonObject(mapOf("results" to JsonArray((0..8).map { JsonObject(mapOf("title" to JsonPrimitive("Record $it"))) }))))
        val goal = "prefix ".repeat(300) + "REQUIRED TAIL: preserve 3.5 mg and do not use 3.5 g"
        var sent = ""
        val assist = AssistCoordinator(goal, { true }, { sent = it; "[2]" }, {})
        val result = assist.bind(tool(source)).execute("call", JsonObject(emptyMap()))
        assertTrue(sent.contains(goal))
        assertEquals(source, result.content)
        assertTrue(result.assistMetadata.isNotEmpty())
    }

    @Test
    fun laQa09OldCharacterBoundariesDoNotGateTasks() = runBlocking {
        for (length in listOf(4095, 4096, 12000, 12001)) {
            var calls = 0
            val states = mutableListOf<AssistTaskState>()
            val source = ToolResultContent.Text("e".repeat(length))
            val coordinator = AssistCoordinator("goal", { true }, { calls++; "{\"queries\":[\"source evidence\"]}" }, {}, policy = LocalAssistPolicy(evidenceSelection = false, proposeQueries = true), status = { states += it })
            val result = coordinator.bind(tool(source)).execute("call", JsonObject(emptyMap()))
            assertEquals(1, calls)
            assertEquals(source, result.content)
            assertEquals(AssistDelivery.VALIDATED, states.last().delivery)
        }
    }

    @Test
    fun ix05NoSpanOrOversizedGoalDoesNotConsumeAttempt() = runBlocking {
        var calls = 0
        val coordinator = AssistCoordinator("whole task ".repeat(1000), { true }, { calls++; "[]" }, {}, policy = LocalAssistPolicy(summarizeRetrievedText = true), contextTokens = { 1024 })
        coordinator.bind(tool(ToolResultContent.Json(JsonObject(emptyMap())))).execute("empty", JsonObject(emptyMap()))
        coordinator.bind(tool(ToolResultContent.Text("short useful snippet"))).execute("too-large", JsonObject(emptyMap()))
        assertEquals(0, calls)
    }

    @Test
    fun laQa11LongFieldsAndShortSnippetsRetainExactOffsets() {
        val text = "short snippet\n" + "🙂é long source. ".repeat(1000)
        val spans = AssistHandoffContract.spans(JsonObject(mapOf("text" to JsonPrimitive(text))))
        assertTrue(spans.size > 1)
        assertEquals(text, spans.joinToString("") { it.text })
        spans.forEach { assertEquals(it.text, text.substring(it.startUtf16, it.endUtf16)) }
        assertEquals("12 kg NOT approved", AssistHandoffContract.spans(JsonPrimitive("12 kg NOT approved")).single().text)
    }

    @Test
    fun ix06RuntimeChangeCannotReuseCachedSelection() = runBlocking {
        var runtime = "session-a"
        var calls = 0
        val source = ToolResultContent.Json(JsonObject(mapOf("results" to JsonArray((0..8).map { JsonPrimitive("r$it") }))))
        val coordinator = AssistCoordinator("goal", { true }, { calls++; "[0]" }, {}, turnId = java.util.UUID.randomUUID().toString(), runtimeFingerprint = { runtime })
        coordinator.bind(tool(source)).execute("one", JsonObject(emptyMap()))
        runtime = "session-b"
        coordinator.bind(tool(source)).execute("two", JsonObject(emptyMap()))
        assertEquals(2, calls)
    }

    @Test
    fun terminalStateRejectsLateRunningCallback() {
        val done = AssistTaskState("task", sequence = 4, execution = AssistExecution.COMPLETED, delivery = AssistDelivery.REJECTED)
        assertFalse(done.accepts(done.copy(sequence = 5, execution = AssistExecution.RUNNING)))
        assertFalse(done.accepts(done.copy(sequence = 3)))
        assertFalse(done.accepts(done.copy(taskId = "other", sequence = 5)))
    }
}
