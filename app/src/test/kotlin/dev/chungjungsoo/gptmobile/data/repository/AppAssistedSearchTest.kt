package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAssistedSearchTest {
    @Test fun currentNewsTriggersSearchWithoutRequiringAMagicPhrase() {
        listOf("Latest news in Toronto", "What's happening in Toronto?", "Current weather in Toronto", "News today").forEach {
            assertTrue(it, AppAssistedSearch.requested(it))
        }
        listOf("Test", "History of France", "Explain how a search engine works", "Latest news in Toronto, without browsing", "Don't search online").forEach {
            assertFalse(it, AppAssistedSearch.requested(it))
        }
    }

    @Test fun freeTextInstructionsDoNotDemandVerificationForEveryQuestion() {
        val prompt = freeTextSystemPrompt("Keep answers short.")
        assertTrue(prompt.startsWith("Keep answers short."))
        assertTrue(prompt.contains("For general knowledge, answer normally"))
        assertFalse(prompt.contains("Native tools"))
        assertFalse(prompt.contains("If evidence is absent, state what could not be verified"))
    }

    @Test fun ellipticalFollowupKeepsTheTaskBearingQuestion() {
        val question = AppAssistedSearch.question(listOf(MessageV2(content = "What is the current status of the Orion launch?", platformType = null), MessageV2(content = "Search online and find out", platformType = null)))
        assertTrue(question.contains("Orion launch"))
        assertTrue(AppAssistedSearch.requested("Search online and find out"))
        assertFalse(AppAssistedSearch.requested("Explain how a search engine works"))
        assertTrue(AppAssistedSearch.inventoryRequest("List all the tools you have"))
    }

    @Test fun unavailableSearchDoesNotEmitSuccessfulCompletion() = runBlocking {
        val events = mutableListOf<ApiState>()
        val result = AppAssistedSearch.prepare(listOf(MessageV2(content = "Search online for launch news", platformType = null)), emptyList(), mockk(), "test", 2200, events::add)
        assertEquals(null, result)
        assertTrue(events.single() is ApiState.Error)
        assertFalse(events.any { it is ApiState.Done })
    }

    @Test fun textOnlyProfileRetrievesAuthorizedEvidenceBeforeAnswering() = runBlocking {
        var executions = 0
        val tool = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("web_search", "Authorized aggregate", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): dev.chungjungsoo.gptmobile.data.agent.AgentToolResult {
                executions++
                assertTrue(arguments.toString().contains("Orion"))
                return dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(callId, dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json(kotlinx.serialization.json.Json.parseToJsonElement("""{"results":[{"title":"Launch update","url":"https://example.org/launch","snippet":"Launch scheduled."}],"failedEngines":["DuckDuckGo"]}""")), false)
            }
        }
        val trace = mockk<ToolTraceSession>()
        val started = mockk<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent>()
        io.mockk.every { started.sequence } returns 0
        io.mockk.coEvery { trace.start(any()) } returns started
        io.mockk.coEvery { trace.finish(any(), any()) } returns ApiState.ToolCall(0)
        val events = mutableListOf<ApiState>()
        val tools = listOf(dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(tool, "authorized-exa", "Exa", "web_search", "web_search"))
        val packet = AppAssistedSearch.prepare(listOf(MessageV2(content = "Search online for Orion", platformType = null)), tools, trace, "run", 2200, events::add)
        assertEquals(1, executions)
        assertTrue(packet!!.contains("https://example.org/launch"))
        assertTrue(events.any { it is ApiState.ToolCall })
        assertFalse(events.any { it is ApiState.Error })
    }

    @Test fun compactPacketPreservesWholeUrlsAndMarksSnippetsUntrusted() {
        val sources = (1..20).map {
            buildJsonObject {
                put("title", "Title $it")
                put("url", "https://example.org/source/$it")
                put("snippet", "Evidence ".repeat(100))
            }
        }
        val packet = AppAssistedSearch.compactEvidence("Launch status", sources, 2200)
        assertTrue(packet.length <= 2200)
        assertTrue(packet.contains("Resolved question: Launch status"))
        assertTrue(packet.contains("untrusted snippets"))
        assertTrue(packet.contains("https://example.org/source/1"))
        assertFalse(packet.contains("source/20"))
    }
}
