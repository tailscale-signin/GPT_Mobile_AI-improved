package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Test fun incompatiblePrivacyRouteIsRejectedBeforeCreatingAVoiceChatOrChangingPreferences() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val views = ViewModelStore()
        try {
            val settings = mockk<SettingRepository>(relaxed = true)
            val chats = mockk<ChatRepository>(relaxed = true)
            val blocked = PlatformV2(uid = "blocked", name = "Blocked", compatibleType = ClientType.OPENROUTER, model = "openai/gpt-audio-mini", openRouterRouting = "{\"zdr\":true}")
            val compatible = PlatformV2(uid = "compatible", name = "Compatible", model = "model")
            val reason = "POLICY_RESTRICTED: Choose a compatible AI profile; privacy protections were preserved."
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings().withToolPluginEnabled(ToolPluginId.VOICE_CHAT, true)
            coEvery { settings.fetchPlatformV2s() } returns listOf(blocked, compatible)
            coEvery { chats.preflightAnswerPrivacy(blocked) } throws IllegalStateException(reason)
            coEvery { chats.createDraftChat(any(), any(), any(), any()) } returns ChatRoomV2(id = 42, title = "Voice chat", enabledPlatform = listOf("compatible"), activePlatform = listOf("compatible"))
            val model = VoiceEntryViewModel(SavedStateHandle(), settings, chats)
            views.put("entry", model)
            model.select("blocked")
            model.start()
            assertEquals("Choose a compatible AI profile; privacy protections were preserved.", model.state.value.error)
            assertFalse(model.state.value.creating)
            assertNull(model.state.value.route)
            coVerify(exactly = 0) { chats.createDraftChat(any(), any(), any(), any()) }
            coVerify(exactly = 0) { settings.mutateFeatureSettings(any()) }
            assertEquals("{\"zdr\":true}", blocked.openRouterRouting)

            model.select("compatible")
            model.start()
            assertNotNull(model.state.value.route)
            coVerify(exactly = 1) { chats.preflightAnswerPrivacy(compatible) }
            coVerify(exactly = 1) { chats.createDraftChat(listOf("compatible"), any(), any(), "compatible") }
        } finally {
            views.clear()
            Dispatchers.resetMain()
        }
    }

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
            assertNull(model.state.value.route)
            model.select("chosen")
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
