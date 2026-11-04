package dev.chungjungsoo.gptmobile.data.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerFailureTest {
    @Test
    fun typedProviderLimitIsPreservedWithoutTheTransientOneSecondRetry() = runTest {
        for (knownNotAccepted in listOf(true, false)) {
            var requests = 0
            val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision("TEST", "p", "model", 1000L, "scope", "PROVIDER_RATE_LIMITED", knownNotAccepted)
            val session = provider {
                requests++
                emit(ProviderEvent.Failed("Service temporarily overloaded, please retry", providerLimit = limit))
            }
            val events = AgentRunner().run(session, emptyList()).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            assertEquals(1, requests)
            assertSame(limit, events.filterIsInstance<ProviderEvent.Failed>().single().providerLimit)
            assertFalse(events.any { it == ProviderEvent.Completed })
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun unknownGatewayOutcomeStopsBeforeAnotherModelRoundOrPendingToolDispatch() = runTest {
        val cases = listOf(
            ProviderEvent.Failed("Connection reset before the response", errorCode = "GATEWAY_OUTCOME_UNKNOWN"),
            ProviderEvent.Failed("The response reached its output limit", errorCode = "REMOTE_OUTCOME_UNKNOWN"),
            ProviderEvent.Failed("GATEWAY_OUTCOME_UNKNOWN: incomplete function call; connection reset")
        )
        for (githubTask in listOf(false, true)) {
            for (failure in cases) {
                var requests = 0
                var toolDispatches = 0
                var retainedRequests = 0
                val arguments = kotlinx.serialization.json.JsonObject(emptyMap())
                val tool = object : AgentTool {
                    override val definition = AgentToolDefinition("github_write", "Write an authorized change", arguments)
                    override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                        toolDispatches++
                        return AgentToolResult(callId, ToolResultContent.Text("write receipt"), false)
                    }
                }
                val session = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                        requests++
                        emit(ProviderEvent.ToolCall("pending", tool.definition.name, arguments))
                        emit(failure)
                    }

                    override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = flow {
                        retainedRequests++
                        emit(ProviderEvent.TextDelta("This fallback would issue another request."))
                        emit(failure)
                    }
                }
                val events = AgentRunner(AgentRunLimits(githubTask = githubTask)).run(session, listOf(tool)).toList()
                assertEquals(1, requests)
                assertEquals(0, toolDispatches)
                assertEquals(0, retainedRequests)
                assertFalse(events.any { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
                val published = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
                assertEquals("GATEWAY_OUTCOME_UNKNOWN", published.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
                assertFalse(published.any { it is ProviderEvent.Completed || it is ProviderEvent.ToolCall })
            }
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun thrownGatewayOutcomeUnknownKeepsItsIdentityAndNeverStartsARecoveryRequest() = runTest {
        for (githubTask in listOf(false, true)) {
            var requests = 0
            var retainedRequests = 0
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    requests++
                    throw IOException("wrapped transport failure", IOException("GATEWAY_OUTCOME_UNKNOWN: connection reset"))
                }

                override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = flow {
                    retainedRequests++
                    emit(failure)
                }
            }
            val events = AgentRunner(AgentRunLimits(githubTask = githubTask)).run(session, emptyList()).toList()
            assertEquals(1, requests)
            assertEquals(0, retainedRequests)
            val failures = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>()
            assertEquals("GATEWAY_OUTCOME_UNKNOWN", failures.single().errorCode)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun unknownGatewayOutcomeDominatesEarlierProtocolErrorsAndStopsTrailingEvents() = runTest {
        for (preamble in listOf(ProviderEvent.TextDelta("<tool_call name=github_write>{}</tool_call>"), ProviderEvent.Failed("Connection reset"))) {
            var requests = 0
            var trailingEvents = 0
            val session = provider {
                requests++
                emit(preamble)
                emit(ProviderEvent.Failed("Gateway result is unknown", errorCode = "GATEWAY_OUTCOME_UNKNOWN"))
                trailingEvents++
                emit(ProviderEvent.Failed("Connection reset again"))
            }
            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, emptyList()).toList()
            assertEquals(1, requests)
            assertEquals(0, trailingEvents)
            val failures = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>()
            assertEquals("GATEWAY_OUTCOME_UNKNOWN", failures.single().errorCode)
        }
    }

    private fun provider(block: suspend FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow(block)
    }

    @Test
    fun `downstream failure propagates without flushing usage or retrying`() = runTest {
        val expected = IOException("connection reset in collector")
        var requests = 0
        val events = mutableListOf<AgentRunEvent>()
        val session = provider {
            requests++
            emit(ProviderEvent.Usage(10, 2, 12))
            emit(ProviderEvent.TextDelta("Answer"))
            emit(ProviderEvent.Completed)
        }
        val failure = runCatching {
            AgentRunner().run(session, emptyList()).collect { event ->
                events += event
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, requests)
        assertEquals(listOf(AgentRunEvent.Provider(ProviderEvent.TextDelta("Answer"))), events)
    }

    @Test
    fun `provider cancellation propagates without a final usage emission`() = runTest {
        val expected = CancellationException("User stopped generation")
        val events = mutableListOf<AgentRunEvent>()
        val session = provider {
            emit(ProviderEvent.Usage(10, 2, 12))
            throw expected
        }
        val failure = runCatching { AgentRunner().run(session, emptyList()).toList(events) }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `upstream exception keeps usage before one bounded recovery`() = runTest {
        var requests = 0
        val session = provider {
            requests++
            emit(ProviderEvent.Usage(10, 2, 12))
            if (requests == 1) throw IOException("connection reset")
            emit(ProviderEvent.TextDelta("Recovered"))
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner().run(session, emptyList()).toList()
        val providerEvents = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val usage = providerEvents.filterIsInstance<ProviderEvent.Usage>()
        assertEquals(2, requests)
        assertEquals(2, usage.size)
        assertEquals(20, usage.sumOf { it.inputTokens ?: 0 })
        assertEquals(4, usage.sumOf { it.outputTokens ?: 0 })
        assertTrue(usage.all { !it.cumulative })
        assertEquals(1, providerEvents.count { it == ProviderEvent.Completed })
        assertFalse(providerEvents.any { it is ProviderEvent.Failed })
    }
}
