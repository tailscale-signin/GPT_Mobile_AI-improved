package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubVerifiedSessionTest {
    private fun answer(text: String) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)
    }

    @Test fun statusPublishesCurrentFactsAfterOneReadWithoutAnotherModelOrVerifierRound() = runTest {
        var rounds = 0
        var executions = 0
        var publicationChecks = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_context", "Status", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Json(Json.parseToJsonElement("""{"schema":"github.operation.v1","data":{"repository":{"full_name":"o/r","default_branch":"main"},"open_pull_request_count":0,"latest_workflow":{"name":"Build","status":"completed","conclusion":"success"}}}""")), false)
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds == 1) { "Status must not drift into source inspection" }
                emit(ProviderEvent.ToolCall("status", "github_context", Json.parseToJsonElement("""{"action":"status"}""") as JsonObject))
                emit(ProviderEvent.Completed)
            }
        }
        val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", setOf("github_context"), { 1000 }), "status", { 8192 }, { error("Receipt-based status needs no model formatter") }, { publicationChecks++ }, {}, { 1000 }, StandardTestDispatcher(testScheduler))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(read)).toList()
        val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val text = published.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertEquals(1, rounds)
        assertEquals(1, executions)
        assertEquals(2, publicationChecks)
        assertTrue(text.startsWith("# Repository status"))
        assertTrue(text.contains("| Open pull requests | 0 |"))
        assertTrue(text.contains("| Workflow result | success |"))
        assertFalse(text.contains("/data/") || text.contains("ev_") || text.contains("clean state"))
        assertEquals(ProviderEvent.Completed, published.last())
    }

    @Test fun runnerRetriesTransientFailuresBeforePublishingWithoutRepeatingCompletedTools() = runTest {
        for (persistent in listOf(false, true)) {
            var rounds = 0
            var dispatches = 0
            var extractions = 0
            val read = object : AgentTool {
                override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatches++
                    return AgentToolResult(callId, ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(3)))), false)
                }
            }
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    rounds++
                    when {
                        rounds == 1 -> {
                            emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
                            emit(ProviderEvent.Completed)
                        }
                        persistent || rounds == 2 -> emit(ProviderEvent.Failed("Connection reset"))
                        else -> {
                            assertTrue(exchanges.isNotEmpty())
                            emit(ProviderEvent.TextDelta("private candidate"))
                            emit(ProviderEvent.Completed)
                        }
                    }
                }
            }
            val gate = GitHubVerifiedSession(provider, GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 }), "fix", { 8192 }, {
                extractions++
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {}, { 1000 }, StandardTestDispatcher(testScheduler))
            val events = AgentRunner(AgentRunLimits(githubTask = true, maxRounds = 2)).run(gate, listOf(read)).toList()
            assertEquals(1, dispatches)
            assertEquals(if (persistent) GitHubTaskRecovery.MAX_RECOVERIES + 2 else 3, rounds)
            assertEquals(if (persistent) 0 else 1, extractions)
            val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            assertTrue(published.last() is ProviderEvent.Completed)
            assertFalse(published.any { it is ProviderEvent.Failed })
            val text = published.filterIsInstance<ProviderEvent.TextDelta>().single().text
            assertEquals(persistent, text.contains("Partial evidence summary"))
            assertFalse(text.contains("private candidate"))
        }
    }

    @Test fun exhaustedToolFrameRepairPublishesRetainedFactsWithoutRepeatingTheRead() = runTest {
        var rounds = 0
        var dispatches = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(3)))), false)
            }
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (++rounds == 1) {
                    emit(ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap())))
                    emit(ProviderEvent.Completed)
                } else {
                    emit(ProviderEvent.Failed("Unparsed tool-call markup", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
                }
            }
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 }), "inspect", { 8192 }, { error("No formatter after exhausted repair") }, {}, {}, { 1000 }, StandardTestDispatcher(testScheduler))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(read)).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(4, rounds)
        assertEquals(1, dispatches)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("| Count | 3 |"))
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Partial evidence summary"))
        assertEquals(ProviderEvent.Completed, events.last())
        assertFalse(events.any { it is ProviderEvent.Failed })
    }

    @Test fun terminalFallbackRechecksPermissionAndCannotPublishAfterRevocation() = runTest {
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.Failed("403 forbidden"))
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("no formatter after failure") }, { error("access revoked") }, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
    }

    @Test fun unfinishedToolCallCannotBecomePartialCompletionOrDispatch() = runTest {
        var dispatches = 0
        val write = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Write", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("unexpected"), false)
            }
        }
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.ToolCall("pending", "github_write", JsonObject(emptyMap())),
                ProviderEvent.Failed("403 forbidden")
            )
        }
        val gate = GitHubVerifiedSession(source, GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { error("must not extract") }, { error("must not finalize pending calls") }, {})
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(gate, listOf(write)).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(0, dispatches)
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.any { it is ProviderEvent.TextDelta || it is ProviderEvent.Completed })
    }

    @Test fun oversizedTaskOrDraftUsesRetainedEvidenceWithoutOpeningFormatter() = runBlocking {
        for (largeTask in listOf(false, true)) {
            val gate = GitHubVerifiedSession(answer(if (largeTask) "private" else "private".repeat(1000)), GitHubEvidenceBook("b", "o/r", "main", emptySet()), if (largeTask) "task".repeat(1000) else "task", { 512 }, { error("oversized request must not be dispatched") }, {}, {})
            val events = gate.streamRound(emptyList(), emptyList()).toList()
            assertTrue(events.last() is ProviderEvent.Completed)
            assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("Partial evidence summary"))
        }
    }

    @Test fun newestReceiptPrefixFitsBudgetAndShrinkingCapacityPreventsDispatch() = runBlocking {
        for (shrink in listOf(false, true)) {
            var limits = 0
            var extractions = 0
            val book = GitHubEvidenceBook("b", "o/r", "main", setOf("github_read"), { 1000 })
            repeat(20) { index ->
                book.observe(ProviderEvent.ToolCall("r$index", "github_read", JsonObject(emptyMap())), AgentToolResult("r$index", ToolResultContent.Json(JsonObject(mapOf("count" to JsonPrimitive(index)))), false))
            }
            val gate = GitHubVerifiedSession(answer("private"), book, "task", { if (shrink && ++limits > 1) 1 else 700 }, { prompt ->
                extractions++
                assertTrue(TextTokenEstimate.estimate(prompt) <= 700)
                val catalog = Json.parseToJsonElement(prompt).jsonObject.getValue("catalog").jsonArray
                assertTrue(catalog.size in 1 until 20)
                assertEquals(JsonPrimitive(19), catalog.first().jsonObject.getValue("facts").jsonObject["/count"])
                answer("""{"claims":[{"kind":"not_measured","subject":"Feature acceptance"}]}""")
            }, {}, {}, { 1000 })
            assertTrue(gate.streamRound(emptyList(), emptyList()).toList().last() is ProviderEvent.Completed)
            assertEquals(if (shrink) 0 else 1, extractions)
        }
    }

    @Test fun downstreamCollectorFailurePropagatesWithoutAnAlternativePublication() = runBlocking {
        val gate = GitHubVerifiedSession(answer("private"), GitHubEvidenceBook("b", "o/r", "main", emptySet()), "fix", { 8192 }, { answer("invalid JSON") }, {}, {})
        val failure = IllegalStateException("collector stopped")
        val result = runCatching { gate.streamRound(emptyList(), emptyList()).collect { throw failure } }
        assertSame(failure, result.exceptionOrNull())
    }
}
