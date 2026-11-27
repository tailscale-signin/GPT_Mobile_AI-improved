package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
        val result = AppAssistedSearch.prepare(listOf(MessageV2(content = "Search online for launch news", platformType = null)), emptyList(), mockk(), "test", 2200, events::add) {
            AppAssistedSearch.readiness(false, false, false, emptyList(), emptyList(), emptyList())
        }
        assertEquals(null, result)
        val error = events.single() as ApiState.Error
        assertEquals("TOOL_UNAVAILABLE", error.errorCode)
        assertEquals("BLOCKED", error.taskOutcome)
        assertEquals("tool_readiness", error.failureDetails?.get("failed_stage")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), error.failureDetails?.get("dispatched"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("web_search"), error.failureDetails?.get("operation"))
        fun report(fields: Map<String, Any?>) = IssueReportContext.fromFields("CHAT_FAILURE", IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "TOOL_UNAVAILABLE", fields), 1000)
        val details = requireNotNull(error.failureDetails)
        val diagnostics = requireNotNull(report(details).diagnostics)
        assertEquals(JsonPrimitive("NO_ENGINE_RESOLVED"), diagnostics.values["app_search_readiness"])
        assertTrue(IssueReportDiagnostics.SEARCH_READINESS_FEATURE in diagnostics.requiredServiceFeatures())
        assertFalse("app_search_readiness" in requireNotNull(report(details + ("dispatched" to true)).diagnostics).values)
        assertFalse("app_search_readiness" in requireNotNull(report(details + ("search_readiness_observation" to "private text")).diagnostics).values)
        assertFalse(events.any { it is ApiState.Done })
    }

    @Test fun searchReadinessIdentifiesTheFirstInventoryThatLostAnEngine() {
        val engine = mockk<dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool>()
        io.mockk.every { engine.modelToolName } returns "web_search"
        val one = listOf(engine)
        val none = emptyList<dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool>()
        assertEquals(AppAssistedSearch.Readiness.CHAT_TOOLS_DISABLED, AppAssistedSearch.readiness(true, false, false, none, none, none))
        assertEquals(AppAssistedSearch.Readiness.PROFILE_TOOLS_DISABLED, AppAssistedSearch.readiness(false, true, false, none, none, none))
        assertEquals(AppAssistedSearch.Readiness.PROFILE_REMOTE_DISABLED, AppAssistedSearch.readiness(false, false, true, none, none, none))
        assertEquals(AppAssistedSearch.Readiness.ROUTED_ENGINE_MISSING, AppAssistedSearch.readiness(false, false, false, one, none, none))
        assertEquals(AppAssistedSearch.Readiness.BOUND_ENGINE_MISSING, AppAssistedSearch.readiness(false, false, false, one, one, none))
        assertEquals(AppAssistedSearch.Readiness.AGGREGATE_NOT_CREATED, AppAssistedSearch.readiness(false, false, false, one, one, one))
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

    @Test fun dnsFailureAndConnectivityPrecheckRetainTheToolCauseAtTheChatBoundary() = runBlocking {
        for ((code, phase) in listOf("DNS_FAILED" to "dns_resolution", "DNS_RECOVERY" to "connection_preflight", "THROWN" to "dns_resolution")) {
            val tool = mockk<dev.chungjungsoo.gptmobile.data.agent.AgentTool>()
            io.mockk.coEvery { tool.execute(any(), any()) } coAnswers {
                if (code == "THROWN") throw java.net.UnknownHostException("Search service unavailable")
                dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.error(firstArg(), code, "Search unavailable")
            }
            val trace = mockk<ToolTraceSession>()
            val started = mockk<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent>(relaxed = true)
            io.mockk.every { started.callId } returns "run:app-search"
            io.mockk.every { started.modelToolName } returns "web_search"
            io.mockk.coEvery { trace.start(any()) } returns started
            io.mockk.coEvery { trace.finish(any(), any()) } returns ApiState.ToolCall(0)
            val events = mutableListOf<ApiState>()
            val resolved = dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(tool, null, "Search", "web_search", "web_search")
            val result = AppAssistedSearch.prepare(listOf(MessageV2(content = "Search online for Orion", platformType = null)), listOf(resolved), trace, "run", 2200, events::add)
            val failure = events.filterIsInstance<ApiState.Error>().single()
            assertEquals(null, result)
            assertEquals("DNS_RESOLUTION_FAILED", failure.errorCode)
            assertEquals(JsonPrimitive(phase), failure.failureDetails?.get("failed_stage"))
            assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive(true), failure.failureDetails?.get("retryable"))
            io.mockk.coVerify(exactly = 1) { tool.execute(any(), any()) }
        }
    }

    @Test fun javaHeapPressureSkipsOptionalPageEnrichmentAndRetainsSearchSnippets() = runBlocking {
        var pageReads = 0
        val search = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("web_search", "Authorized aggregate", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject) =
                dev.chungjungsoo.gptmobile.data.agent.AgentToolResult(
                    callId,
                    dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json(
                        kotlinx.serialization.json.Json.parseToJsonElement("""{"results":[{"title":"Launch update","url":"https://example.org/launch","snippet":"Launch scheduled for October 27."}]}""")
                    ),
                    false
                )
        }
        val reader = object : dev.chungjungsoo.gptmobile.data.agent.AgentTool {
            override val definition = dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition("read_url", "Read public page", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): dev.chungjungsoo.gptmobile.data.agent.AgentToolResult {
                pageReads++
                return dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.error(callId, "JAVA_HEAP_PRESSURE", "waiting for Java heap capacity")
            }
        }
        val trace = mockk<ToolTraceSession>()
        val started = mockk<dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent>()
        io.mockk.every { started.sequence } returns 0
        io.mockk.coEvery { trace.start(any()) } returns started
        io.mockk.coEvery { trace.finish(any(), any()) } returns ApiState.ToolCall(0)
        val events = mutableListOf<ApiState>()
        val tools = listOf(
            dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(search, "authorized-search", "Search", "web_search", "web_search"),
            dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool(reader, null, "Reader", "read_url", "read_url")
        )

        val packet = AppAssistedSearch.prepare(
            listOf(MessageV2(content = "Search online for Orion", platformType = null)),
            tools,
            trace,
            "run",
            2200,
            events::add
        )

        assertNotNull(packet)
        assertTrue(requireNotNull(packet).contains("Launch scheduled for October 27."))
        assertEquals(1, pageReads)
        assertTrue(events.any { it is ApiState.Notice })
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
