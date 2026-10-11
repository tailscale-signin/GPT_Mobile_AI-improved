package dev.chungjungsoo.gptmobile.data.benchmark

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BenchmarkExecutionCoordinatorTest {
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
        withTimeout(5000) { first.join() }
        assertNull(coordinator.sessionId.value)
        assertFalse(coordinator.isRunning)
        assertEquals("STOPPED", context.getSharedPreferences("benchmark_execution_v1", Context.MODE_PRIVATE).getString("status", null))
        val second = requireNotNull(coordinator.launch { awaitCancellation() })
        coordinator.cancel()
        withTimeout(5000) { second.join() }
        assertNull(coordinator.sessionId.value)
    }
}
