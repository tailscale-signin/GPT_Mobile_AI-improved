package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.FakeLocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalConversationConfig
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineHolder
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.ResourceRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkCleanupTest {
    @Test fun stalledCheckpointReadHasADeadlineAndReleasesOnlyAfterNativeCleanup() = runTest {
        val native = FakeLocalRuntime()
        val runtime = LocalEngineHolder(native) { testScheduler.currentTime }
        runtime.createConversation(LocalConversationConfig(LocalSamplerConfig(40, .95f, .8f), null, emptyList()))
        val trialEntered = CompletableDeferred<Unit>()
        val stalledRead = CompletableDeferred<Unit>()
        var committed = false
        var nextCase = false
        var remoteRan = false
        val remote = launch {
            trialEntered.await()
            InferenceAdmission.shared(ResourceRequest.remote("remote-after-stall", "endpoint")) {
                assertEquals(1, native.closeConversationCalls)
                assertFalse(runtime.hasOpenConversation())
                assertFalse(runtime.isNativeOperationBusy)
                remoteRan = true
            }
        }
        try {
            InferenceAdmission.benchmark {
                trialEntered.complete(Unit)
                assertEquals("conversation_closed", cleanupBenchmarkLocalRuntime(runtime))
                withContext(NonCancellable) {
                    benchmarkCheckpointWithinDeadline(100) {
                        assertTrue(InferenceAdmission.isExclusiveBusy)
                        stalledRead.await()
                        committed = true
                    }
                }
                nextCase = true
            }
        } catch (failure: BenchmarkStorageFailure) {
            // Coroutine stack recovery can add a copy of the storage exception at
            // the withContext boundary; the original timeout must remain causal.
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is TimeoutCancellationException })
        }
        remote.join()
        assertEquals(100L, testScheduler.currentTime)
        assertFalse(committed)
        assertFalse(nextCase)
        assertTrue(remoteRan)
        assertFalse(InferenceAdmission.isExclusiveBusy)
    }

    @Test fun trueParentCancellationIsNotConvertedToStorageFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stalledRead = CompletableDeferred<Unit>()
        var canceled = false
        var misclassified = false
        val owner = launch {
            try {
                benchmarkCheckpointWithinDeadline {
                    entered.complete(Unit)
                    stalledRead.await()
                }
            } catch (_: BenchmarkStorageFailure) {
                misclassified = true
            } catch (_: CancellationException) {
                canceled = true
            }
        }
        entered.await()
        owner.cancelAndJoin()
        assertTrue(canceled)
        assertFalse(misclassified)
    }

    @Test fun owningMeasurementDoesNotQuarantineAnIdleOrAcknowledgedRuntime() = runTest {
        val native = FakeLocalRuntime()
        val runtime = LocalEngineHolder(native) { 1000L }
        InferenceAdmission.benchmark {
            assertTrue(runtime.isBusy)
            assertFalse(runtime.isNativeOperationBusy)
            assertEquals("not_required", cleanupBenchmarkLocalRuntime(runtime))
            runtime.createConversation(LocalConversationConfig(LocalSamplerConfig(40, .95f, .8f), null, emptyList()))
            assertEquals("conversation_closed", cleanupBenchmarkLocalRuntime(runtime))
            assertEquals(1, native.closeConversationCalls)
            assertEquals(0, native.cancelActiveCalls)
            assertFalse(runtime.hasOpenConversation())
            assertFalse(runtime.isNativeOperationBusy)
            // Cleanup must not release measurement ownership before its checkpoint.
            assertTrue(runtime.isBusy)
            assertTrue(InferenceAdmission.isExclusiveBusy)
            runtime.runExclusive { assertTrue(runtime.isNativeOperationBusy) }
            assertFalse(runtime.isNativeOperationBusy)
        }
        assertFalse(runtime.isBusy)
    }

    @Test fun closedConversationWithUnacknowledgedWorkerNeverProducesReadyReceipt() = runTest {
        val native = FakeLocalRuntime()
        var quarantinedWorkerAlive = true
        val worker = object : LocalRuntime by native {
            override val isBusy get() = quarantinedWorkerAlive
            override val isNativeOperationBusy get() = quarantinedWorkerAlive
        }
        val runtime = LocalEngineHolder(worker) { 1000L }
        InferenceAdmission.benchmark {
            assertEquals("cleanup_unconfirmed_local_blocked", cleanupBenchmarkLocalRuntime(runtime))
            assertEquals(1, native.cancelActiveCalls)
            assertEquals(1, native.closeConversationCalls)
            assertFalse(runtime.hasOpenConversation())
            assertTrue(runtime.isNativeOperationBusy)
            assertTrue(InferenceAdmission.isExclusiveBusy)
        }
        assertTrue(runtime.isBusy)
        quarantinedWorkerAlive = false
        assertFalse(runtime.isNativeOperationBusy)
        assertFalse(runtime.isBusy)
    }
}
