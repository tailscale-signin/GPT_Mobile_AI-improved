package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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
        coordinator = BenchmarkExecutionCoordinator(context, mockk(relaxed = true))
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
        val coordinator = BenchmarkExecutionCoordinator(context, mockk(relaxed = true))
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
        val coordinator = BenchmarkExecutionCoordinator(context, mockk(relaxed = true))
        assertNull(coordinator.launch { error("Rejected work must not execute") })
        assertNull(coordinator.sessionId.value)
        assertFalse(coordinator.isRunning)
        assertFalse(context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getBoolean("running", false))
    }

    @Test fun immediateCancellationClearsTheSessionAndAllowsASecondRun() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BenchmarkExecutionCoordinator(context, mockk(relaxed = true))
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
        val second = requireNotNull(coordinator.launch { awaitCancellation() })
        coordinator.cancel()
        withTimeout(5000) {
            second.join()
            coordinator.sessionId.first { it == null }
        }
        assertNull(coordinator.sessionId.value)
    }
}
