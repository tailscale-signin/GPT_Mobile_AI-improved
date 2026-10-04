package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FollowUpAgentSessionFailureTest {
    private fun provider(block: suspend FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow(block)
    }

    private fun TestScope.emptyInbox() = FollowUpInbox(
        backgroundScope,
        MutableStateFlow<List<PendingPrompt>>(emptyList()),
        { true },
        { error("No prompt should be admitted") },
        nowMs = { testScheduler.currentTime }
    )

    private fun TestScope.pendingInbox(): FollowUpInbox {
        val pending = MutableStateFlow(listOf(PendingPrompt("one", 1, "Also check dates", "{}", 1)))
        return FollowUpInbox(backgroundScope, pending, { true }, {
            pending.value = emptyList()
            true
        }, nowMs = { testScheduler.currentTime })
    }

    @Test
    fun `provider exception preserves observed usage and original failure`() = runTest {
        val inbox = emptyInbox()
        val expected = IOException("connection reset")
        val session = FollowUpAgentSession(
            provider {
                emit(ProviderEvent.Usage(100, 10, 110))
                emit(ProviderEvent.Usage(130, 20, 150))
                emit(ProviderEvent.Usage(7, 3, 10, cumulative = false, decodeTokensPerSecond = 12.5))
                throw expected
            },
            inbox
        ) { _, _, _ -> error("No continuation expected") }
        val events = mutableListOf<ProviderEvent>()
        val failure = runCatching { session.streamRound(emptyList(), emptyList()).toList(events) }.exceptionOrNull()
        assertSame(expected, failure)
        val usage = events.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(137, usage.inputTokens)
        assertEquals(23, usage.outputTokens)
        assertEquals(160, usage.totalTokens)
        assertEquals(12.5, usage.decodeTokensPerSecond!!, 0.0)
        assertFalse(usage.cumulative)
        assertFalse(events.contains(ProviderEvent.Completed))
        inbox.close()
    }

    @Test
    fun `failure ignores later content and tools but retains trailing usage`() = runTest {
        val inbox = emptyInbox()
        val session = FollowUpAgentSession(
            provider {
                emit(ProviderEvent.Usage(10, 2, 12))
                emit(ProviderEvent.Failed("connection reset"))
                emit(ProviderEvent.TextDelta("Do not display this text"))
                emit(ProviderEvent.ToolCall("late", "write", JsonObject(emptyMap())))
                emit(ProviderEvent.Completed)
                emit(ProviderEvent.Usage(15, 3, 18))
            },
            inbox
        ) { _, _, _ -> error("No continuation expected") }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertTrue(events.filterIsInstance<ProviderEvent.ToolCall>().isEmpty())
        assertFalse(events.contains(ProviderEvent.Completed))
        val usage = events.filterIsInstance<ProviderEvent.Usage>().single()
        assertEquals(15, usage.inputTokens)
        assertEquals(3, usage.outputTokens)
        assertEquals(18, usage.totalTokens)
        inbox.close()
    }

    @Test
    fun `downstream failure is not masked by a usage emission`() = runTest {
        val inbox = emptyInbox()
        val expected = IOException("collector stopped")
        val session = FollowUpAgentSession(
            provider {
                emit(ProviderEvent.Usage(5, 1, 6))
                emit(ProviderEvent.TextDelta("Answer"))
                emit(ProviderEvent.Completed)
            },
            inbox
        ) { _, _, _ -> error("No continuation expected") }
        val events = mutableListOf<ProviderEvent>()
        val failure = runCatching {
            session.streamRound(emptyList(), emptyList()).collect { event ->
                events += event
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(listOf(ProviderEvent.TextDelta("Answer")), events)
        inbox.close()
    }

    @Test
    fun `cancellation is propagated without usage or continuation emissions`() = runTest {
        val inbox = emptyInbox()
        val expected = CancellationException("User stopped generation")
        val session = FollowUpAgentSession(
            provider {
                emit(ProviderEvent.Usage(5, 1, 6))
                throw expected
            },
            inbox
        ) { _, _, _ -> error("No continuation expected") }
        val events = mutableListOf<ProviderEvent>()
        val failure = runCatching { session.streamRound(emptyList(), emptyList()).toList(events) }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(events.isEmpty())
        inbox.close()
    }

    @Test
    fun `silent follow-up handoff does not invent an answer delta`() = runTest {
        val inbox = pendingInbox()
        var handoffs = 0
        val session = FollowUpAgentSession(provider { emit(ProviderEvent.Completed) }, inbox) { _, _, _ ->
            handoffs++
            provider { emit(ProviderEvent.Completed) }
        }
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, handoffs)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertEquals(1, events.count { it == ProviderEvent.Completed })
        inbox.close()
    }

    @Test
    fun `continuation failure retains usage from both requests exactly once`() = runTest {
        val inbox = pendingInbox()
        val expected = IOException("connection reset")
        var handoffs = 0
        val session = FollowUpAgentSession(
            provider {
                emit(ProviderEvent.Usage(130, 20, 150))
                emit(ProviderEvent.Completed)
            },
            inbox
        ) { _, _, _ ->
            handoffs++
            provider {
                emit(ProviderEvent.Usage(30, 5, 35))
                throw expected
            }
        }
        val events = mutableListOf<ProviderEvent>()
        val failure = runCatching { session.streamRound(emptyList(), emptyList()).toList(events) }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, handoffs)
        val usage = events.filterIsInstance<ProviderEvent.Usage>()
        assertEquals(2, usage.size)
        assertEquals(160, usage.sumOf { it.inputTokens ?: 0 })
        assertEquals(25, usage.sumOf { it.outputTokens ?: 0 })
        assertEquals(185, usage.sumOf { it.totalTokens ?: 0 })
        assertTrue(usage.all { !it.cumulative })
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isEmpty())
        assertFalse(events.contains(ProviderEvent.Completed))
        inbox.close()
    }
}
