package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredAgentToolTest {
    @Test
    fun `resource payload measurement includes link names and mime types`() {
        val content = ToolResultContent.ResourceLinks(listOf(dev.chungjungsoo.gptmobile.data.agent.AgentResourceLink("https://example.com", "Report", "text/plain")))
        val metrics = dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics.measure("{}", content)
        val expected = """[{"uri":"https://example.com","name":"Report","mimeType":"text/plain"}]"""
        assertEquals(expected.encodeToByteArray().size, metrics.resultBytes)
    }

    private fun tool(action: suspend () -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition("test", "test", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = action()
    }

    @Test
    fun `measures unicode payload bytes and actual elapsed milliseconds`() = runBlocking {
        var clock = 0L
        val delegate = tool {
            clock = 120_999_000L
            AgentToolResult("call", ToolResultContent.Text("é🌍"), false)
        }
        val arguments = buildJsonObject { put("city", "東京") }
        val result = MeasuredAgentTool(delegate, nanoTime = { clock }).execute("call", arguments)
        val metrics = requireNotNull(result.measurement)
        assertEquals(120L, metrics.durationMs)
        assertEquals(6, metrics.resultBytes)
        assertEquals(1, metrics.estimatedResultTokens)
        assertEquals(arguments.toString().length, metrics.argumentsCharacters)
        assertEquals(arguments.toString().encodeToByteArray().size, metrics.argumentsBytes)
    }

    @Test
    fun `errors are measured and diagnostics cannot break execution`() = runBlocking {
        val failing = MeasuredAgentTool(tool { error("unavailable") }, onMeasured = { error("diagnostics unavailable") })
        val result = failing.execute("call", JsonObject(emptyMap()))
        assertTrue(result.isError)
        assertNotNull(result.measurement)
        assertEquals("unavailable", (result.content as ToolResultContent.Text).text)
    }

    @Test
    fun `cancellation propagates without reporting success`() = runBlocking {
        var reported = false
        val wrapper = MeasuredAgentTool(tool { throw CancellationException("stop") }, onMeasured = { reported = true })
        var canceled = false
        try {
            wrapper.execute("call", JsonObject(emptyMap()))
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
        assertFalse(reported)
    }

    @Test
    fun `shared result retains attribution and gets delivery timing`() = runBlocking {
        var clock = 0L
        val wrapper = MeasuredAgentTool(
            tool {
                clock = 2_000_000L
                AgentToolResult("follower", ToolResultContent.Text("cached"), false, sharedResult = true)
            },
            nanoTime = { clock }
        )
        val result = wrapper.execute("follower", JsonObject(emptyMap()))
        assertTrue(requireNotNull(result.measurement).shared)
        assertEquals(2L, result.measurement?.durationMs)
    }
}
