package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.modelText
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedPageAdmissionRunnerTest {
    @Test fun nativePageAllowanceIncludesTheRunnersFinalWrapUpInstruction() = runBlocking {
        val route = "github_context"
        val views = GitHubResultViews(pageBytes = 8192)
        val source = views.present(
            "scope",
            "github_context",
            route,
            AgentToolResult("source", ToolResultContent.Json(buildJsonObject { put("body", "v".repeat(6000)) }), false),
            buildJsonObject { put("action", "status") }
        )
        val issued = (source.content as ToolResultContent.Json).value.jsonObject.getValue("recovery_call").jsonObject
        val historyCall = ProviderEvent.ToolCall("provider-history", route, buildJsonObject { put("action", "status") })
        val pageCall = ProviderEvent.ToolCall("provider-small-page", route, issued.getValue("arguments").jsonObject)
        assertEquals(route, issued.getValue("tool").jsonPrimitive.content)
        assertFalse(pageCall.callId.startsWith("saved-result-consume-"))
        val cursor = requireNotNull(GitHubSavedReadProgress.cursor(pageCall.arguments))
        assertEquals(1536, cursor.bytes)
        val replayLimit = 4000
        val resultLimit = 512
        val outputLimit = 16_384
        val limits = AgentRunLimits(githubTask = true, maxRounds = 6, maxToolCalls = 3, maxReplayTokens = replayLimit, maxReplayResultTokens = resultLimit, maxToolOutputBytes = outputLimit)
        val budget = ToolExecutionBudget(limits)
        val issuedText = "Issued saved read: $issued"
        val seed = AgentToolResult(historyCall.callId, ToolResultContent.Text(issuedText), false, dispatched = false)
        val seedExchange = AgentToolExchange(listOf(historyCall), listOf(seed))
        val padding = (replayLimit - 256 - ToolExchangeCompactor.estimateTokens(listOf(seedExchange))) * 3
        assertTrue(padding > 0)
        val history = seed.copy(content = ToolResultContent.Text("s".repeat(padding) + issuedText))
        val historyExchange = AgentToolExchange(listOf(historyCall), listOf(history))
        assertTrue(ToolExchangeCompactor.estimateTokens(listOf(historyExchange)) <= replayLimit)
        var historyDelivered = false
        var providerRounds = 0
        var finalPage: AgentToolResult? = null
        var pageDelivered = false
        val executed = mutableListOf<Pair<String, JsonObject>>()
        val reader = object : AgentTool {
            override val definition = GitHubOperationTool.definition("github_context", route)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executed += callId to arguments
                if (callId == historyCall.callId) {
                    assertEquals(historyCall.arguments, arguments)
                    return history
                }
                assertEquals(pageCall.callId, callId)
                assertEquals(pageCall.arguments, arguments)
                assertTrue(historyDelivered)
                val native = views.read("scope", "github_context", route, cursor.value, callId)
                assertFalse(native.isError)
                assertFalse(native.dispatched)
                val nativeBody = (native.content as ToolResultContent.Json).value.jsonObject
                assertFalse("The native reader does not supply the runner's instruction", "agent_instruction" in nativeBody)
                val nativeBytes = ToolHandoffReceipt.payload(native.content).encodeToByteArray().size
                assertTrue(nativeBytes <= cursor.bytes)
                val wrapped = requireNotNull(SavedPageInstruction.append(native, ToolBudgetPolicy.buildWrapUpPrompt(1)))
                assertEquals("The persisted native page remains byte-for-byte stable", native.content, wrapped.content)
                val wrappedBytes = wrapped.modelText().encodeToByteArray().size
                assertTrue("The real wrap-up must outgrow the cursor-only allowance", wrappedBytes > cursor.bytes)
                assertTrue(wrappedBytes <= outputLimit)
                val wrappedTokens = (wrappedBytes + 2) / 3
                assertTrue(wrappedTokens > resultLimit && wrappedTokens <= replayLimit - 512)
                val finalExchange = AgentToolExchange(listOf(pageCall), listOf(wrapped))
                assertTrue(ToolExchangeCompactor.estimateTokens(listOf(finalExchange)) <= replayLimit)
                assertTrue("Retained history must force per-result compaction", ToolExchangeCompactor.estimateTokens(listOf(historyExchange, finalExchange)) > replayLimit)
                finalPage = wrapped
                return native
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                assertTrue(ToolExchangeCompactor.estimateTokens(exchanges) <= replayLimit)
                when (providerRounds) {
                    1 -> {
                        assertTrue(exchanges.isEmpty())
                        emit(historyCall)
                    }
                    2 -> {
                        val deliveredHistory = exchanges.flatMap { it.results }.single { it.callId == historyCall.callId }
                        assertEquals(history.content, deliveredHistory.content)
                        assertEquals(1, requireNotNull(deliveredHistory.toolCallBudgetUsed))
                        emit(pageCall)
                    }
                    3 -> {
                        val expected = requireNotNull(finalPage)
                        val delivered = exchanges.flatMap { it.results }.single { it.callId == pageCall.callId }
                        assertEquals("The immutable finishBatch body must arrive intact", expected.content, delivered.content)
                        assertEquals(expected.assistMetadata, delivered.assistMetadata)
                        assertEquals(ToolResultDeliveryProof.digest(expected.content), ToolResultDeliveryProof.digest(delivered.content))
                        assertEquals(2, requireNotNull(delivered.toolCallBudgetUsed))
                        val required = (ToolHandoffReceipt.payload(delivered.content).encodeToByteArray().size + 2) / 3
                        val finalExchange = AgentToolExchange(listOf(pageCall), listOf(delivered))
                        assertEquals(mapOf(pageCall.callId to required), GitHubReplayRecovery.savedPageResultAllowances(listOf(finalExchange), emptySet(), replayLimit, resultLimit))
                        assertTrue(GitHubReplayRecovery.savedPageResultAllowances(listOf(finalExchange), emptySet(), required + 511, resultLimit).isEmpty())
                        assertFalse(exchanges.flatMap { it.results }.any { it.callId == historyCall.callId && it.content == history.content })
                        pageDelivered = true
                        emit(ProviderEvent.TextDelta("The complete native page and final wrap-up were delivered."))
                    }
                    else -> error("Unexpected provider round")
                }
                emit(ProviderEvent.Completed)
                if (providerRounds == 2) historyDelivered = true
            }
        }
        val events = AgentRunner(limits).run(provider, listOf(budget.bind(reader))).toList()
        assertEquals(listOf(historyCall.callId to historyCall.arguments, pageCall.callId to pageCall.arguments), executed)
        assertEquals(2, budget.usedCalls())
        assertEquals(3, providerRounds)
        assertTrue(pageDelivered)
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
    }

    @Test fun ordinaryProviderPageKeepsItsFullBodyAfterEarlierDeliveredHistoryExceedsReplay() = runBlocking {
        val fixture = GitHubSavedPageCapacityTest.Fixture()
        val replayLimit = 4000
        val resultLimit = 512
        val outputLimit = 16_384
        val issued = fixture.body.getValue("recovery_call").jsonObject
        assertEquals(fixture.route, issued.getValue("tool").jsonPrimitive.content)
        val historyCall = ProviderEvent.ToolCall("provider-history", fixture.route, buildJsonObject { put("action", "status") })
        val pageCall = ProviderEvent.ToolCall("provider-issued-page", fixture.route, issued.getValue("arguments").jsonObject)
        assertFalse(pageCall.callId.startsWith("saved-result-consume-"))
        val cursor = requireNotNull(GitHubSavedReadProgress.cursor(pageCall.arguments))
        assertTrue((cursor.bytes + 2) / 3 <= replayLimit - 512)
        val issuedText = "Issued saved read: $issued"
        val historySeed = AgentToolResult(historyCall.callId, ToolResultContent.Text(issuedText), false, dispatched = false)
        val historySeedExchange = AgentToolExchange(listOf(historyCall), listOf(historySeed))
        val paddingBytes = (replayLimit - 512 - ToolExchangeCompactor.estimateTokens(listOf(historySeedExchange))) * 3
        assertTrue(paddingBytes > 0)
        // Size only the synthetic history; the page and its admission receipt come
        // from the actual native saved-view reader.
        val historyContent = ToolResultContent.Text("s".repeat(paddingBytes) + issuedText)
        val historyResult = historySeed.copy(content = historyContent)
        val historyExchange = AgentToolExchange(listOf(historyCall), listOf(historyResult))
        assertTrue(historyContent.text.encodeToByteArray().size < outputLimit)
        assertTrue(ToolExchangeCompactor.estimateTokens(listOf(historyExchange)) <= replayLimit)
        var historyDelivered = false
        var pageDelivered = false
        var providerRounds = 0
        var completePage: ToolResultContent? = null
        val executed = mutableListOf<Pair<String, JsonObject>>()
        val reader = object : AgentTool {
            override val definition = fixture.tools.single()
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executed += callId to arguments
                if (callId == historyCall.callId) {
                    assertEquals(historyCall.arguments, arguments)
                    return historyResult
                }
                assertEquals(pageCall.callId, callId)
                assertEquals(pageCall.arguments, arguments)
                assertTrue("The earlier full result must already have reached a successful provider round", historyDelivered)
                val result = fixture.read(ProviderEvent.ToolCall(callId, definition.name, arguments))
                assertFalse(result.isError)
                assertFalse(result.dispatched)
                val bodyBytes = (result.content as ToolResultContent.Json).value.toString().encodeToByteArray().size
                assertTrue("The exact page must need more than the normal per-result allowance", bodyBytes > resultLimit * 3)
                assertTrue(bodyBytes <= cursor.bytes && bodyBytes <= outputLimit)
                val pageExchange = AgentToolExchange(listOf(pageCall), listOf(result))
                assertTrue("The full new page fits this unchanged total replay allowance", ToolExchangeCompactor.estimateTokens(listOf(pageExchange)) <= replayLimit)
                assertTrue("Raw retained history must force the compaction path", ToolExchangeCompactor.estimateTokens(listOf(historyExchange, pageExchange)) > replayLimit)
                completePage = result.content
                return result
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                providerRounds++
                assertTrue(ToolExchangeCompactor.estimateTokens(exchanges) <= replayLimit)
                when (providerRounds) {
                    1 -> {
                        assertTrue(exchanges.isEmpty())
                        emit(historyCall)
                    }
                    2 -> {
                        assertEquals(historyContent, exchanges.flatMap { it.results }.single { it.callId == historyCall.callId }.content)
                        emit(pageCall)
                    }
                    3 -> {
                        val results = exchanges.flatMap { it.results }
                        assertEquals(requireNotNull(completePage), results.single { it.callId == pageCall.callId }.content)
                        assertFalse("Already-delivered history must be compacted or evicted", results.any { it.callId == historyCall.callId && it.content == historyContent })
                        pageDelivered = true
                        emit(ProviderEvent.TextDelta("The ordinary requested page was delivered in full."))
                    }
                    else -> error("Unexpected provider round")
                }
                emit(ProviderEvent.Completed)
                if (providerRounds == 2) historyDelivered = true
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxRounds = 6, maxToolCalls = 6, maxReplayTokens = replayLimit, maxReplayResultTokens = resultLimit, maxToolOutputBytes = outputLimit)
        ).run(provider, listOf(reader)).toList()
        assertEquals(listOf(historyCall.callId to historyCall.arguments, pageCall.callId to pageCall.arguments), executed)
        assertEquals(3, providerRounds)
        assertTrue(pageDelivered)
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
    }

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
