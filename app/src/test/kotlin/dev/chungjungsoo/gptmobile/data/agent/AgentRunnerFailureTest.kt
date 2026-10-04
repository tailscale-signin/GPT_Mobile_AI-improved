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
