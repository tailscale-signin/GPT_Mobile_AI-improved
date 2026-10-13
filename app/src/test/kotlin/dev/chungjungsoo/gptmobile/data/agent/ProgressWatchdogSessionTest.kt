package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProgressWatchdogSessionTest {
    @Test
    fun stalledRoundKeepsPartialTextAndStopsDespiteHeartbeats() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                emit(ProviderEvent.TextDelta("partial evidence"))
                repeat(50) {
                    delay(100)
                    emit(ProviderEvent.GatewayProgressUpdate(GatewayProgress(event = "heartbeat", timestamp = it.toDouble())))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = session.withProgressWatchdog(1000, 1000, 5000) { testScheduler.currentTime }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.TextDelta("partial evidence"), events.first())
        assertTrue(events.last() is ProviderEvent.Failed)
        assertTrue(testScheduler.currentTime <= 2000)
        assertTrue(events.none { it == ProviderEvent.Completed })
    }

    @Test
    fun productiveRoundCompletesWithoutFalseTimeout() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                repeat(6) {
                    delay(400)
                    emit(ProviderEvent.TextDelta("part $it"))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = session.withProgressWatchdog(1000, 1000, 5000) { testScheduler.currentTime }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, events.last())
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test
    fun lc09ActiveReasoningCanExceedOldRoundCutoff() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                repeat(5) {
                    delay(50_000)
                    emit(ProviderEvent.ThinkingDelta("working $it"))
                }
                emit(ProviderEvent.TextDelta("visible answer"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = session.withProgressWatchdog(nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals(250_000L, testScheduler.currentTime)
        assertEquals(ProviderEvent.Completed, events.last())
        assertTrue(events.none { it is ProviderEvent.Failed })
    }

    @Test
    fun lc11ReasoningThenStallIsNotContextOverflow() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                emit(ProviderEvent.ThinkingDelta("reasoning"))
                delay(200_000)
            }
        }
        val events = session.withProgressWatchdog(nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().single().message.startsWith("GENERATION_STALLED"))
    }

    @Test
    fun lc14NonCooperativeTransportCannotWithholdTerminalOutcome() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                try {
                    delay(100_000)
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(30_000) }
                }
            }
        }
        val events = session.withProgressWatchdog(1000, 1000, 5000) { testScheduler.currentTime }.streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(testScheduler.currentTime <= 6000)
        testScheduler.advanceUntilIdle()
    }
}
