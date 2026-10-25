package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerEmptyAnswerTest {
    @Test
    fun atomicCombinedAnswerCompletesExactlyOneRoundWithOrWithoutProvisionalText() = runBlocking {
        val text = "The complete answer retains the saved contributions."
        val report = CombinedQualityReport(
            version = 6,
            answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text),
            revisionId = "atomic-final",
            complete = true,
            status = "COMPLETED_UNVERIFIED",
            deliveredScopeStatus = "COMPLETED",
            protocolComplete = true,
            finalAssessment = true,
            assessmentStage = "FINAL_DISPLAY",
            wordCount = visibleWordCount(text),
            displayedCharacters = text.length
        )
        for (singlePass in listOf(false, true)) {
            for (progressive in listOf(false, true)) {
                var rounds = 0
                val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(), listOf("source"), mapOf("source" to "model"), "Summarize the contributions", singlePass = singlePass)
                val execution = CombinedExecutionContext(requested, rootBudgetId = "atomic-$singlePass-$progressive")
                val session = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        check(++rounds == 1) { "A delivered final bundle must never trigger empty-answer recovery." }
                        if (progressive) emit(ProviderEvent.TextDelta(text.take(12)))
                        emit(ProviderEvent.CombinedAnswer(text, report))
                        emit(ProviderEvent.Completed)
                    }
                }
                val events = AgentRunner(AgentRunLimits(combined = execution)).run(session, emptyList()).toList()
                val provider = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
                assertEquals(1, rounds)
                assertEquals(listOf(ProviderEvent.CombinedAnswer(text, report)), provider.filterIsInstance<ProviderEvent.CombinedAnswer>())
                assertEquals(ProviderEvent.Completed, provider.last())
                assertFalse(provider.any { it is ProviderEvent.Failed })
                assertFalse(events.any { it is AgentRunEvent.Notice && it.message.contains("no visible answer") })
            }
        }
    }

    @Test
    fun partialCombinedBundleKeepsItsTerminalFailureWithoutAnEmptyAnswerRetry() = runBlocking {
        var rounds = 0
        val text = "The retained answer has an unfinished requirement."
        val failure = ProviderEvent.Failed("COMBINED_REQUIREMENTS_UNMET", transportComplete = true)
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                check(++rounds == 1) { "The partial bundle must retain its reported outcome." }
                emit(ProviderEvent.CombinedAnswer(text, CombinedQualityReport(answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text), revisionId = "partial")))
                emit(failure)
            }
        }
        val events = AgentRunner().run(session, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(1, rounds)
        assertEquals(failure, events.last())
        assertEquals(1, events.filterIsInstance<ProviderEvent.CombinedAnswer>().size)
        assertFalse(ProviderEvent.Completed in events)
    }

    @Test
    fun `empty completion finalizes once without repeating executed actions`() = runBlocking {
        var round = 0
        var executions = 0
        val sizes = mutableListOf<Int>()
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                sizes += tools.size
                when (round++) {
                    0 -> emit(ProviderEvent.ToolCall("action", "test", buildJsonObject {}))
                    1 -> emit(ProviderEvent.TextDelta("   "))
                    else -> {
                        assertEquals("action", exchanges.single().results.single().callId)
                        emit(ProviderEvent.TextDelta("Done."))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("test", "test", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("done"), false)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits()).run(session, listOf(tool)).collect { events += it }
        assertEquals(1, executions)
        assertEquals(listOf(1, 1, 0), sizes)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test
    fun `repeated empty completion fails terminally instead of looping or reporting success`() = runBlocking {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.Completed)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits()).run(session, emptyList()).collect { events += it }
        assertEquals(2, rounds)
        assertEquals(1, events.count { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertTrue(events.none { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
    }
}
