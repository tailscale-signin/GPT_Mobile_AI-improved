package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedPageAdmissionRunnerTest {
    @Test fun multipleCapacityRefusalsDeliverEveryFullPageBeforeTheNextAutomaticRead() = runBlocking {
        val fixtures = List(6) { GitHubSavedPageCapacityTest.Fixture("source-$it") }
        val completePages = mutableMapOf<String, ToolResultContent>()
        val deliveredPages = mutableSetOf<String>()
        var localReads = 0
        var providerRounds = 0
        val reader = object : AgentTool {
            override val definition = fixtures.first().tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                localReads++
                val view = arguments.getValue("view_cursor").jsonPrimitive.content.substringBefore(':')
                val fixture = fixtures.single { it.cursor.substringBefore(':') == view }
                val result = fixture.read(ProviderEvent.ToolCall(callId, definition.name, arguments))
                if (!result.isError) completePages[callId] = result.content
                return result
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                if (exchanges.isEmpty()) {
                    fixtures.forEachIndexed { index, fixture -> emit(ProviderEvent.ToolCall("provider-page-$index", reader.definition.name, fixture.blocked.arguments)) }
                } else {
                    val pending = completePages.keys - deliveredPages
                    assertEquals("Another automatic read must wait for this exact page", 1, pending.size)
                    val id = pending.single()
                    assertEquals(completePages.getValue(id), exchanges.flatMap { it.results }.single { it.callId == id }.content)
                    assertTrue(ToolExchangeCompactor.estimateTokens(exchanges) <= 4000)
                    deliveredPages += id
                    if (deliveredPages.size == fixtures.size) emit(ProviderEvent.TextDelta("All retained pages were delivered."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxRounds = 20, maxToolCalls = 20, maxReplayTokens = 4000, maxReplayResultTokens = 512, maxToolOutputBytes = 8192)
        ).run(provider, listOf(reader)).toList()
        assertEquals(12, localReads)
        assertEquals(7, providerRounds)
        assertEquals(6, deliveredPages.size)
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
    }

    @Test fun blockedDeliveryRoundCannotDispatchTheNextAutomaticPage() = runBlocking {
        val fixtures = List(2) { GitHubSavedPageCapacityTest.Fixture("source-$it") }
        var localReads = 0
        var providerRounds = 0
        val reader = object : AgentTool {
            override val definition = fixtures.first().tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                localReads++
                val view = arguments.getValue("view_cursor").jsonPrimitive.content.substringBefore(':')
                return fixtures.single { it.cursor.substringBefore(':') == view }.read(ProviderEvent.ToolCall(callId, definition.name, arguments))
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                if (exchanges.isEmpty()) {
                    fixtures.forEachIndexed { index, fixture -> emit(ProviderEvent.ToolCall("provider-page-$index", reader.definition.name, fixture.blocked.arguments)) }
                    emit(ProviderEvent.Completed)
                } else {
                    emit(
                        ProviderEvent.Failed(
                            "Provider delivery blocked.",
                            errorCode = "INPUT_CAPACITY_EXCEEDED",
                            taskOutcome = "BLOCKED",
                            failureDetails = buildJsonObject { put("logical_continuation_allowed", false) }
                        )
                    )
                }
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxRounds = 10, maxToolCalls = 10, maxReplayTokens = 4000, maxReplayResultTokens = 512, maxToolOutputBytes = 8192)
        ).run(provider, listOf(reader)).toList()
        assertEquals(3, localReads)
        assertEquals(2, providerRounds)
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { (it.event as? ProviderEvent.Failed)?.errorCode == "INPUT_CAPACITY_EXCEEDED" })
    }

    @Test fun omittedFullPageBlocksBeforeAnotherProviderOrAutomaticRead() = runBlocking {
        val fixtures = List(2) { GitHubSavedPageCapacityTest.Fixture("source-$it") }
        var localReads = 0
        var providerRounds = 0
        val reader = object : AgentTool {
            override val definition = fixtures.first().tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                localReads++
                val view = arguments.getValue("view_cursor").jsonPrimitive.content.substringBefore(':')
                return fixtures.single { it.cursor.substringBefore(':') == view }.read(ProviderEvent.ToolCall(callId, definition.name, arguments))
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                assertTrue(exchanges.isEmpty())
                fixtures.forEachIndexed { index, fixture -> emit(ProviderEvent.ToolCall("provider-page-$index", reader.definition.name, fixture.blocked.arguments)) }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxRounds = 10, maxToolCalls = 10, maxReplayTokens = 4000, maxReplayResultTokens = 512, maxToolOutputBytes = 8192, maxRetainedEvidenceTokens = 1024)
        ).run(provider, listOf(reader)).toList()
        assertEquals(3, localReads)
        assertEquals(1, providerRounds)
        val failure = events.filterIsInstance<AgentRunEvent.Provider>().mapNotNull { it.event as? ProviderEvent.Failed }.single()
        assertEquals("REPLAY_EVIDENCE_REQUIRED", failure.errorCode)
        assertEquals("BLOCKED", failure.taskOutcome)
    }

    @Test fun nativeCapacityRefusalResumesIssuedPageBeforeTheNextProviderRound() = runBlocking {
        val fixture = GitHubSavedPageCapacityTest.Fixture()
        var localReads = 0
        var providerRounds = 0
        var completePage: ToolResultContent? = null
        val reader = object : AgentTool {
            override val definition = fixture.tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                localReads++
                val result = fixture.read(ProviderEvent.ToolCall(callId, definition.name, arguments))
                if (!result.isError) completePage = result.content
                return result
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                if (exchanges.isEmpty()) {
                    emit(ProviderEvent.ToolCall("provider-page", reader.definition.name, fixture.blocked.arguments))
                } else {
                    val page = exchanges.flatMap { it.results }.single { !it.isError }
                    assertEquals(completePage, page.content)
                    assertTrue(page.callId.startsWith("saved-result-consume-"))
                    emit(ProviderEvent.TextDelta("The complete retained page was delivered."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(
                githubTask = true,
                maxRounds = 3,
                maxToolCalls = 3,
                maxReplayTokens = 4000,
                maxReplayResultTokens = 512,
                maxToolOutputBytes = 8192
            )
        ).run(provider, listOf(reader)).toList()
        assertEquals(2, localReads)
        assertEquals(2, providerRounds)
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
    }
}
