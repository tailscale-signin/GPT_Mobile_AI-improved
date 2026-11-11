package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceEntryViewModelTest {
    @Test fun firstUseNeedsExplicitEnableAndRepeatedStartCannotCreateDuplicateChats() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val views = ViewModelStore()
        try {
            val settings = mockk<SettingRepository>(relaxed = true)
            val chats = mockk<ChatRepository>(relaxed = true)
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings().withToolPluginEnabled(ToolPluginId.VOICE_CHAT, false)
            coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "chosen", name = "Chosen", enabled = true, model = "model"))
            coEvery { chats.createDraftChat(any(), any(), any(), any()) } returns ChatRoomV2(id = 42, title = "Voice chat", enabledPlatform = listOf("chosen"), activePlatform = listOf("chosen"))
            val model = VoiceEntryViewModel(SavedStateHandle(), settings, chats)
            views.put("entry", model)
            model.start()
            assertNull(model.state.value.route)
            coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
            model.start(enable = true)
            assertNotNull(model.state.value.route)
            model.start(enable = true)
            coVerify(exactly = 1) { chats.createDraftChat(listOf("chosen"), any(), any(), "chosen") }
        } finally {
            views.clear()
            Dispatchers.resetMain()
        }
    }
}
