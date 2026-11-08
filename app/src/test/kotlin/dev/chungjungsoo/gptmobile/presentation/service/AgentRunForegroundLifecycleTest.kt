package dev.chungjungsoo.gptmobile.presentation.service

import android.app.Application
import android.content.pm.ServiceInfo
import dagger.Lazy
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentRunForegroundLifecycleTest {
    private val coordinator = mockk<AgentRunCoordinator>(relaxed = true).also {
        every { it.activeRuns } returns MutableStateFlow(emptyMap())
    }

    private fun service(): AgentRunForegroundService =
        Robolectric.buildService(AgentRunForegroundService::class.java).get().apply {
            // Exercise the lifecycle callback without starting Hilt or a process
            // observer. The Android Service methods still use Robolectric.
            coordinatorProvider = Lazy { coordinator }
        }

    @Test fun platformTimeoutInterruptsObservationWithoutIssuingUserCancellation() {
        service().onTimeout(7, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        verify(exactly = 1) { coordinator.interruptAll() }
        verify(exactly = 0) { coordinator.cancelAll() }
    }

    @Test fun serviceDestructionDoesNotCancelAcceptedGatewayWork() {
        service().onDestroy()

        verify(exactly = 0) { coordinator.cancelAll() }
        verify(exactly = 0) { coordinator.interruptAll() }
    }
}
