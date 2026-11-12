package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelSelectionController
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class VoiceEntryState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val profiles: List<PlatformV2> = emptyList(),
    val selected: String = "",
    val creating: Boolean = false,
    val route: String? = null,
    val error: String? = null
)

@HiltViewModel
class VoiceEntryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val settings: SettingRepository,
    private val chats: ChatRepository,
    gatewayPreparation: GatewayModelPreparationRepository? = null
) : ViewModel() {
    private val gatewayModelSelection = GatewayModelSelectionController(gatewayPreparation, viewModelScope)
    val gatewayModelPreparation = gatewayModelSelection.states
    private val mutable = MutableStateFlow(VoiceEntryState())
    val state = mutable.asStateFlow()
    init {
        if (gatewayPreparation != null) {
            combine(settings.observePlatformV2s(), settings.observeFeatureSettings(), state.map { it.selected }.distinctUntilChanged()) { available, features, selected ->
                val profiles = available.filter { it.uid == selected }
                profiles to profiles.associate { it.uid to ReasoningPolicy.preference(it.reasoning, features.llamaReasoningModes[it.uid]) }
            }.distinctUntilChanged().onEach { (profiles, modes) ->
                gatewayModelSelection.update(profiles, modes)
            }.launchIn(viewModelScope)
        }
        viewModelScope.launch {
            try {
                val features = settings.getFeatureSettings()
                val profiles = settings.fetchPlatformV2s().filter { it.enabled }
                val selected = features.voiceProfileUid?.takeIf { uid -> profiles.any { it.uid == uid } }
                    ?: features.lastChatProfileUids.firstOrNull { uid -> profiles.any { it.uid == uid } }
                    ?: profiles.firstOrNull()?.uid.orEmpty()
                mutable.value = VoiceEntryState(true, features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT), profiles, selected, route = savedState["voice_created_route"])
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = VoiceEntryState(loaded = true, error = "Could not load your AI profiles. Open settings and try again.")
            }
        }
    }
    fun select(uid: String) {
        if (!mutable.value.creating && mutable.value.profiles.any { it.uid == uid }) mutable.value = mutable.value.copy(selected = uid)
    }
    fun recheckGatewayModel(uid: String) = gatewayModelSelection.recheck(uid)

    fun start(enable: Boolean = false) {
        val before = mutable.value
        if (!before.loaded || before.creating || before.route != null || before.selected.isBlank() || (!before.enabled && !enable)) return
        mutable.value = before.copy(creating = true, error = null)
        viewModelScope.launch {
            try {
                val features = settings.getFeatureSettings()
                if (!enable && !features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT)) {
                    mutable.value = before.copy(enabled = false)
                    return@launch
                }
                val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == before.selected && it.enabled }
                    ?: error("Choose an enabled AI profile.")
                dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile)?.let { error(it.message) }
                settings.mutateFeatureSettings { it.withToolPluginEnabled(ToolPluginId.VOICE_CHAT, true).copy(voiceProfileUid = profile.uid) }
                val room = chats.createDraftChat(listOf(profile.uid), ConversationMode.STANDARD, "Voice chat", profile.uid)
                val route = "voice_chat/${room.id}?enabled=${android.net.Uri.encode(profile.uid)}"
                savedState["voice_created_route"] = route
                mutable.value = before.copy(enabled = true, route = route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = before.copy(error = error.message ?: "Could not open voice chat. Try again.")
            }
        }
    }
}
