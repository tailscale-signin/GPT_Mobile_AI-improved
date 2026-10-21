package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ObservedProgressStage
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.withProgressWatchdog
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.util.ApiStateFlowOutcome
import dev.chungjungsoo.gptmobile.util.collectApiStateUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeProgressPipelineTest {
    @Test fun counterWatermarksRejectUnknownRepeatedAndRegressingReportsAndSeparateOperations() {
        val tracker = NativeProgressTracker()
        assertFalse(tracker.advance(null))
        assertFalse(tracker.advance(progress(operation = 0, processed = 100)))
        assertFalse(tracker.advance(progress(processed = -1, sampled = -1)))
        assertTrue(tracker.advance(progress(processed = 64)))
        assertFalse(tracker.advance(progress(processed = 64).copy(elapsedMs = 9000, loadFraction = 1.0)))
        assertFalse(tracker.advance(progress(processed = 32)))
        assertTrue(tracker.advance(progress(processed = 32, sampled = 1)))
        assertFalse(tracker.advance(progress(processed = 64, sampled = 1)))
        assertTrue(tracker.advance(progress(operation = 2, processed = 1)))
        assertFalse(tracker.advance(progress(operation = 1, processed = 64, sampled = 1)))
        assertTrue(tracker.advance(progress(operation = 1, processed = 65, sampled = 1)))
    }

    @Test fun coalescedProgressPreservesAcceptedTextOrderAndReservedTerminalCapacity() = runTest {
        val queue = LocalEventBuffer(byteLimit = 64, controlLimit = 2)
        assertTrue(queue.offer(LocalRuntimeEvent.TextDelta("α")))
        assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = 1))))
        assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = 2))))
        assertTrue(queue.offer(LocalRuntimeEvent.TextDelta("β")))
        assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = 3))))
        assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = 4))))
        assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(operation = 2, processed = 1))))
        assertTrue(queue.offer(LocalRuntimeEvent.Done))
        assertFalse(queue.offer(LocalRuntimeEvent.TextDelta("late")))
        val events = buildList { while (true) add(queue.take() ?: break) }
        assertEquals("αβ", events.filterIsInstance<LocalRuntimeEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(listOf(progress(processed = 4), progress(operation = 2, processed = 1)), events.filterIsInstance<LocalRuntimeEvent.PhaseChanged>().map { it.nativeProgress })
        assertEquals(LocalRuntimeEvent.Done, events.last())
        assertFalse(events.any { it is LocalRuntimeEvent.Error })
        assertNull(queue.take())
    }

    @Test fun progressMayBeDroppedWhenOnlyRequiredControlsRemainButCannotOverflowAcceptedText() = runTest {
        val queue = LocalEventBuffer(byteLimit = 64, controlLimit = 1)
        assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.LOADING)))
        assertTrue(queue.offer(LocalRuntimeEvent.TextDelta("kept")))
        repeat(100) { assertTrue(queue.offer(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = it.toLong())))) }
        assertTrue(queue.offer(LocalRuntimeEvent.Error("native terminal failure")))
        assertEquals(LocalRuntimeEvent.PhaseChanged(LocalInferencePhase.LOADING), queue.take())
        assertEquals(LocalRuntimeEvent.TextDelta("kept"), queue.take())
        assertEquals(LocalRuntimeEvent.Error("native terminal failure"), queue.take())
        assertNull(queue.take())
    }

    @Test fun nativeProgressKeepsProductivePrefillAliveAcrossARealNewOperation() = runTest {
        val session = session {
            for (sample in listOf(progress(processed = 1000), progress(processed = 1000), progress(operation = 2, processed = 1), progress(operation = 2, processed = 2))) {
                delay(400)
                emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, sample))
            }
            delay(400)
            emit(ProviderEvent.TextDelta("complete answer"))
            emit(ProviderEvent.Completed)
        }
        val events = session.withProgressWatchdog(1000, 1000, nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        assertEquals(ProviderEvent.Completed, events.last())
        assertFalse(events.any { it is ProviderEvent.Failed })
        assertEquals(2000L, testScheduler.currentTime)
        assertEquals(4, events.filterIsInstance<ProviderEvent.PhaseChanged>().size)
    }

    @Test fun elapsedHeartbeatsAndRegressingNativeCountersCannotHideAStalledPrefill() = runTest {
        val session = session {
            delay(400)
            emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = 64)))
            repeat(30) { index ->
                delay(200)
                emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, progress(processed = if (index % 2 == 0) 64 else 32).copy(elapsedMs = 1000L + index * 200)))
            }
            emit(ProviderEvent.Completed)
        }
        val events = session.withProgressWatchdog(1000, 1000, nowMillis = { testScheduler.currentTime }).streamRound(emptyList(), emptyList()).toList()
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        val deadline = requireNotNull(failure.progressDeadline)
        assertEquals("GENERATION_STALLED", deadline.deadline)
        assertEquals(ObservedProgressStage.PREFILL, deadline.stage)
        assertFalse(deadline.visibleOutputObserved)
        assertTrue(deadline.elapsedMillis in 1400..2000)
        assertFalse(failure.transportComplete)
        assertFalse(events.any { it == ProviderEvent.Completed })
    }

    @Test fun apiReducerRetainsNativeFieldsSeparatelyFromAnswerAndCompletesNormally() = runTest {
        val sample = progress(processed = 2048, sampled = 9).copy(
            promptTokens = 4096, elapsedMs = 800, nativeCode = 0, observedAtMs = 1234,
            configuration = "cpu-batch64", effectiveBatch = 64, prefillTokensPerSecond = 2560.0,
            actualRuntime = "LLAMA_CPP_CPU", modelName = "fixture.gguf", runtimeRequestId = "runtime-7", physicalAttemptId = "attempt-3"
        )
        val received = mutableListOf<GgufNativeProgress>()
        val phases = mutableListOf<LocalInferencePhase>()
        var answer = ""
        val outcome = flowOf(ApiState.PhaseChanged(LocalInferencePhase.PREFILL, sample), ApiState.Success("answer"), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { content, _, _ -> answer = content }, onPhaseChanged = phases::add, onNativeProgress = received::add)
        assertEquals(ApiStateFlowOutcome.Completed, outcome)
        assertEquals(listOf(LocalInferencePhase.PREFILL), phases)
        assertEquals(listOf(sample), received)
        assertEquals("answer", answer)
        val encoded = Json.encodeToString(GgufNativeProgress.serializer(), received.single())
        assertEquals(sample, Json.decodeFromString(GgufNativeProgress.serializer(), encoded))
    }

    private fun progress(operation: Long = 1, processed: Long = 0, sampled: Long = 0) = GgufNativeProgress(operation, "prefill", processedTokens = processed, sampledTokens = sampled)

    private fun session(block: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.() -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow(block)
    }
}
