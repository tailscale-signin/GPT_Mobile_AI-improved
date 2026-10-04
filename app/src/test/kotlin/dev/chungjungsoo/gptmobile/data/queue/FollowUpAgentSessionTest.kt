package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FollowUpAgentSessionTest {
    private fun prompt(text: String = "Also check dates", id: String = "one") = PendingPrompt(id, 1, text, "{}", 1)
    private fun provider(block: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow(block)
    }

    @Test
    fun `admission does not wait for research or a model response`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        var acceptedAt = -1L
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, {
            acceptedAt = testScheduler.currentTime
            pending.value = emptyList()
            true
        }, nowMs = { testScheduler.currentTime })
        runCurrent()
        advanceTimeBy(2999)
        assertEquals(-1L, acceptedAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3000L, acceptedAt)
        assertEquals(listOf(prompt()), inbox.snapshot())
        inbox.close()
    }

    @Test
    fun `edit uses latest saved text without extending the deadline`() = runTest {
        val pending = MutableStateFlow(listOf(prompt("Old")))
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, {
            assertEquals("New", it.text)
            pending.value = emptyList()
            true
        }, nowMs = { testScheduler.currentTime })
        runCurrent()
        advanceTimeBy(2000)
        pending.value = listOf(prompt("New"))
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("New", inbox.snapshot().single().text)
        assertEquals(3000L, testScheduler.currentTime)
        inbox.close()
    }

    @Test
    fun `stop before deadline never accepts the message`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        var calls = 0
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, {
            calls++
            true
        }, nowMs = { testScheduler.currentTime })
        runCurrent()
        advanceTimeBy(2999)
        pending.value = emptyList()
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(inbox.snapshot().isEmpty())
        inbox.close()
    }

    @Test
    fun `multiple additions keep their own deadlines and FIFO order`() = runTest {
        val first = prompt("First")
        val second = prompt("Second", "two")
        val pending = MutableStateFlow(listOf(first))
        val times = mutableListOf<Long>()
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, { accepted ->
            times += testScheduler.currentTime
            pending.value = pending.value.filterNot { it.id == accepted.id }
            true
        }, nowMs = { testScheduler.currentTime })
        runCurrent()
        advanceTimeBy(500)
        pending.value += second
        runCurrent()
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(listOf(3000L, 3500L), times)
        assertEquals(listOf(first, second), inbox.snapshot())
        inbox.close()
    }

    @Test
    fun `primary finishing during countdown still applies addition once`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, {
            pending.value = emptyList()
            true
        }, nowMs = { testScheduler.currentTime })
        var handoffs = 0
        val session = FollowUpAgentSession(
            provider {
                delay(100)
                emit(ProviderEvent.TextDelta("Original answer"))
                emit(ProviderEvent.Completed)
            },
            inbox
        ) { addition, draft, _ ->
            handoffs++
            assertTrue(addition.contains("Also check dates"))
            assertEquals("Original answer", draft)
            provider {
                emit(ProviderEvent.TextDelta("Added date"))
                emit(ProviderEvent.Completed)
            }
        }
        val result = async { session.streamRound(emptyList(), emptyList()).toList() }
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(1, handoffs)
        assertEquals(1, result.await().count { it == ProviderEvent.Completed })
        inbox.close()
    }

    @Test
    fun `tool results are handed over after execution with no orphan replay`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, {
            pending.value = emptyList()
            true
        }, nowMs = { testScheduler.currentTime })
        val call = ProviderEvent.ToolCall("call", "read_url", JsonObject(emptyMap()))
        var handoffs = 0
        val session = FollowUpAgentSession(
            provider {
                delay(3100)
                emit(call)
                emit(ProviderEvent.Completed)
            },
            inbox
        ) { _, _, exchanges ->
            handoffs++
            assertEquals(call, exchanges.single().calls.single())
            assertEquals("Verified source", (exchanges.single().results.single().content as ToolResultContent.Text).text)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertTrue(exchanges.isEmpty())
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val first = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(first.contains(call))
        assertEquals(0, handoffs)
        val exchange = AgentToolExchange(listOf(call), listOf(AgentToolResult("call", ToolResultContent.Text("Verified source"), false)))
        session.streamRound(emptyList(), listOf(exchange)).toList()
        assertEquals(1, handoffs)
        inbox.close()
    }

    @Test
    fun `continuation preserves long original question history and prepared evidence`() {
        val original = "original requirement ".repeat(1000) + "MANDATORY FINAL REQUIREMENT\nPrepared evidence: [S1]"
        val history = ConversationTurn(MessageV2(chatId = 1, content = "Earlier context", platformType = null), null, false)
        val current = ConversationTurn(MessageV2(chatId = 1, content = original, platformType = null), null, true)
        val turns = appendFollowUpContext(listOf(history, current), "\nAlso include dates", "Answer already streamed")
        assertEquals(history, turns.first())
        assertTrue(turns.last().userMessage.content.startsWith(original))
        assertTrue(turns.last().userMessage.content.contains("Also include dates"))
        assertTrue(turns.last().userMessage.content.contains("ALL original requirements"))
        assertEquals(current.userMessage.id, turns.last().userMessage.id)
    }

    @Test
    fun `failed ownership transfer leaves prompt queued`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val inbox = FollowUpInbox(backgroundScope, pending, { true }, { false }, nowMs = { testScheduler.currentTime })
        advanceTimeBy(3000)
        runCurrent()
        assertTrue(inbox.snapshot().isEmpty())
        assertFalse(pending.value.isEmpty())
        inbox.close()
    }
}
