package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.EngineLoadReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeExecutionIdentity
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BenchmarkBatchReceiptIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun acceptedNewPolicyContinuesAfterConfirmedNativeOutputLimitAndSavesPreCleanupPeak() = runBlocking {
        exercise(unsafeCleanup = false)
    }

    @Test fun unconfirmedCleanupStopsLaterLocalCasesBeforeAnotherSessionCanOpen() = runBlocking {
        exercise(unsafeCleanup = true)
    }

    @Test fun crashAfterAllCasesCommittedKeepsRecoveredPartialWithoutReopeningItsRequest() = runBlocking {
        val store = BenchmarkStore(context)
        try {
            for (version in listOf(8, 9)) {
                val profile = PlatformV2(uid = "profile-$version", name = "Profile", model = "model")
                val manifest = benchmarkSuiteForVersion(version, BenchmarkMode.QUICK)
                val cleanup = RuntimeCleanupReceipt("benchmark", obligationsObserved = true, nativeClosureConfirmed = true, conversationClosed = true, engineHealthy = true)
                val samples = manifest.mapIndexed { index, test ->
                    BenchmarkSample(
                        test.id, test.label, test.category, if (index == 0) BenchmarkOutcome.PARTIAL else BenchmarkOutcome.PASSED, requestDispatched = true, terminalReceived = true, canonicalAnswer = "Retained $index", answerHash = benchmarkHash("Retained $index"), warmup = test.warmup, workload = test.workload, runtimeCleanup = cleanup,
                        failure = if (index == 0) BenchmarkFailure("OUTPUT_LIMIT", "generation", "Output cap reached", scope = "request") else null
                    )
                }
                val batch = BenchmarkBatch("crash-$version", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run-$version", "revision", state = BenchmarkQueueState.RUNNING)), state = BenchmarkBatchState.RUNNING, suiteVersion = version, recoveryPolicy = "INDEPENDENT_CASES_AFTER_CONFIRMED_CLEANUP_V1".takeIf { version == 9 })
                val run = BenchmarkRun("run-$version", profile.uid, profile.name, "CUSTOM", "model", "revision", false, BenchmarkMode.QUICK, 1, samples, finished = false, suiteVersion = version, plannedTrials = manifest.size, batchId = batch.id, queueOrdinal = 0, caseManifest = manifest)
                store.checkpoint(batch, run, event = "CASE_FINISHED")
                store.recoverBatches()
                val recovered = store.history.value.single { it.id == run.id }
                val saved = checkNotNull(store.batch(batch.id))
                assertTrue(recovered.finished)
                assertEquals(BenchmarkOutcome.PARTIAL, recovered.samples.first().outcome)
                assertEquals(benchmarkHash("Retained 0"), recovered.samples.first().answerHash)
                assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, saved.state)
                if (version == 9) {
                    assertEquals(BenchmarkQueueState.COMPLETED, saved.items.single().state)
                    assertEquals("COMPLETED_WITH_FAILURES", recovered.state)
                    assertEquals(null, recovered.stoppedReason)
                } else {
                    assertEquals(BenchmarkQueueState.FAILED, saved.items.single().state)
                }
            }
        } finally {
            store.close()
        }
    }

    private suspend fun exercise(unsafeCleanup: Boolean) {
        val store = BenchmarkStore(context)
        try {
            val profile = PlatformV2(uid = "local", name = "Local", model = "model.gguf", compatibleType = ClientType.LITERT_LM)
            val settings = mockk<SettingRepository>()
            coEvery { settings.fetchPlatformV2s() } returns listOf(profile)
            val environment = mockk<Context>()
            val battery = mockk<BatteryManager>()
            every { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns 90
            every { environment.getSystemService(BatteryManager::class.java) } returns battery
            val power = mockk<PowerManager>()
            every { power.currentThermalStatus } returns 0
            every { environment.getSystemService(PowerManager::class.java) } returns power
            val spec = LocalEngineSpec("model.gguf", "cpu", 2048)
            val state = MutableStateFlow(LocalRuntimeState())
            var receipt: RuntimeExecutionReceipt? = null
            var load: EngineLoadReceipt? = null
            var workerPss = 146_000_000L
            var cleanupCalls = 0
            var opened = 0
            var quarantined = false
            val runtime = mockk<LocalRuntime>()
            every { runtime.state } returns state
            every { runtime.loadedEngineSpec() } answers { state.value.engineSpec }
            every { runtime.admittedEngineSpec() } answers { state.value.engineSpec }
            every { runtime.latestEngineLoadReceipt } answers { load }
            every { runtime.latestExecutionReceipt } answers { receipt }
            every { runtime.latestInspectionReceipt } returns null
            every { runtime.executionIdentity } returns LocalRuntimeExecutionIdentity(workerGenerationId = "worker", engineInstanceId = "engine")
            every { runtime.isNativeOperationBusy } returns false
            every { runtime.processPssBytes } answers { workerPss }
            coEvery { runtime.cleanupAfterRequest(any()) } answers {
                assertTrue(InferenceAdmission.isExclusiveBusy)
                cleanupCalls++
                workerPss = 146_000_000L
                if (unsafeCleanup && opened > 0) RuntimeCleanupReceipt("benchmark", obligationsObserved = true, nativeClosureConfirmed = null, conversationClosed = true, engineHealthy = false) else RuntimeCleanupReceipt("benchmark", obligationsObserved = true, nativeClosureConfirmed = true, conversationClosed = true, engineHealthy = true, notRequiredReason = "idle".takeIf { opened == 0 })
            }
            every { runtime.quarantineAfterUnconfirmedCleanup(any()) } answers {
                assertTrue(InferenceAdmission.isExclusiveBusy)
                quarantined = true
                RuntimeCleanupReceipt("benchmark", quarantined = true, failureReason = "native closure unknown")
            }
            val chats = mockk<ChatRepository>()
            coEvery { chats.validateBenchmarkProfile(any()) } returns Unit
            coEvery { chats.benchmarkContextTokens(any()) } returns 2048
            coEvery { chats.supportsBenchmarkTools(any()) } returns false
            coEvery { chats.openBenchmarkSession(any(), any(), any(), any(), any(), any(), any()) } answers {
                assertFalse(quarantined)
                opened++
                val index = opened
                val physical = "physical-$index"
                arg<(String) -> Unit>(6)(physical)
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        state.value = LocalRuntimeState(backend = LocalRuntimeBackend.LLAMA_CPP, engineSpec = spec)
                        workerPss = 4_200_000_000L
                        load = EngineLoadReceipt("load", "artifact", "runtime", "cpu", "cpu", "engine", startedAtElapsedMs = 0, completedAtElapsedMs = 1, outcome = "READY")
                        receipt = RuntimeExecutionReceipt(runtimeRequestId = "runtime-$index", physicalAttemptId = physical, workerGenerationId = "worker", engineInstanceId = "engine", nativeOperationId = index.toLong(), nativeGenerationId = "generation-$index", nativeEntered = true, nativeEnteredAtElapsedMs = SystemClock.elapsedRealtime(), observedAtElapsedMs = SystemClock.elapsedRealtime(), engineLoad = load)
                        emit(ProviderEvent.PhaseChanged(LocalInferencePhase.PREFILL, GgufNativeProgress(index.toLong(), "NATIVE_ENTRY", runtimeRequestId = "runtime-$index", physicalAttemptId = physical)))
                        emit(ProviderEvent.TextDelta(if (index == 1) "Partial final output" else "A wrong but completed answer"))
                        receipt = receipt!!.copy(nativeReturned = true, nativeReturnedAtElapsedMs = SystemClock.elapsedRealtime(), terminalKind = if (index == 1) "output_limit" else "eos", observedAtElapsedMs = SystemClock.elapsedRealtime())
                        emit(ProviderEvent.PhaseChanged(LocalInferencePhase.GENERATING, GgufNativeProgress(index.toLong(), "NATIVE_RETURN", nativeCode = if (index == 1) 1 else 0, runtimeRequestId = "runtime-$index", physicalAttemptId = physical)))
                        if (index == 1) {
                            emit(ProviderEvent.Usage(finishReason = "length"))
                            emit(ProviderEvent.Failed("Output cap reached"))
                        } else {
                            emit(ProviderEvent.Completed)
                        }
                    }
                }
            }
            val batch = BenchmarkBatch("batch", BenchmarkMode.QUICK, 1, listOf(BenchmarkQueueItem(0, profile, "run", "revision")), suiteVersion = 9)
            store.checkpoint(batch, event = "MANIFEST_SAVED")
            assertEquals("INDEPENDENT_CASES_AFTER_CONFIRMED_CLEANUP_V1", store.batch("batch")?.recoveryPolicy)
            BenchmarkBatchRunner(store, chats, settings, runtime, environment).run("batch") {}
            val run = store.history.value.single { it.id == "run" }
            val first = run.samples.first()
            assertTrue(run.finished)
            assertEquals(BenchmarkOutcome.PARTIAL, first.outcome)
            assertEquals(true, first.requestDispatched)
            assertEquals(true, first.terminalReceived)
            assertEquals("output_limit", first.runtimeExecution?.terminalKind)
            assertEquals(4_200_000_000L, first.peakExecutionWorkerPssBytes)
            assertEquals(4_200_000_000L, first.workerPssBytes)
            assertEquals(146_000_000L, run.resources.first { it.testId == first.testId && it.scope == "after_cleanup" }.workerPssBytes)
            assertEquals(5, run.samples.size)
            if (unsafeCleanup) {
                assertEquals(1, opened)
                assertTrue(quarantined)
                assertTrue(run.samples.drop(1).all { it.outcome == BenchmarkOutcome.SKIPPED && it.requestDispatched == false })
                assertNotNull(run.stoppedReason)
            } else {
                assertEquals(4, opened)
                assertEquals(true, first.runtimeCleanup?.safeForSuccessor)
                assertEquals(true, run.samples[1].requestDispatched)
                assertFalse(run.samples[1].outcome == BenchmarkOutcome.SKIPPED)
                assertFalse(quarantined)
                assertEquals(6, cleanupCalls)
            }
            assertEquals(BenchmarkBatchState.COMPLETED_WITH_FAILURES, store.batch("batch")?.state)
        } finally {
            store.close()
        }
    }
}
