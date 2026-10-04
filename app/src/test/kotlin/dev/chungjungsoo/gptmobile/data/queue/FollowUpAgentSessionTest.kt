package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpAgentSessionTest {
    private fun prompt(text: String = "Also check dates") = PendingPrompt("one", 1, text, "{}", 1)
    private fun provider(block: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow(block)
    }

    @Test
    fun `worker starts during primary stream and accepted evidence is sent once`() = runTest {
        val pending = MutableStateFlow<List<PendingPrompt>>(emptyList())
        val started = CompletableDeferred<Unit>()
        var primaryRequests = 0
        var accepted = 0
        val handoffs = mutableListOf<String>()
        val session = FollowUpAgentSession(
            initial = provider {
                primaryRequests++
                emit(ProviderEvent.TextDelta("Original answer"))
                pending.value = listOf(prompt())
                started.await()
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = {
                started.complete(Unit)
                delay(10)
                "Verified date: 2026"
            },
            accept = {
                accepted++
                pending.value = emptyList()
                true
            },
            continuation = { evidence, draft ->
                handoffs += evidence
                assertEquals("Original answer", draft)
                provider {
                    emit(ProviderEvent.TextDelta("Added date"))
                    emit(ProviderEvent.Completed)
                }
            }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, primaryRequests)
        assertEquals(1, accepted)
        assertEquals(1, handoffs.size)
        assertTrue(handoffs.single().contains("Verified date: 2026"))
        assertEquals(1, events.count { it == ProviderEvent.Completed })
    }

    @Test
    fun `edited queued prompt cancels stale work and only new text is accepted`() = runTest {
        val pending = MutableStateFlow(listOf(prompt("Old text")))
        val started = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Unit>()
        var staleCanceled = false
        var acceptedText = ""
        val session = FollowUpAgentSession(
            initial = provider {
                started.await()
                pending.value = listOf(prompt("New text"))
                fresh.await()
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = {
                if (it.text == "Old text") {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        staleCanceled = true
                    }
                } else {
                    fresh.complete(Unit)
                    "Fresh evidence"
                }
            },
            accept = {
                acceptedText = it.text
                pending.value = emptyList()
                true
            },
            continuation = { _, _ -> provider { emit(ProviderEvent.Completed) } }
        )
        session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(staleCanceled)
        assertEquals("New text", acceptedText)
    }

    @Test
    fun `worker timeout preserves user addition without claiming research`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        var handoff = ""
        val session = FollowUpAgentSession(
            initial = provider {
                delay(1)
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = { awaitCancellation() },
            accept = {
                pending.value = emptyList()
                true
            },
            continuation = { evidence, _ ->
                handoff = evidence
                provider { emit(ProviderEvent.Completed) }
            },
            workerTimeoutMs = 10
        )
        session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(handoff.contains("Also check dates"))
        assertTrue(handoff.contains("no usable evidence"))
    }

    @Test
    fun `parent cancellation cancels child and never consumes draft`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val started = CompletableDeferred<Unit>()
        var childCanceled = false
        var accepted = false
        val session = FollowUpAgentSession(
            initial = provider { awaitCancellation() },
            pending = pending,
            eligible = { true },
            prepare = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    childCanceled = true
                }
            },
            accept = {
                accepted = true
                true
            },
            continuation = { _, _ -> provider { emit(ProviderEvent.Completed) } }
        )
        val job = async { session.streamRound(emptyList(), emptyList()).toList() }
        started.await()
        job.cancel()
        job.join()
        assertTrue(childCanceled)
        assertFalse(accepted)
        assertEquals(1, pending.value.size)
    }

    @Test
    fun `pending primary tool call executes before follow-up continuation`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val ready = CompletableDeferred<Unit>()
        var continuations = 0
        val session = FollowUpAgentSession(
            initial = provider {
                ready.await()
                emit(ProviderEvent.ToolCall("tool", "lookup", JsonObject(emptyMap())))
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = {
                ready.complete(Unit)
                "New evidence"
            },
            accept = {
                pending.value = emptyList()
                true
            },
            continuation = { _, _ ->
                provider {
                    continuations++
                    emit(ProviderEvent.Completed)
                }
            }
        )
        session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(0, continuations)
        session.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, continuations)
    }

    @Test
    fun `usage from continuation requests is additive rather than taking the maximum`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val ready = CompletableDeferred<Unit>()
        val session = FollowUpAgentSession(
            initial = provider {
                ready.await()
                emit(ProviderEvent.Usage(inputTokens = 10, outputTokens = 5))
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = {
                ready.complete(Unit)
                "Evidence"
            },
            accept = {
                pending.value = emptyList()
                true
            },
            continuation = { _, _ ->
                provider {
                    emit(ProviderEvent.Usage(inputTokens = 20, outputTokens = 6))
                    emit(ProviderEvent.Completed)
                }
            }
        )
        val usage = session.streamRound(emptyList(), emptyList()).toList().filterIsInstance<ProviderEvent.Usage>()
        assertEquals(30, usage.sumOf { it.inputTokens ?: 0 })
        assertEquals(11, usage.sumOf { it.outputTokens ?: 0 })
        assertTrue(usage.all { !it.cumulative })
    }

    @Test
    fun `failed compare and transfer leaves queued prompt untouched`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val ready = CompletableDeferred<Unit>()
        var continued = false
        val session = FollowUpAgentSession(
            initial = provider {
                ready.await()
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = {
                ready.complete(Unit)
                "Evidence"
            },
            accept = { false },
            continuation = { _, _ ->
                continued = true
                provider { emit(ProviderEvent.Completed) }
            }
        )
        session.streamRound(emptyList(), emptyList()).toList()
        assertFalse(continued)
        assertEquals(1, pending.value.size)
    }

    @Test
    fun `a canceled helper cannot cancel the primary answer`() = runTest {
        val pending = MutableStateFlow(listOf(prompt()))
        val started = CompletableDeferred<Unit>()
        var continued = false
        val session = FollowUpAgentSession(
            initial = provider {
                started.await()
                emit(ProviderEvent.Completed)
            },
            pending = pending,
            eligible = { true },
            prepare = {
                started.complete(Unit)
                throw CancellationException("Helper disconnected")
            },
            accept = {
                pending.value = emptyList()
                true
            },
            continuation = { _, _ ->
                continued = true
                provider { emit(ProviderEvent.Completed) }
            }
        )
        session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(continued)
    }
}
