package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.NewChatType
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.researchStartBlockReason
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shared by both full-screen creation destinations; selecting models never generates text. */
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val settings: SettingRepository,
    private val chats: ChatRepository
) : ViewModel() {
    val profiles = settings.observePlatformV2s()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val features = settings.observeFeatureSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppFeatureSettings())
    val typeName = savedStateHandle.getStateFlow("new_chat_type", "")
    val selectedUids = savedStateHandle.getStateFlow("new_chat_uids", emptyList<String>())
    val primaryUid = savedStateHandle.getStateFlow("new_chat_primary", "")
    val researchDepth = savedStateHandle.getStateFlow("new_chat_research_depth", 50)
    private val _creating = MutableStateFlow(false)
    val creating = _creating.asStateFlow()
    private val _approvingResearch = MutableStateFlow(false)
    val approvingResearch = _approvingResearch.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val type: NewChatType?
        get() = NewChatType.entries.firstOrNull { it.name == typeName.value }

    fun chooseType(choice: NewChatType) {
        savedStateHandle["new_chat_type"] = choice.name
        val enabled = profiles.value.filter { it.enabled }.map { it.uid }.toSet()
        val kept = selectedUids.value.filter { it in enabled }.let {
            if (choice == NewChatType.SINGLE) it.take(1) else it
        }
        savedStateHandle["new_chat_uids"] = kept
        if (primaryUid.value !in kept) savedStateHandle["new_chat_primary"] = kept.firstOrNull().orEmpty()
        clearError()
    }

    fun toggleProfile(uid: String) {
        val profile = profiles.value.firstOrNull { it.uid == uid && it.enabled } ?: return
        val current = selectedUids.value
        val next = if (type == NewChatType.SINGLE) {
            if (current.singleOrNull() == uid) emptyList() else listOf(uid)
        } else if (uid in current) {
            current - uid
        } else {
            current + profile.uid
        }
        savedStateHandle["new_chat_uids"] = next
        if (primaryUid.value !in next) savedStateHandle["new_chat_primary"] = next.firstOrNull().orEmpty()
        clearError()
    }

    fun setPrimary(uid: String) {
        if (uid in selectedUids.value) savedStateHandle["new_chat_primary"] = uid
    }

    fun setResearchDepth(value: Int) {
        savedStateHandle["new_chat_research_depth"] = value.coerceIn(1, 100)
    }

    fun toggleFavorite(uid: String) {
        val profile = profiles.value.firstOrNull { it.uid == uid } ?: return
        viewModelScope.launch {
            try {
                settings.updatePlatformV2(profile.copy(isFavorite = !profile.isFavorite))
            } catch (_: Exception) {
                _error.value = "Could not update this profile's favorite status."
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** Report independent Research tool gates without silently granting permissions. */
    fun researchBlockers(): List<String> {
        if (type != NewChatType.RESEARCH) return emptyList()
        val selected = selectedUids.value.toSet()
        val selection = researchSelection()
        val current = features.value
        return profiles.value.filter { it.uid in selected }.mapNotNull { profile ->
            researchStartBlockReason(
                selection,
                current,
                profile.uid,
                networkAllowed = !profile.disableAllTools && !profile.disableRemoteTools
            )?.let { profile.name + ": " + it }
        }
    }

    /** Explicit opt-in, not a permission elevation triggered by entering the picker. */
    fun grantWebResearchAccess() {
        if (_approvingResearch.value || type != NewChatType.RESEARCH) return
        val uids = selectedUids.value
        if (uids.isEmpty()) return
        _approvingResearch.value = true
        viewModelScope.launch {
            try {
                settings.mutateFeatureSettings { before ->
                    var updated = before.withToolPluginEnabled(ToolPluginId.DEEP_RESEARCH, true)
                    uids.forEach { uid ->
                        updated = updated.withProfileToolPluginEnabled(uid, ToolPluginId.DEEP_RESEARCH, true)
                    }
                    updated
                }
                clearError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = "Could not save Deep Research permissions. Check Plugins & Tools."
            } finally {
                _approvingResearch.value = false
            }
        }
    }

    private fun researchSelection(): MultiSearchResearchSelection = MultiSearchResearchSelection(
        researchToggle = true,
        depth = researchDepth.value.coerceIn(1, 100),
        scope = ResearchContentScope.WEB_ONLY
    ).normalized()

    fun start(onCreated: (String) -> Unit) {
        if (_creating.value) return
        val finished = savedStateHandle.get<String>("new_chat_finished_route")
        if (!finished.isNullOrBlank()) {
            onCreated(finished)
            return
        }
        val choice = type ?: run {
            _error.value = "Choose a chat type."
            return
        }
        val selected = selectedUids.value.distinct()
        if (!choice.isCountValid(selected.size)) {
            _error.value = when (choice) {
                NewChatType.SINGLE -> "Select exactly one AI profile."
                NewChatType.RESEARCH -> "Select at least one AI profile."
                else -> "Select at least two AI profiles."
            }
            return
        }
        _creating.value = true
        _error.value = null
        viewModelScope.launch {
            val created = mutableListOf<ChatRoomV2>()
            try {
                val available = settings.fetchPlatformV2s().filter { it.enabled }
                require(selected.all { uid -> available.any { it.uid == uid } }) {
                    "A selected AI profile was disabled or deleted. Check your selection."
                }
                if (choice == NewChatType.RESEARCH) {
                    val liveSettings = settings.getFeatureSettings()
                    val selection = researchSelection()
                    selected.forEach { uid ->
                        val profile = available.first { it.uid == uid }
                        val reason = researchStartBlockReason(
                            selection,
                            liveSettings,
                            uid,
                            !profile.disableAllTools && !profile.disableRemoteTools
                        )
                        require(reason == null) { profile.name + ": " + reason }
                    }
                }
                val mode = choice.executionMode(selected.size)
                val nextRoute = if (choice == NewChatType.MULTI_CHAT) {
                    selected.forEach { uid ->
                        val profile = available.first { it.uid == uid }
                        val isRoot = created.isEmpty()
                        val title = if (isRoot) "Multi-chat · " + selected.size + " AIs" else profile.name
                        val room = chats.createDraftChat(listOf(uid), mode, title)
                        created += room
                        if (isRoot) chats.updateChatTitle(room, title, isCustomized = true)
                    }
                    val rootId = created.first().id
                    settings.mutateFeatureSettings {
                        it.copy(
                            multiChatWorkspaces = it.multiChatWorkspaces + (rootId to created.map(ChatRoomV2::id)),
                            lastChatMode = mode,
                            lastChatProfileUids = selected
                        )
                    }
                    "multi_chat_workspace/$rootId"
                } else {
                    val primary = primaryUid.value.takeIf { it in selected } ?: selected.first()
                    val title = when (choice) {
                        NewChatType.RESEARCH -> "Research Chat"
                        NewChatType.COMBINED -> "Combined Chat"
                        else -> "New Chat"
                    }
                    created += chats.createDraftChat(
                        selected,
                        mode,
                        title,
                        primary
                    )
                    val chatId = created.single().id
                    settings.mutateFeatureSettings {
                        if (choice == NewChatType.RESEARCH) {
                            it.copy(
                                conversationResearch = it.conversationResearch + (chatId to researchSelection()),
                                researchChatIds = it.researchChatIds + chatId,
                                lastChatMode = mode,
                                lastChatProfileUids = selected
                            )
                        } else {
                            it.copy(lastChatMode = mode, lastChatProfileUids = selected)
                        }
                    }
                    "chat_room/$chatId?enabled=" + selected.joinToString(",") + "&mode=$mode"
                }
                savedStateHandle["new_chat_finished_route"] = nextRoute
                onCreated(nextRoute)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (created.isNotEmpty()) {
                    val ids = created.mapTo(mutableSetOf()) { it.id }
                    try {
                        settings.mutateFeatureSettings { current ->
                            current.copy(
                                multiChatWorkspaces = current.multiChatWorkspaces.filterKeys { it !in ids },
                                researchChatIds = current.researchChatIds - ids,
                                conversationResearch = current.conversationResearch.filterKeys { it !in ids }
                            )
                        }
                    } catch (_: Exception) {
                        // A failed settings rollback must not overwrite the creation error.
                    }
                    try {
                        chats.deleteChatsV2(created)
                    } catch (_: Exception) {
                        // Keep the original error visible. No model request has started.
                    }
                }
                _error.value = failure.message ?: "Could not create the conversation. Retry."
            } finally {
                _creating.value = false
            }
        }
    }
}
