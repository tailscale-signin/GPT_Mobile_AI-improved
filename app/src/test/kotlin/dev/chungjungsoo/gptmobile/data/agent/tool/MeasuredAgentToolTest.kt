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
    fun `legacy failed results receive a code while retaining provider receipts`() = runBlocking {
        val provider = buildJsonObject {
            put("requestId", "request-17")
            put("schema", "gptmobile.search-operation.v1")
        }
        val result = MeasuredAgentTool(
            tool {
                AgentToolResult("call", ToolResultContent.Text("unable to resolve host search.example"), true, errorCode = "none", executionReceipt = provider)
            }
        ).execute("call", JsonObject(emptyMap()))
        assertEquals("DNS_RESOLUTION_FAILED", result.errorCode)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("request-17"), result.executionReceipt?.get("requestId"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("gptmobile.search-operation.v1"), result.executionReceipt?.get("schema"))
    }

    @Test
    fun `a transport exception after mutation dispatch remains unknown and cannot be replayed`() = runBlocking {
        val delegate = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Write to repository", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = throw java.net.SocketTimeoutException("response timed out")
        }
        val result = MeasuredAgentTool(delegate).execute("write", buildJsonObject { put("action", "create_issue") })
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", result.errorCode)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("NETWORK_TIMEOUT"), result.executionReceipt?.get("cause_code"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("UNKNOWN_OUTCOME"), result.executionReceipt?.get("execution_state"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), result.executionReceipt?.get("requires_reconciliation"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), result.executionReceipt?.get("retryable"))
        assertTrue(result.dispatched)
    }

    @Test
    fun `remote reader capability alone cannot authorize replay after transport failure`() = runBlocking {
        val delegate = object : AgentTool {
            override val definition = AgentToolDefinition("mcp__server__read_url", "Reader", JsonObject(emptyMap()), capabilities = setOf(dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability.PAGE_READER))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = throw java.net.SocketTimeoutException("response timed out")
        }
        val measured = MeasuredAgentTool(delegate)
        val result = measured.execute("read", JsonObject(emptyMap()))
        assertFalse(dev.chungjungsoo.gptmobile.data.agent.ToolReplaySafety.isReplayableRead(measured.definition.name, JsonObject(emptyMap()), measured))
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", result.errorCode)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), result.executionReceipt?.get("retryable"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), result.executionReceipt?.get("requires_reconciliation"))
    }

    @Test
    fun `safe read exceptions preserve DNS cause and bounded recovery eligibility`() = runBlocking {
        val delegate = object : AgentTool {
            override val definition = AgentToolDefinition("read_url", "Read a source", JsonObject(emptyMap()), capabilities = setOf(dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability.PAGE_READER))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = throw java.io.IOException("request failed", java.net.UnknownHostException("source.example"))
        }
        val result = MeasuredAgentTool(delegate, approvedReadOnly = true).execute("read", JsonObject(emptyMap()))
        assertEquals("DNS_RESOLUTION_FAILED", result.errorCode)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), result.executionReceipt?.get("retryable"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
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
