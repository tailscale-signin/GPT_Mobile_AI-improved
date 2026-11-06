package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryGraphFallbackTest {
    @Test fun transportFailureRetainsGraphFailureAndOneIndependentNativeReceipt() = runTest {
        var nativeReads = 0
        val receipt = JsonObject(mapOf("read_requests" to JsonPrimitive(1)))
        val tool = RepositoryGraphTool("repository_graph__active", nativeStatus = { call ->
            nativeReads++
            AgentToolResult(call, ToolResultContent.Json(JsonObject(mapOf("commit_sha" to JsonPrimitive("a".repeat(40))))), false, executionReceipt = receipt)
        }) { throw IOException("gateway unreachable") }
        val result = tool.execute("status", JsonObject(mapOf("action" to JsonPrimitive("status"))))
        val root = (result.content as ToolResultContent.Json).value as JsonObject
        val fallback = root["native_fallback"] as JsonObject
        assertTrue(result.isError)
        assertEquals("REPOSITORY_GRAPH_UNAVAILABLE", result.errorCode)
        assertEquals(JsonPrimitive(false), root["graph_complete"])
        assertEquals(JsonPrimitive("github_context__active"), fallback["tool"])
        assertEquals(receipt, fallback["execution_receipt"])
        assertEquals(1, nativeReads)
    }

    @Test fun admissionAndTlsFailuresDoNotDispatchFallback() = runTest {
        for (failure in listOf(IllegalStateException("disabled"), IllegalArgumentException("invalid"), SSLException("TLS"))) {
            var nativeReads = 0
            val tool = RepositoryGraphTool("repository_graph", nativeStatus = { call ->
                nativeReads++
                AgentToolResult(call, ToolResultContent.Text("status"), false)
            }) { throw failure }
            assertTrue(tool.execute("status", JsonObject(mapOf("action" to JsonPrimitive("status")))).isError)
            assertEquals(0, nativeReads)
        }
    }

    @Test fun graphReadFailureOffersNativeReadsWithoutDispatchingOrInventingGraphCoverage() = runTest {
        var calls = 0
        val tool = RepositoryGraphTool("repository_graph__selected", nativeStatus = {
            calls++
            error("Only status may run the native probe")
        }) { throw IOException("unreachable") }
        val result = tool.execute("graph-query", JsonObject(mapOf("action" to JsonPrimitive("query"))))
        val root = (result.content as ToolResultContent.Json).value as JsonObject
        val alternatives = root["native_read_alternatives"] as kotlinx.serialization.json.JsonArray
        assertTrue(alternatives.toString().contains("github_pr__selected"))
        for (action in listOf("resolve", "branches", "commits", "checks")) assertTrue(alternatives.toString().contains(action))
        assertEquals(JsonPrimitive(false), root["graph_complete"])
        assertEquals(0, calls)
        val denied = RepositoryGraphTool("repository_graph", nativeStatus = { error("No fallback") }) { throw IllegalStateException("permission denied") }
        val rejected = (denied.execute("denied", JsonObject(mapOf("action" to JsonPrimitive("query")))).content as ToolResultContent.Json).value as JsonObject
        assertFalse(rejected.containsKey("native_read_alternatives"))
        assertFalse(rejected.containsKey("recovery_call"))
    }

    @Test fun indexingAndCancellationAreNeverReplayed() = runTest {
        var nativeReads = 0
        val cancelled = CancellationException("cancel")
        val tool = RepositoryGraphTool("repository_graph", nativeStatus = { call ->
            nativeReads++
            AgentToolResult(call, ToolResultContent.Text("status"), false)
        }) { arguments -> if (arguments["action"] == JsonPrimitive("status")) throw cancelled else throw IOException("interrupted") }
        val index = tool.execute("index", JsonObject(mapOf("action" to JsonPrimitive("index"))))
        assertTrue(index.isError)
        assertFalse(((index.content as ToolResultContent.Json).value as JsonObject).containsKey("native_fallback"))
        assertSame(cancelled, runCatching { tool.execute("status", JsonObject(mapOf("action" to JsonPrimitive("status")))) }.exceptionOrNull())
        assertEquals(0, nativeReads)
    }

    @Test fun typedReadinessOutageAllowsOneAuthorizedReadButNeverReplaysIndexing() = runTest {
        var nativeReads = 0
        val tool = RepositoryGraphTool("repository_graph__active", nativeStatus = { call ->
            nativeReads++
            AgentToolResult(call, ToolResultContent.Text("Selected repository status"), false)
        }) { throw dev.chungjungsoo.gptmobile.data.github.GatewayGraphUnavailableException("Graph is unavailable") }
        val status = tool.execute("status", JsonObject(mapOf("action" to JsonPrimitive("status"))))
        assertTrue(status.isError)
        assertEquals(1, nativeReads)
        assertTrue(((status.content as ToolResultContent.Json).value as JsonObject).containsKey("native_fallback"))
        tool.execute("index", JsonObject(mapOf("action" to JsonPrimitive("index"))))
        assertEquals(1, nativeReads)
    }
}
