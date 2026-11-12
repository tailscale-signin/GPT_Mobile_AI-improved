package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.chat.ConversationFolderRepository
import dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore
import dev.chungjungsoo.gptmobile.data.chat.PinnedConversationOrderStore
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceEntryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayModelEntryWiringTest {
    @Test fun newChatRestoresOnlySelectedModelsAndTracksConfigurationReasoningAndDeselection() = withFixture { fixture ->
        val model = fixture.newChat(listOf("chosen", "remote"))
        collectProfiles(model)
        runCurrent()
        assertEquals(listOf("chosen"), fixture.calls.map { it.profile.uid })
        assertEquals(ReasoningMode.ON, fixture.calls.single().mode)
        assertEquals(setOf("chosen"), model.gatewayModelPreparation.value.keys)

        fixture.profiles.value = fixture.profiles.value.map { if (it.uid == "chosen") it.copy(model = "model-revised") else it }
        runCurrent()
        assertEquals("model-revised", fixture.calls.last().profile.model)
        fixture.features.value = fixture.features.value.copy(llamaReasoningModes = mapOf("chosen" to ReasoningMode.OFF))
        runCurrent()
        assertEquals(ReasoningMode.OFF, fixture.calls.last().mode)

        model.toggleProfile("chosen")
        runCurrent()
        assertTrue(model.gatewayModelPreparation.value.isEmpty())
        val before = fixture.calls.size
        fixture.profiles.value = fixture.profiles.value.map { if (it.uid == "other") it.copy(model = "unselected-revised") else it }
        runCurrent()
        assertEquals(before, fixture.calls.size)

        model.toggleProfile("chosen")
        runCurrent()
        model.recheckGatewayModel("chosen")
        runCurrent()
        assertTrue(fixture.calls.last().forceRefresh)
        assertTrue(fixture.calls.all { it.profile.uid == "chosen" })
        coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun newChatCanCreateItsDraftWhileTheSelectedModelIsLoading() = withFixture { fixture ->
        fixture.loading = true
        val model = fixture.newChat(listOf("chosen"))
        runCurrent()
        assertTrue(model.gatewayModelPreparation.value.getValue("chosen").busy)
        coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }

        var route: String? = null
        model.start { route = it }
        runCurrent()
        assertEquals("chat_room/42?enabled=chosen&mode=STANDARD", route)
        assertFalse(model.creating.value)
        coVerify(exactly = 1) { fixture.chats.createDraftChat(listOf("chosen"), any(), any(), "chosen") }
    }

    @Test fun homePickerPreparesRestoredSelectionAndClearsItWhenDismissed() = withFixture { fixture ->
        val model = fixture.home()
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        model.openSelectModelDialog()
        runCurrent()
        assertEquals(listOf("chosen"), fixture.calls.map { it.profile.uid })
        assertEquals(ReasoningMode.ON, fixture.calls.single().mode)
        assertEquals(setOf("chosen"), model.gatewayModelPreparation.value.keys)

        fixture.profiles.value = fixture.profiles.value.reversed()
        runCurrent()
        assertEquals(listOf("chosen"), fixture.calls.map { it.profile.uid })
        assertEquals(setOf("chosen"), model.gatewayModelPreparation.value.keys)

        fixture.profiles.value = fixture.profiles.value.map { if (it.uid == "chosen") it.copy(model = "home-revised") else it }
        runCurrent()
        assertEquals("home-revised", fixture.calls.last().profile.model)
        model.closeSelectModelDialog()
        runCurrent()
        assertTrue(model.gatewayModelPreparation.value.isEmpty())
        val before = fixture.calls.size
        fixture.features.value = fixture.features.value.copy(llamaReasoningModes = mapOf("chosen" to ReasoningMode.OFF))
        runCurrent()
        assertEquals(before, fixture.calls.size)

        model.openSelectModelDialog()
        runCurrent()
        assertEquals(ReasoningMode.OFF, fixture.calls.last().mode)
        model.togglePlatformSelection("chosen")
        runCurrent()
        assertTrue(model.gatewayModelPreparation.value.isEmpty())
        assertTrue(fixture.calls.all { it.profile.uid == "chosen" })
        coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun voiceEntryPreparesItsSavedProfileAndTracksTheNewSelectionAndLiveConfiguration() = withFixture { fixture ->
        val model = fixture.voice()
        runCurrent()
        assertEquals("chosen", model.state.value.selected)
        assertEquals(listOf("chosen"), fixture.calls.map { it.profile.uid })
        assertEquals(ReasoningMode.ON, fixture.calls.single().mode)

        model.select("other")
        runCurrent()
        assertEquals(setOf("other"), model.gatewayModelPreparation.value.keys)
        fixture.profiles.value = fixture.profiles.value.map { if (it.uid == "other") it.copy(model = "voice-revised") else it }
        runCurrent()
        assertEquals("voice-revised", fixture.calls.last().profile.model)
        fixture.features.value = fixture.features.value.copy(llamaReasoningModes = mapOf("other" to ReasoningMode.OFF))
        runCurrent()
        assertEquals(ReasoningMode.OFF, fixture.calls.last().mode)
        fixture.profiles.value = fixture.profiles.value.map { if (it.uid == "other") it.copy(enabled = false) else it }
        runCurrent()
        assertTrue(model.gatewayModelPreparation.value.isEmpty())
        val before = fixture.calls.size
        model.select("remote")
        runCurrent()
        assertEquals(before, fixture.calls.size)
        assertTrue(model.gatewayModelPreparation.value.isEmpty())
        assertNull(model.state.value.route)
        coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }
    }

    @Test fun voiceEntryKeepsExplicitCreationAvailableDuringModelLoading() = withFixture { fixture ->
        fixture.loading = true
        val model = fixture.voice()
        runCurrent()
        assertTrue(model.gatewayModelPreparation.value.getValue("chosen").busy)
        model.start()
        runCurrent()
        assertNull(model.state.value.route)
        coVerify(exactly = 0) { fixture.chats.createDraftChat(any(), any(), any(), any()) }

        model.start(enable = true)
        runCurrent()
        assertNotNull(model.state.value.route)
        model.start(enable = true)
        runCurrent()
        coVerify(exactly = 1) { fixture.chats.createDraftChat(listOf("chosen"), any(), any(), "chosen") }
    }

    private fun TestScope.collectProfiles(model: NewChatViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.profiles.collect() }
    }

    private fun withFixture(test: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.views.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private data class PreparationCall(val profile: PlatformV2, val mode: ReasoningMode, val forceRefresh: Boolean)

    private class Fixture {
        val views = ViewModelStore()
        val profiles = MutableStateFlow(
            listOf(
                PlatformV2(uid = "chosen", name = "Chosen", compatibleType = ClientType.LLAMA, model = "model-chosen", reasoning = true),
                PlatformV2(uid = "other", name = "Other", compatibleType = ClientType.LLAMA, model = "model-other", reasoning = true),
                PlatformV2(uid = "remote", name = "Remote", compatibleType = ClientType.OPENAI, model = "model-remote")
            )
        )
        val features = MutableStateFlow(
            AppFeatureSettings(voiceProfileUid = "chosen", lastChatProfileUids = listOf("chosen"), llamaReasoningModes = mapOf("chosen" to ReasoningMode.ON))
                .withToolPluginEnabled(ToolPluginId.VOICE_CHAT, false)
        )
        val settings = mockk<SettingRepository>(relaxed = true)
        val chats = mockk<ChatRepository>(relaxed = true)
        val service = mockk<GatewayModelPreparationRepository>()
        val calls = mutableListOf<PreparationCall>()
        var loading = false

        init {
            every { settings.observePlatformV2s() } returns profiles
            every { settings.observeFeatureSettings() } returns features
            every { settings.observeFavoriteGroups() } returns flowOf(listOf("Starred"))
            every { settings.observeFavoriteMessageGroups() } returns flowOf(emptyMap())
            coEvery { settings.fetchPlatformV2s() } answers { profiles.value }
            coEvery { settings.getFeatureSettings() } answers { features.value }
            coEvery { settings.providerActivationRequired(any()) } returns false
            coEvery { settings.mutateFeatureSettings(any()) } answers {
                val transform = firstArg<(AppFeatureSettings) -> AppFeatureSettings>()
                transform(features.value).also { features.value = it }
            }
            every { chats.observeFavoriteAssistantMessages() } returns flowOf(emptyList())
            coEvery { chats.createDraftChat(any(), any(), any(), any()) } returns ChatRoomV2(id = 42, title = "Draft", enabledPlatform = listOf("chosen"), activePlatform = listOf("chosen"))
            coEvery { service.prepareSelected(any(), any(), any(), any()) } coAnswers {
                val profile = firstArg<PlatformV2>()
                calls += PreparationCall(profile, secondArg(), thirdArg())
                val state = GatewayModelPreparationState(profile.uid, profile.model, if (loading) GatewayModelPreparationPhase.LOADING else GatewayModelPreparationPhase.READY, if (loading) "Loading selected model" else "Selected model ready")
                arg<(GatewayModelPreparationState?) -> Unit>(3)(state)
                if (loading) awaitCancellation()
                state
            }
        }

        fun newChat(uids: List<String>) = NewChatViewModel(
            SavedStateHandle(mapOf("new_chat_type" to if (uids.size == 1) "SINGLE" else "MULTI_CHAT", "new_chat_uids" to uids)),
            settings,
            chats,
            mockk<GatewayAvailabilityRepository>(),
            service
        ).also { views.put("new-chat", it) }

        fun voice() = VoiceEntryViewModel(SavedStateHandle(), settings, chats, service).also { views.put("voice", it) }

        fun home(): HomeViewModel {
            val coordinator = mockk<AgentRunCoordinator>(relaxed = true)
            every { coordinator.activeRuns } returns MutableStateFlow(emptyMap())
            val folders = mockk<ConversationFolderRepository>(relaxed = true)
            every { folders.folders } returns flowOf(emptyList())
            every { folders.members } returns flowOf(emptyList())
            val completions = mockk<GenerationCompletionStore>(relaxed = true)
            every { completions.items } returns MutableStateFlow(emptyList())
            val pinned = mockk<PinnedConversationOrderStore>(relaxed = true)
            every { pinned.order } returns MutableStateFlow(emptyList())
            return HomeViewModel(chats, settings, coordinator, mockk(relaxed = true), mockk(relaxed = true), completions, folders, pinned, gatewayPreparation = service)
                .also { views.put("home", it) }
        }
    }
}
