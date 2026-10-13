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
    fun latePrefillLabelDoesNotRestartTheFirstProgressDeadline() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                delay(900)
                emit(ProviderEvent.PhaseChanged(dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase.PREFILL))
                delay(5000)
            }
        }
        val events = session.withProgressWatchdog(1000, 1000, nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals("MODEL_PROGRESS_TIMEOUT", events.filterIsInstance<ProviderEvent.Failed>().single().progressDeadline?.deadline)
        assertEquals(1000L, testScheduler.currentTime)
    }

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

    @Test
    fun advancingCountersKeepPrefillAliveButRepeatedCountersDoNot() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                emit(ProviderEvent.PhaseChanged(dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase.PREFILL))
                repeat(4) {
                    delay(400)
                    emit(ProviderEvent.Usage(inputTokens = (it + 1) * 100))
                }
                repeat(20) {
                    delay(200)
                    emit(ProviderEvent.Usage(inputTokens = 400))
                }
            }
        }
        val events = session.withProgressWatchdog(1000, 1000, nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        val deadline = requireNotNull(events.filterIsInstance<ProviderEvent.Failed>().single().progressDeadline)
        assertEquals(ObservedProgressStage.PREFILL, deadline.stage)
        assertEquals("GENERATION_STALLED", deadline.deadline)
        assertEquals(false, deadline.visibleOutputObserved)
        assertTrue(deadline.elapsedMillis in 2600..3000)
    }

    @Test
    fun repeatedStageLabelsAndAlternatingOldCheckpointsCannotKeepAStallAlive() = runTest {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                repeat(30) {
                    delay(100)
                    emit(ProviderEvent.GatewayProgressUpdate(GatewayProgress(event = "checkpoint", checkpoint = if (it % 2 == 0) 2 else 1, phase = "prefill")))
                }
            }
        }
        val events = session.withProgressWatchdog(1000, 1000, nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
        assertTrue(testScheduler.currentTime <= 2000)
    }

    @Test
    fun queueDeadlineIsSeparateAndAnExplicitTotalDeadlineStillBoundsProductiveReasoning() = runTest {
        fun session(queued: Boolean) = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                repeat(30) {
                    emit(if (queued) ProviderEvent.GatewayProgressUpdate(GatewayProgress(event = "heartbeat", phase = "queued")) else ProviderEvent.ThinkingDelta("work"))
                    delay(100)
                }
            }
        }
        val queued = session(true).withProgressWatchdog(settings = ProgressDeadlineSettings(queueMillis = 500), nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals("QUEUE_DEADLINE_EXCEEDED", queued.filterIsInstance<ProviderEvent.Failed>().single().progressDeadline?.deadline)
        val active = session(false).withProgressWatchdog(settings = ProgressDeadlineSettings(roundDeadlineMillis = 500), nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals("RUN_DEADLINE_EXCEEDED", active.filterIsInstance<ProviderEvent.Failed>().single().progressDeadline?.deadline)
    }

    @Test
    fun adaptiveObservationsAreScopedBoundedAndNeverOverrideUserValues() {
        val history = AdaptiveProgressHistory()
        repeat(3) { history.record("runtime-a-size-1", 150_000) }
        assertEquals(300_000L, history.firstProgressMillis("runtime-a-size-1", 180_000, ProgressDeadlineSettings()))
        assertEquals(180_000L, history.firstProgressMillis("runtime-b-size-1", 180_000, ProgressDeadlineSettings()))
        assertEquals(20_000L, history.firstProgressMillis("runtime-a-size-1", 180_000, ProgressDeadlineSettings(firstProgressMillis = 20_000)))
        repeat(3) { history.record("slow", Long.MAX_VALUE) }
        assertEquals(900_000L, history.firstProgressMillis("slow", 180_000, ProgressDeadlineSettings()))
    }
}
