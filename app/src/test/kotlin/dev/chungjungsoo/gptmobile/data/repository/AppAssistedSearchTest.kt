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

    @Test fun lifeStatusQuestionsUseFreshEvidenceAndRespectOfflineRequests() {
        listOf("Is Hulk Hogan dead?", "Is this actor still alive?", "Did Hulk Hogan die?").forEach {
            assertTrue(it, AppAssistedSearch.requested(it))
        }
        assertFalse(AppAssistedSearch.requested("Is Hulk Hogan dead? Don't search online"))
        assertFalse(AppAssistedSearch.requested("Explain dead code elimination"))
    }

    @Test fun tellMeKeepsTheOriginalQuestionAcrossSearchFollowups() {
        val messages = listOf("Is Hulk Hogan dead?", "search the web", "tell me").map { MessageV2(content = it, platformType = null) }
        assertTrue(AppAssistedSearch.requested(messages))
        assertEquals("Is Hulk Hogan dead?", AppAssistedSearch.question(messages))
        assertFalse(AppAssistedSearch.requested(listOf(MessageV2(content = "tell me", platformType = null))))
    }

    @Test fun excerptFindsRelevantEvidenceBeyondPageNavigation() {
        val page = "Navigation menu and advertisements. ".repeat(100) + "Hulk Hogan died on July 24, 2025." + " Other unrelated content.".repeat(100)
        val excerpt = AppAssistedSearch.pageExcerpt("Is Hulk Hogan dead?", page)
        assertTrue(excerpt.contains("July 24, 2025"))
        assertTrue(excerpt.length <= 1600)
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
        val reader = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("read_url", "Read public page", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): dev.chungjungsoo.gptmobile.data.agent.AgentToolResult {
                assertEquals("https://example.org/launch", (arguments["url"] as kotlinx.serialization.json.JsonPrimitive).content)
                executions++
                return dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(callId, dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Text("Orion launch confirmed for October 27."), false)
            }
        }
        val trace = mockk<ToolTraceSession>()
        val started = mockk<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent>()
        io.mockk.every { started.sequence } returns 0
        io.mockk.coEvery { trace.start(any()) } returns started
        io.mockk.coEvery { trace.finish(any(), any()) } returns ApiState.ToolCall(0)
        val events = mutableListOf<ApiState>()
        val tools = listOf(
            dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(tool, "authorized-exa", "Exa", "web_search", "web_search"),
            dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(reader, null, "Reader", "read_url", "read_url")
        )
        val packet = AppAssistedSearch.prepare(listOf(MessageV2(content = "Search online for Orion", platformType = null)), tools, trace, "run", 2200, events::add)
        assertEquals(2, executions)
        assertTrue(packet!!.contains("https://example.org/launch"))
        assertTrue(packet.contains("Orion launch confirmed for October 27."))
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
