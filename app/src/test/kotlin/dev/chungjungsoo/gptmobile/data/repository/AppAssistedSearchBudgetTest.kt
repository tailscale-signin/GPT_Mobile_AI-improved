package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAssistedSearchBudgetTest {
    @Test fun sharedBudgetExhaustionStopsFurtherOptionalReadsAndKeepsBothSnippets() = runBlocking {
        for (exhausted in listOf(true, false)) {
            var pageReads = 0
            val search = object : AgentTool {
                override val definition = AgentToolDefinition("web_search", "Authorized aggregate", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        Json.parseToJsonElement(
                            """{"results":[{"title":"First","url":"https://example.org/first","snippet":"First retained snippet."},{"title":"Second","url":"https://example.org/second","snippet":"Second retained snippet."}]}"""
                        )
                    ),
                    false
                )
            }
            val reader = object : AgentTool {
                override val definition = AgentToolDefinition("read_url", "Read authorized page", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    pageReads++
                    return AgentToolResult(
                        callId,
                        ToolResultContent.Text("No page was read"),
                        isError = true,
                        dispatched = false,
                        errorCode = if (exhausted) "COMBINED_BUDGET_EXHAUSTED" else "READ_UNAVAILABLE",
                        combinedWorkBudgetExhausted = exhausted
                    )
                }
            }
            val trace = mockk<ToolTraceSession>()
            val started = mockk<ToolEvent>()
            every { started.sequence } returns 0
            coEvery { trace.start(any()) } returns started
            coEvery { trace.finish(any(), any()) } returns ApiState.ToolCall(0)
            val events = mutableListOf<ApiState>()
            val evidence = AppAssistedSearch.prepare(
                listOf(MessageV2(content = "Search online for updates", platformType = null)),
                listOf(
                    ResolvedAgentTool(search, "authorized-search", "Search", "web_search", "web_search"),
                    ResolvedAgentTool(reader, null, "Reader", "read_url", "read_url")
                ),
                trace,
                "accepted-run",
                2200,
                events::add
            )
            assertEquals(if (exhausted) 1 else 2, pageReads)
            assertTrue(requireNotNull(evidence).contains("First retained snippet."))
            assertTrue(evidence.contains("Second retained snippet."))
            assertTrue(evidence.contains("https://example.org/first"))
            assertTrue(evidence.contains("https://example.org/second"))
            assertFalse(evidence.contains("Page excerpt:"))
            assertFalse(events.any { it is ApiState.Error || it is ApiState.Done })
        }
    }
}
