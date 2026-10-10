package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolIntegrityTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","minLength":1}},"required":["id"],"additionalProperties":false}""") as JsonObject

    @Test
    fun missingIdentifierNeverDispatches() = runBlocking {
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("unsubscribe", "Mutation fixture", schema)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("sent"), false)
            }
        }
        val result = ToolExecutionBudget(AgentRunLimits()).bind(tool).execute("bad", JsonObject(emptyMap()))
        assertEquals(0, dispatched)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertTrue(ToolResultEnvelope.encode("unsubscribe", result).contains("not_executed"))
    }

    @Test
    fun unchangedInvalidCallsStopAndSmallEvidenceIsNotRewritten() = runBlocking {
        val bound = ToolExecutionBudget(AgentRunLimits()).bind(object : AgentTool {
            override val definition = AgentToolDefinition("unsubscribe", "Mutation fixture", schema)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Must not dispatch")
        })
        val arguments = JsonObject(emptyMap())
        assertEquals("INVALID_ARGUMENTS", bound.execute("first", arguments).errorCode)
        assertEquals("INVALID_ARGUMENTS", bound.execute("second", arguments).errorCode)
        assertEquals("REPEATED_FAILURE", bound.execute("third", arguments).errorCode)
        val small = ToolResultContent.Text("Exact observation")
        assertEquals(small, ToolResultEnvelope.compact(small, 1024))
    }

    @Test
    fun largeJsonThenSecondToolPreservesWholeEvidence() = runBlocking {
        val payload = buildJsonObject {
            put("indexedFallback", true)
            put("notice", "Prices and availability are unconfirmed")
            put(
                "results",
                JsonArray(
                    (0..30).map { index ->
                        buildJsonObject {
                            put("id", index)
                            put("url", "https://example.org/$index")
                            put("text", "evidence ".repeat(80))
                        }
                    }
                )
            )
        }
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("search", "Search fixture", buildJsonObject { put("type", "object") })
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Json(payload), false)
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 512 * 1024), evidenceBytesPerResult = 4096)
        val bound = budget.bind(tool)
        val first = bound.execute("first", JsonObject(emptyMap()))
        val second = bound.execute("second", JsonObject(emptyMap()))
        assertEquals(2, executions)
        assertFalse(first.isError)
        assertFalse(second.isError)
        val value = ToolResultEnvelope.element(first.content) as JsonObject
        assertEquals(payload["notice"], value["notice"])
        assertEquals(JsonPrimitive(true), value["indexedFallback"])
        assertTrue((value["results"] as JsonArray).all { record -> record in payload["results"] as JsonArray })
        Json.parseToJsonElement(ToolResultEnvelope.encode("search", first))
        Unit
    }

    @Test
    fun timeoutAfterDispatchRemainsUnknown() = runBlocking {
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("send", "Mutation fixture", buildJsonObject { put("type", "object") })
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                delay(100)
                return AgentToolResult(callId, ToolResultContent.Text("done"), false)
            }
        }
        val result = ToolExecutionBudget(AgentRunLimits(toolTimeoutMillis = 1)).bind(tool).execute("write", JsonObject(emptyMap()))
        assertEquals(1, executions)
        assertEquals("TIMEOUT_OUTCOME_UNKNOWN", result.errorCode)
        assertTrue(result.dispatched)
        assertTrue(ToolResultEnvelope.encode("send", result).contains("unknown"))
    }

    @Test
    fun schemaRejectsWrongTypesAndUndeclaredFields() {
        assertTrue(ToolArgumentValidator.errors(buildJsonObject { put("id", 5) }, schema).isNotEmpty())
        assertTrue(
            ToolArgumentValidator.errors(
                buildJsonObject {
                    put("id", "x")
                    put("extra", true)
                },
                schema
            ).isNotEmpty()
        )
        assertTrue(ToolArgumentValidator.errors(buildJsonObject { put("id", "x") }, schema).isEmpty())
    }
}
