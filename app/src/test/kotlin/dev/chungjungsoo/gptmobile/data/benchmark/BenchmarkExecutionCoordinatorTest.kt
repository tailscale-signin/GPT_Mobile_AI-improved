package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BenchmarkExecutionCoordinatorTest {
    private fun emptyStore() = mockk<BenchmarkStore>(relaxed = true) {
        every { batches } returns MutableStateFlow(emptyList())
        coEvery { batch(any()) } returns null
    }

    @Test fun storageFailurePausesDurablyWithoutEscapingToTheProcessCrashHandler() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("benchmark_v2.db")
        context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = BenchmarkStore(context)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val escaped = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (failure is BenchmarkStorageFailure) escaped.set(failure) else previousHandler?.uncaughtException(thread, failure)
        }
        try {
            val profiles = listOf("A", "B").map { PlatformV2(uid = it, name = it, model = "model") }
            val batch = BenchmarkBatch("storage-failure", BenchmarkMode.QUICK, 1, profiles.mapIndexed { index, profile -> BenchmarkQueueItem(index, profile, "run-$index", benchmarkConfigKey(profile), if (index == 0) BenchmarkQueueState.RUNNING else BenchmarkQueueState.QUEUED) }, state = BenchmarkBatchState.RUNNING, activeOrdinal = 0)
            val run = BenchmarkRun("run-0", "A", "A", "OPENAI", "model", "key", false, BenchmarkMode.QUICK, 1, batchId = batch.id, state = "RUNNING")
            val coordinator = BenchmarkExecutionCoordinator(context, store)
            var dispatches = 0
            val job = requireNotNull(
                coordinator.launch(batch.id, prepare = { store.checkpoint(batch, run) }) {
                    dispatches++
                    throw BenchmarkStorageFailure(java.io.IOException("Disk unavailable"))
                }
            )
            assertTrue(coordinator.serviceReady(batch.id))
            withTimeout(5000) {
                job.join()
                coordinator.sessionId.first { it == null }
            }
            val saved = checkNotNull(store.batch(batch.id))
            assertEquals(BenchmarkBatchState.STORAGE_PAUSED, saved.state)
            assertEquals(BenchmarkQueueState.INTERRUPTED, saved.items[0].state)
            assertEquals("STORAGE_CHECKPOINT_FAILED", saved.items[0].failure?.code)
            assertEquals(listOf("B"), saved.pending.map { it.profile.uid })
            assertEquals("INTERRUPTED", store.history.value.single().state)
            assertTrue(store.history.value.single().finished)
            assertEquals("STORAGE_PAUSED", context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getString("status", null))
            assertTrue(coordinator.errorMutable.value.orEmpty().contains("storage is unavailable"))
            assertEquals(1, dispatches)
            assertTrue(job.isCancelled)
            assertNull(escaped.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            store.close()
            context.deleteDatabase("benchmark_v2.db")
        }
    }

    @Test fun workWaitsForForegroundAndFastCompletionStopsOnlyAfterPromotion() = runBlocking {
        var stops = 0
        val stopped = CompletableDeferred<Unit>()
        lateinit var coordinator: BenchmarkExecutionCoordinator
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun stopService(service: Intent): Boolean {
                assertTrue(coordinator.isRunning)
                assertNull(coordinator.launch { error("Cleanup still owns the service") })
                assertEquals("FINISHED", getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getString("status", null))
                stops++
                stopped.complete(Unit)
                return true
            }
        }
        val ran = CompletableDeferred<Unit>()
        coordinator = BenchmarkExecutionCoordinator(context, emptyStore())
        val job = requireNotNull(coordinator.launch { ran.complete(Unit) })
        assertNull(withTimeoutOrNull(100) { ran.await() })
        assertFalse(ran.isCompleted)
        assertEquals(0, stops)
        assertFalse(coordinator.serviceReady("stale-session"))
        assertFalse(ran.isCompleted)
        assertTrue(coordinator.serviceReady(coordinator.sessionId.value))
        withTimeout(5000) {
            job.join()
            stopped.await()
            coordinator.sessionId.first { it == null }
        }
        assertTrue(ran.isCompleted)
        assertEquals(1, stops)
    }

    @Test fun cancellationBeforePromotionLetsTheServiceAcknowledgeAndroidBeforeStopping() = runBlocking {
        var stops = 0
        val stopped = CompletableDeferred<Unit>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun stopService(service: Intent): Boolean {
                stops++
                stopped.complete(Unit)
                return true
            }
        }
        val coordinator = BenchmarkExecutionCoordinator(context, emptyStore())
        val first = requireNotNull(coordinator.launch { error("Cancelled work must not execute") })
        val firstId = coordinator.sessionId.value
        coordinator.cancel()
        withTimeout(5000) {
            first.join()
            coordinator.sessionId.first { it == null }
        }
        assertEquals(0, stops)
        val second = requireNotNull(coordinator.launch { awaitCancellation() })
        assertFalse(coordinator.serviceReady(firstId))
        assertTrue(coordinator.serviceReady(coordinator.sessionId.value))
        coordinator.cancel()
        withTimeout(5000) {
            second.join()
            stopped.await()
            coordinator.sessionId.first { it == null }
        }
        assertEquals(1, stops)
    }

    @Test fun foregroundStartRejectionReleasesReservedOwnership() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("Service rejected")
        }
        val coordinator = BenchmarkExecutionCoordinator(context, emptyStore())
        assertNull(coordinator.launch { error("Rejected work must not execute") })
        assertNull(coordinator.sessionId.value)
        assertFalse(coordinator.isRunning)
        assertFalse(context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getBoolean("running", false))
    }

    @Test fun immediateCancellationClearsTheSessionAndAllowsASecondRun() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BenchmarkExecutionCoordinator(context, emptyStore())
        val first = requireNotNull(coordinator.launch { awaitCancellation() })
        assertNull(coordinator.launch { error("Only one run can own the service") })
        coordinator.cancel()
        withTimeout(5000) {
            first.join()
            coordinator.sessionId.first { it == null }
        }
        assertNull(coordinator.sessionId.value)
        assertFalse(coordinator.isRunning)
        assertEquals("STOPPED", context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getString("status", null))
        assertTrue(first.isCancelled)
        val second = requireNotNull(coordinator.launch { awaitCancellation() })
        coordinator.cancel()
        withTimeout(5000) {
            second.join()
            coordinator.sessionId.first { it == null }
        }
        assertNull(coordinator.sessionId.value)
    }
}
