package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkRuntimeReceiptTest {
    private val fixture = BenchmarkCase("first", "First", "task", "Reply OK", "OK")
    private fun session(events: Flow<ProviderEvent>) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = events
    }
    private fun healthyCleanup() = RuntimeCleanupReceipt("benchmark", requiredActions = setOf("close_conversation"), obligationsObserved = true, nativeClosureConfirmed = true, conversationClosed = true, engineHealthy = true)
    private fun entered(operation: Long = 17) = RuntimeExecutionReceipt(runtimeRequestId = "request", physicalAttemptId = "physical", nativeGenerationId = "generation-$operation", workerGenerationId = "worker", engineInstanceId = "engine", nativeOperationId = operation, nativeEntered = true, nativeEnteredAtElapsedMs = 0, observedAtElapsedMs = 0)

    @Test fun prefillWithoutVisibleOutputStillRecordsNativeDispatchWhenItTimesOut() = runTest {
        var receipt = entered()
        val runner = BenchmarkRunner(
            { _, _ ->
                session(
                    flow {
                        emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(17, "NATIVE_ENTRY", runtimeRequestId = "request", physicalAttemptId = "physical")))
                        delay(25)
                        receipt = receipt.copy(lastStage = "PREFILL_CHUNK", observedAtElapsedMs = testScheduler.currentTime)
                        emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(17, "PREFILL_CHUNK", processedTokens = 4, runtimeRequestId = "request", physicalAttemptId = "physical")))
                        awaitCancellation()
                    }
                )
            },
            now = { testScheduler.currentTime },
            timeoutMs = 50,
            observeRuntime = { receipt }
        )
        val sample = runner.run(fixture, false)
        assertEquals(BenchmarkOutcome.TIMED_OUT, sample.outcome)
        assertEquals(true, sample.requestDispatched)
        assertEquals(0, sample.outputCharacters)
        assertNull(sample.firstTextMs)
        assertEquals(false, sample.terminalReceived)
        assertTrue(sample.runtimeExecutions.single().knownDispatched)
        assertFalse(sample.runtimeExecutions.single().hasNativeTerminal)
        assertNull(sample.generationDurationMs)
        assertEquals(50L, sample.durationMs)
    }

    @Test fun outputLimitIsANativeTerminalAndHealthyCleanupAllowsOnlyIndependentSuccessors() = runTest {
        var receipt = entered()
        val partial = BenchmarkRunner(
            { _, _ ->
                session(
                    flow {
                        emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(17, "NATIVE_ENTRY")))
                        delay(10)
                        emit(ProviderEvent.TextDelta("Partial final answer"))
                        receipt = receipt.copy(nativeReturned = true, nativeReturnedAtElapsedMs = testScheduler.currentTime, terminalKind = "output_limit", observedAtElapsedMs = testScheduler.currentTime)
                        emit(ProviderEvent.PhaseChanged(LocalInferencePhase.GENERATING, GgufNativeProgress(17, "NATIVE_RETURN", nativeCode = 1)))
                        emit(ProviderEvent.Usage(finishReason = "length"))
                        emit(ProviderEvent.Failed("Output cap reached", transportComplete = false))
                    }
                )
            },
            now = { testScheduler.currentTime },
            observeRuntime = { receipt }
        ).run(fixture, false).copy(runtimeCleanup = healthyCleanup())
        assertEquals(BenchmarkOutcome.PARTIAL, partial.outcome)
        assertEquals("OUTPUT_LIMIT", partial.failureCategory)
        assertEquals(true, partial.terminalReceived)
        assertTrue(partial.runtimeExecution!!.hasNativeTerminal)
        assertEquals("output_limit", partial.runtimeExecution!!.terminalKind)
        assertEquals(10L, partial.generationDurationMs)
        assertFalse(partial.completed)
        assertEquals(BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES, partial.recoveryAction())
        assertEquals(BenchmarkRecoveryAction.STOP_PROFILE, partial.recoveryAction(allowConfirmedRecovery = false))
        val dispatched = mutableListOf<String>()
        val saved = mutableListOf<BenchmarkSample>()
        val suite = listOf(fixture, fixture.copy(id = "dependent", dependsOn = setOf(fixture.id)), fixture.copy(id = "independent"))
        val stop = runBenchmarkSuite(suite, { _, test ->
            dispatched += test.id
            if (test.id == fixture.id) partial else BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED, requestDispatched = true)
        }, { saved += it })
        assertNull(stop)
        assertEquals(listOf("first", "independent"), dispatched)
        assertEquals(BenchmarkOutcome.SKIPPED, saved[1].outcome)
        assertEquals(false, saved[1].requestDispatched)
        assertEquals("SKIPPED_DEPENDENCY", saved[1].failureCategory)
    }

    @Test fun aFailedQualityGradeDoesNotStopOtherIndependentCases() = runTest {
        val saved = mutableListOf<BenchmarkSample>()
        var called = 0
        runBenchmarkSuite(listOf(fixture, fixture.copy(id = "second")), { _, test ->
            called++
            BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.FAILED, requestDispatched = true, terminalReceived = true)
        }, { saved += it })
        assertEquals(2, called)
        assertEquals(2, saved.count { it.outcome == BenchmarkOutcome.FAILED })
    }

    @Test fun laterBookkeepingCannotEraseTheMatchingNativeReturnOrCrossRequestBoundary() {
        val first = entered().copy(nativeReturned = true, nativeReturnedAtElapsedMs = 10, terminalKind = "output_limit", observedAtElapsedMs = 10)
        val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.PARTIAL, runtimeExecutions = listOf(first))
        val bookkeeping = first.copy(nativeEntered = null, nativeEnteredAtElapsedMs = null, nativeReturned = null, nativeReturnedAtElapsedMs = null, terminalKind = null, observedAtElapsedMs = 20)
        val retained = benchmarkExecutionReceipts(sample, bookkeeping, setOf("physical")).single()
        assertTrue(retained.knownDispatched)
        assertTrue(retained.hasNativeTerminal)
        assertEquals("output_limit", retained.terminalKind)
        assertEquals(10L, retained.nativeReturnedAtElapsedMs)
        val unrelated = first.copy(runtimeRequestId = "other", physicalAttemptId = "other", workerGenerationId = "other")
        assertEquals(listOf(first), benchmarkExecutionReceipts(sample, unrelated, setOf("physical")))
    }

    @Test fun unsafeCleanupQuarantinesBeforeMeasurementOwnershipIsReleased() = runTest {
        val runtime = mockk<LocalRuntime>()
        var quarantined = false
        val unknown = RuntimeCleanupReceipt("benchmark", requiredActions = setOf("cancel", "close_conversation"), obligationsObserved = true, cancellationRequested = true, nativeClosureConfirmed = null, conversationClosed = true, engineHealthy = false)
        coEvery { runtime.cleanupAfterRequest(any()) } answers {
            assertTrue(InferenceAdmission.isExclusiveBusy)
            unknown
        }
        every { runtime.quarantineAfterUnconfirmedCleanup(any()) } answers {
            assertTrue(InferenceAdmission.isExclusiveBusy)
            quarantined = true
            unknown.copy(quarantined = true, failureReason = "Native closure not confirmed")
        }
        InferenceAdmission.benchmark {
            val receipt = cleanupBenchmarkLocalRuntimeReceipt(runtime, "benchmark")
            assertTrue(quarantined)
            assertTrue(receipt.quarantined)
            assertFalse(receipt.safeForSuccessor)
            assertEquals("cleanup_unconfirmed_local_blocked", benchmarkCleanupLabel(receipt))
        }
        assertFalse(InferenceAdmission.isExclusiveBusy)
        assertTrue(quarantined)
    }

    @Test fun stalledCleanupTransfersProtectionToQuarantineAtItsBound() = runTest {
        val runtime = mockk<LocalRuntime>()
        val entered = CompletableDeferred<Unit>()
        var quarantineAt: Long? = null
        coEvery { runtime.cleanupAfterRequest(any()) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        every { runtime.quarantineAfterUnconfirmedCleanup(any()) } answers {
            quarantineAt = testScheduler.currentTime
            RuntimeCleanupReceipt("benchmark", quarantined = true, failureReason = "cleanup deadline")
        }
        InferenceAdmission.benchmark {
            val receipt = cleanupBenchmarkLocalRuntimeReceipt(runtime, "benchmark")
            assertTrue(entered.isCompleted)
            assertTrue(receipt.quarantined)
            assertEquals(15_000L, quarantineAt)
            assertTrue(InferenceAdmission.isExclusiveBusy)
        }
    }

    @Test fun unknownCleanupAndTerminationIntentCannotAuthorizeContinuation() {
        val partial = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.PARTIAL, terminalReceived = true)
        assertEquals(BenchmarkRecoveryAction.STOP_PROFILE, partial.recoveryAction())
        val intent = RuntimeCleanupReceipt("benchmark", terminationRequested = true, obligationsObserved = true)
        assertFalse(intent.safeForSuccessor)
        assertEquals(BenchmarkRecoveryAction.QUARANTINE_RUNTIME, partial.copy(runtimeCleanup = intent).recoveryAction())
        assertEquals(BenchmarkRecoveryAction.CONTINUE_INDEPENDENT_CASES, partial.copy(runtimeCleanup = RuntimeCleanupReceipt("benchmark", obligationsObserved = true, workerExitConfirmed = true, workerExitEvidence = "binderDied")).recoveryAction())
    }

    @Test fun preDispatchQuarantineRetainsElapsedWaitWithoutInventingGenerationLatency() {
        val sample = benchmarkAdmissionOnlySample(fixture, BenchmarkFailure("NATIVE_QUARANTINED", "preflight_admission", "Earlier native work has not closed."), 5250)
        assertEquals(5250L, sample.endToEndDurationMs)
        assertEquals(5250L, sample.admissionWaitMs)
        assertEquals(5250L, sample.phaseElapsedMs.getValue("preflight_admission"))
        assertEquals(false, sample.requestDispatched)
        assertEquals(false, sample.requestAdmitted)
        assertNull(sample.generationDurationMs)
        assertNull(sample.firstTextMs)
        assertNull(sample.nativeMetrics)
        assertTrue(sample.runtimeExecutions.isEmpty())
        assertNotNull(sample.fixtureContentHash)
    }
}
