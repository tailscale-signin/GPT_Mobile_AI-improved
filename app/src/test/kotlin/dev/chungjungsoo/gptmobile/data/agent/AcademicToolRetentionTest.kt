package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicToolRetentionTest {
    @Test
    fun academicSearchPreservesTypedPageAndCursorOutsideModelExcerpt() = runTest {
        val payload = paperPage()
        assertTrue(payload.toString().toByteArray().size > 64 * 1024)
        assertTrue(payload.toString().toByteArray().size < 256 * 1024)
        val budget = ToolExecutionBudget(AgentRunLimits())
        val result = budget.bind(fixture("deep_research_search_papers", payload)).execute("papers", JsonObject(emptyMap()))

        assertFalse(result.isError)
        assertEquals(ToolResultContent.Json(payload), result.retainedContent)
        assertEquals(50, (retained(result).getValue("papers") as JsonArray).size)
        assertEquals("2", retained(result).getValue("nextCursor").jsonPrimitive.content)
        assertTrue(contentBytes(result.content) <= 16 * 1024)
        assertTrue(budget.remainingOutputBytes() > 0)
        val trace = (result.traceContent as ToolResultContent.Text).text
        assertTrue(trace.startsWith("Redacted academic response"))
        assertFalse(trace.contains("Research fixture"))
    }

    @Test
    fun academicReaderPreservesCapturedTextWithinTheRetentionLimit() = runTest {
        val text = "Captured methods and results. ".repeat(4000)
        val payload = buildJsonObject {
            put("text", text)
            put("retrievalStatus", "read")
            put("versionId", "v1")
        }
        val result = ToolExecutionBudget(AgentRunLimits())
            .bind(fixture("deep_research_read_paper", payload))
            .execute("read", JsonObject(emptyMap()))

        assertFalse(result.isError)
        assertEquals(text, retained(result).getValue("text").jsonPrimitive.content)
        assertEquals("read", retained(result).getValue("retrievalStatus").jsonPrimitive.content)
        assertTrue(contentBytes(result.content) <= 16 * 1024)
    }

    @Test
    fun ordinaryToolsKeepTheExistingObservationLimit() = runTest {
        val result = ToolExecutionBudget(AgentRunLimits())
            .bind(fixture("web_search", paperPage()))
            .execute("ordinary", JsonObject(emptyMap()))

        assertEquals(JsonPrimitive(true), retained(result)["observationTruncated"])
        assertTrue(contentBytes(result.retainedContent ?: result.content) <= 64 * 1024)
    }

    @Test
    fun academicResultsAboveTheHardLimitRemainBounded() = runTest {
        val payload = buildJsonObject {
            put("text", "x".repeat(512 * 1024))
            put("retrievalStatus", "read")
        }
        val result = ToolExecutionBudget(AgentRunLimits())
            .bind(fixture("deep_research_read_paper", payload))
            .execute("oversized", JsonObject(emptyMap()))

        assertEquals(JsonPrimitive(true), retained(result)["observationTruncated"])
        assertTrue(contentBytes(result.retainedContent ?: result.content) <= 256 * 1024)
        assertTrue(contentBytes(result.content) <= 16 * 1024)
    }

    @Test
    fun academicRetentionDoesNotReplenishTheSharedOutputAllowance() = runTest {
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 1024))
        val result = budget.bind(fixture("deep_research_search_papers", paperPage()))
            .execute("limited", JsonObject(emptyMap()))

        assertEquals(0, budget.remainingOutputBytes())
        assertTrue(result.outputBudgetExhausted)
        assertFalse(budget.canExecute())
        assertTrue(contentBytes(result.content) <= 1024)
    }

    private fun paperPage(): JsonObject = buildJsonObject {
        put(
            "papers",
            JsonArray(
                (1..50).map { index ->
                    buildJsonObject {
                        put("paperId", "doi:10.1234/fixture$index")
                        put("canonicalUrl", "https://example.org/fixture/$index")
                        put("title", "Research fixture $index")
                        put("abstract", "Metadata lead ".repeat(120))
                    }
                }
            )
        )
        put("nextCursor", "2")
    }

    private fun fixture(name: String, payload: JsonObject): AgentTool = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Recorded fixture", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
            callId,
            ToolResultContent.Json(payload),
            false,
            traceContent = ToolResultContent.Text("Redacted academic response")
        )
    }

    private fun retained(result: AgentToolResult): JsonObject =
        ((result.retainedContent ?: result.content) as ToolResultContent.Json).value as JsonObject

    private fun contentBytes(content: ToolResultContent): Int = when (content) {
        is ToolResultContent.Text -> content.text.toByteArray().size
        is ToolResultContent.Json -> content.value.toString().toByteArray().size
        is ToolResultContent.ResourceLinks -> content.links.sumOf { it.uri.toByteArray().size }
    }
}
