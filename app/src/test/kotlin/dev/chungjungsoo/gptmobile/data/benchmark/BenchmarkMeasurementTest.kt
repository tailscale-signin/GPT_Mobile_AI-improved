package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase
import dev.chungjungsoo.gptmobile.data.localruntime.FakeLocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeExecutionIdentity
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState
import dev.chungjungsoo.gptmobile.data.localruntime.ResourceLease
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkMeasurementTest {
    private val test = BenchmarkCase("instruction", "Instruction", "task", "Reply READY", "READY")
    private fun session(events: Flow<ProviderEvent>) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = events
    }

    @Test fun finalNativeCountsReplaceEarlierEstimatesAndRetainIndependentInputOutputSources() = runTest {
        val sample = BenchmarkRunner({ _, _ ->
            session(
                flowOf(
                    ProviderEvent.Usage(inputTokens = 500, outputTokens = 900, inputSource = "estimated", outputSource = "estimated"),
                    ProviderEvent.TextDelta("READY"),
                    ProviderEvent.Usage(inputTokens = 42, outputTokens = 7, inputSource = "runtime_reported", outputSource = "runtime_reported"),
                    ProviderEvent.Completed
                )
            )
        }).run(test, false)
        assertEquals(BenchmarkOutcome.PASSED, sample.outcome)
        assertEquals(42, sample.inputTokens)
        assertEquals(7, sample.outputTokens)
        assertEquals("runtime_reported", sample.inputTokenSource)
        assertEquals("runtime_reported", sample.outputTokenSource)
        assertEquals("final_reported", sample.outputTokenConfidence)
        assertEquals("final_reported", sample.rounds.single().inputTokenConfidence)
        assertFalse(sample.estimatedTokens)
    }

    @Test fun interimOrEstimatedUsageCannotBecomeFinalMeasuredUsage() = runTest {
        for ((source, phase, confidence) in listOf(Triple("provider_reported", ProviderUsagePhase.INITIAL, "partial_observation"), Triple("estimated", ProviderUsagePhase.FINAL, "estimated"))) {
            val sample = BenchmarkRunner({ _, _ -> session(flowOf(ProviderEvent.TextDelta("READY"), ProviderEvent.Usage(outputTokens = 3, outputSource = source, phase = phase), ProviderEvent.Completed)) }).run(test, false)
            assertTrue(sample.estimatedTokens)
            assertEquals(confidence, sample.outputTokenConfidence)
            assertEquals("unknown", sample.inputTokenSource)
            assertEquals("unknown", sample.inputTokenConfidence)
            assertEquals(if (phase == ProviderUsagePhase.INITIAL) "provider_reported_initial" else source, sample.outputTokenSource)
        }
    }

    @Test fun reasoningOnlyExhaustionRetainsUsageWithoutInventingAnAnswer() = runTest {
        val sample = BenchmarkRunner({ _, _ -> session(flowOf(ProviderEvent.Usage(outputTokens = 512, reasoningTokens = 512, finishReason = "length"), ProviderEvent.Completed)) }).run(test, false)
        assertEquals(BenchmarkOutcome.ERROR, sample.outcome)
        assertEquals("NO_VISIBLE_ANSWER", sample.failureCategory)
        assertEquals(512, sample.reasoningTokens)
        assertEquals("final_reported", sample.reasoningTokenConfidence)
        assertEquals(0, sample.outputCharacters)
        assertEquals("", sample.canonicalAnswer)
        assertFalse(sample.completed)
    }

    @Test fun phaseIntervalsPartitionElapsedTimeAndRetainNativeRequestJoins() = runTest {
        val progress = GgufNativeProgress(9, "WEIGHTS_LOADING", runtimeRequestId = "worker-request", physicalAttemptId = "physical-attempt")
        val sample = BenchmarkRunner({ _, _ ->
            delay(25)
            session(
                flow {
                    emit(ProviderEvent.PhaseChanged(LocalInferencePhase.LOADING, progress))
                    delay(100)
                    emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL))
                    delay(40)
                    emit(ProviderEvent.PhaseChanged(LocalInferencePhase.GENERATING))
                    delay(10)
                    emit(ProviderEvent.TextDelta("REA"))
                    delay(20)
                    emit(ProviderEvent.TextDelta("DY"))
                    emit(ProviderEvent.Completed)
                }
            )
        }, now = { testScheduler.currentTime }).run(test, false)
        assertEquals(195L, sample.durationMs)
        assertEquals(175L, sample.firstTextMs)
        assertEquals(25L, sample.phaseElapsedMs["session_setup"])
        assertEquals(100L, sample.phaseElapsedMs["loading"])
        assertEquals(40L, sample.phaseElapsedMs["prefill"])
        assertEquals(10L, sample.phaseElapsedMs["generating"])
        assertEquals(20L, sample.phaseElapsedMs["visible_output"])
        assertEquals(sample.durationMs, sample.phaseIntervals.sumOf { it.durationMs })
        assertEquals(sample.durationMs, sample.phaseElapsedMs.values.sum())
        val load = sample.phaseIntervals.single { it.phase == "loading" }
        assertEquals(9L, load.nativeOperationId)
        assertEquals("worker-request", load.runtimeRequestId)
        assertEquals("physical-attempt", load.physicalAttemptId)
    }

    @Test fun timeoutRetainsTheObservedLoadStageRatherThanClaimingAdmissionTimedOut() = runTest {
        val sample = BenchmarkRunner({ _, _ ->
            session(
                flow {
                    emit(ProviderEvent.PhaseChanged(LocalInferencePhase.LOADING))
                    delay(2000)
                }
            )
        }, now = { testScheduler.currentTime }, timeoutMs = 1000).run(test, false)
        assertEquals(BenchmarkOutcome.TIMED_OUT, sample.outcome)
        assertEquals("REQUEST_DEADLINE_EXCEEDED", sample.failure?.code)
        assertEquals("loading", sample.failure?.stage)
        assertEquals(1000L, sample.phaseElapsedMs["loading"])
    }

    @Test fun actualRuntimeJoinKeepsUnavailableEngineDriverAndOffloadUnknown() {
        val runtime = object : LocalRuntime by FakeLocalRuntime() {
            override val state = MutableStateFlow(LocalRuntimeState(backend = LocalRuntimeBackend.LLAMA_CPP, engineSpec = LocalEngineSpec("model.gguf", "cpu", 2048, cpuThreads = 4, ggufGpuLayers = 8), fallbackReason = "Requested GPU unavailable"))
            override val executionIdentity = LocalRuntimeExecutionIdentity("worker-generation", "conversation")
        }
        val lease = ResourceLease("measurement", "owner", 3, emptySet(), 100, 20, true, false)
        val identity = benchmarkNativeIdentity(runtime, "run", "attempt", lease)
        assertEquals("worker-generation", identity.workerGenerationId)
        assertEquals("measurement", identity.measurementLeaseId)
        assertEquals("LLAMA_CPP", identity.actualRuntime)
        assertEquals("cpu", identity.actualAccelerator)
        assertEquals(8, identity.configuredOffloadLayers)
        assertNull(identity.actualOffloadLayers)
        assertNull(identity.engineInstanceId)
        assertNull(identity.driverIdentity)
        assertTrue(identity.unknownFields.containsAll(setOf("actualOffloadLayers", "engineInstanceId", "driverIdentity")))
    }
}
