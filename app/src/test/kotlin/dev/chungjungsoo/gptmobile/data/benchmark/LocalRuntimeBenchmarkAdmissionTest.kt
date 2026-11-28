package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalRuntimeBenchmarkAdmissionTest {
    @Test fun stoppingAQueuedComparisonCannotCancelTheRuntimeHolder() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val runtime = mockk<LocalRuntime>()
        val store = mockk<BenchmarkStore>()
        val coordinator = mockk<BenchmarkExecutionCoordinator>()
        var worker: Job? = null
        every { coordinator.progressMutable } returns MutableStateFlow<BenchmarkProgress?>(null)
        every { coordinator.sessionId } returns MutableStateFlow<String?>("comparison")
        every { coordinator.stopReason } returns "Local comparison stopped by user"
        every { coordinator.launch(any(), any(), any()) } answers {
            val work = thirdArg<suspend () -> Unit>()
            launch { work() }.also { worker = it }
        }
        every { coordinator.cancel(any(), any(), any()) } answers {
            worker?.cancel()
            Unit
        }
        val release = CompletableDeferred<Unit>()
        val holder = launch { InferenceAdmission.shared { release.await() } }
        val suite = LocalRuntimeBenchmarkSuite(context, runtime, store, coordinator)
        try {
            runCurrent()
            assertTrue(suite.start(listOf(LocalBenchmarkCandidate("CPU", LocalEngineSpec("model", "cpu", 1024)))))
            runCurrent()
            assertTrue(requireNotNull(suite.progress.value).label.contains("Waiting for resources"))
            suite.stop()
            runCurrent()
            assertTrue(requireNotNull(worker).isCancelled)
            assertNull(suite.progress.value)
            assertTrue(requireNotNull(suite.error.value).contains("stopped by user"))
            assertFalse(holder.isCompleted)
            verify { runtime wasNot Called }
            verify { store wasNot Called }
            release.complete(Unit)
            holder.join()
            var successor = false
            InferenceAdmission.shared { successor = true }
            assertTrue(successor)
        } finally {
            release.complete(Unit)
            worker?.cancelAndJoin()
            holder.cancelAndJoin()
        }
    }

    @Test fun blockedComparisonShowsItsWaitAndExpiresWithoutStartingOrPersistingATrial() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val runtime = mockk<LocalRuntime>()
        val store = mockk<BenchmarkStore>()
        val coordinator = mockk<BenchmarkExecutionCoordinator>()
        every { coordinator.progressMutable } returns MutableStateFlow<BenchmarkProgress?>(null)
        every { coordinator.launch(any(), any(), any()) } answers {
            val work = thirdArg<suspend () -> Unit>()
            launch { work() }
        }
        val release = CompletableDeferred<Unit>()
        val holder = launch { InferenceAdmission.shared { release.await() } }
        val suite = LocalRuntimeBenchmarkSuite(context, runtime, store, coordinator)
        try {
            runCurrent()
            assertTrue(suite.start(listOf(LocalBenchmarkCandidate("CPU", LocalEngineSpec("model", "cpu", 1024)))))
            runCurrent()
            assertTrue(requireNotNull(suite.progress.value).label.contains("Waiting for resources"))
            assertNull(suite.error.value)
            advanceTimeBy(BENCHMARK_ADMISSION_QUEUE_TIMEOUT_MS)
            runCurrent()
            assertNull(suite.progress.value)
            assertTrue(requireNotNull(suite.error.value).contains("before any comparison trial started"))
            assertFalse(holder.isCompleted)
            verify { runtime wasNot Called }
            verify { store wasNot Called }
            release.complete(Unit)
            holder.join()
            var successor = false
            InferenceAdmission.shared { successor = true }
            assertTrue(successor)
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
        }
    }
}
