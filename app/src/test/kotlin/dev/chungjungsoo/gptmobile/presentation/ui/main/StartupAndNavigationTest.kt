package dev.chungjungsoo.gptmobile.presentation.ui.main

import androidx.lifecycle.SavedStateHandle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupAndNavigationTest {
    @Test fun consumedNotificationDoesNotReplayAndPendingNotificationSurvivesRecreation() {
        val state = SavedStateHandle()
        val first = NotificationNavigationQueue(state)
        first.enqueue("chat_room/1")
        val event = first.pending.value.single()
        first.consume(event)
        NotificationNavigationQueue(state).enqueue("chat_room/1", event.substringBefore('|'))
        assertTrue(NotificationNavigationQueue(state).pending.value.isEmpty())
        first.enqueue("chat_room/2")
        val restored = NotificationNavigationQueue(SavedStateHandle(mapOf("pendingNavigation" to first.pending.value)))
        assertEquals("chat_room/2", restored.pending.value.single().substringAfter('|'))
        restored.consume(restored.pending.value.single())
        restored.enqueue("chat_room/1")
        assertNotEquals(event, restored.pending.value.single())
    }

    @Test fun profileReadFailureIsActionableAndRetryCanReachHome() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            StartupRecoveryGate.start(backgroundScope) {}
            StartupRecoveryGate.await()
            val repository = mockk<SettingRepository>()
            var fail = true
            coEvery { repository.fetchPlatforms() } returns emptyList()
            coEvery { repository.fetchPlatformV2s() } answers { if (fail) error("Profile read failed") else listOf(PlatformV2(uid = "saved", name = "Saved profile")) }
            val vm = MainViewModel(javax.inject.Provider { repository }, SavedStateHandle())
            assertFalse(vm.isReady.value)
            assertTrue(vm.startupError.value.orEmpty().contains("Profile read failed"))
            fail = false
            vm.retryStartup()
            assertTrue(vm.isReady.value)
            assertNull(vm.startupError.value)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
