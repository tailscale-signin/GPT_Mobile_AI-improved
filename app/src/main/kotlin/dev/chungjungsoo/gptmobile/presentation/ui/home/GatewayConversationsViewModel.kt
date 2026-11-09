package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GatewayChatBoundaryUnavailable
import dev.chungjungsoo.gptmobile.data.github.GatewayChatCreator
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationPage
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayHistoryUnsupported
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayKnownProfileStore
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedChatState
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class GatewayBrowserError { CONNECTION, OPEN, UNSUPPORTED, IDENTITY_CHANGED }

internal data class GatewayConversationsState(
    val connectionUid: String? = null,
    val routeIdentity: String? = null,
    val page: GatewayConversationPage? = null,
    val loading: Boolean = false,
    val openingId: String? = null,
    val lastSyncedAt: Long? = null,
    val error: GatewayBrowserError? = null,
    val transcriptKey: String? = null,
    val cachedInventory: Boolean = false,
    val localChats: List<ChatRoomV2> = emptyList(),
    val profileChoices: List<GatewayChatProfileChoice> = emptyList(),
    val profileChoicesLoaded: Boolean = false,
    val profilePickerVisible: Boolean = false,
    val creatingChat: Boolean = false,
    val creationError: String? = null,
    val queuedCounts: Map<Int, Int> = emptyMap(),
    val activeChatIds: Set<Int> = emptySet(),
    val gatewayStates: Map<Int, GatewayQueuedChatState> = emptyMap(),
    val localConversationKeys: Set<String> = emptySet(),
    val localConversationChats: Map<String, Int> = emptyMap()
) {
    /** A visible local mirror already opens this same server conversation and queued job. */
    val unmirroredConversations: List<GatewayConversationSnapshot> get() {
        val visibleLocalIds = localChats.map { it.id }.toSet()
        return page?.let { inventory ->
            inventory.items.filter { conversation ->
                localConversationChats[gatewayIdentityKey(inventory.gatewayId, inventory.principalId, conversation.conversationId)] !in visibleLocalIds
            }
        }.orEmpty()
    }
}

@HiltViewModel
internal class GatewayConversationsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val settings: SettingRepository,
    private val jobs: GatewayJobRepository,
    private val conversations: GatewayConversationRepository,
    private val scopes: GatewayVerifiedScopeStore,
    monitor: GatewayMenuMonitor,
    private val knownProfiles: GatewayKnownProfileStore,
    private val chatCreator: GatewayChatCreator,
    database: ChatDatabaseV2,
    queue: DurablePromptQueue
) : ViewModel() {
    val connections = monitor.connections
    private val _state = MutableStateFlow(GatewayConversationsState(connectionUid = savedState["gateway_browser_profile"]))
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var openJob: Job? = null
    private var creationJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            database.chatRoomDao().observeGatewayChats().collect { rooms -> _state.update { it.copy(localChats = rooms) } }
        }
        viewModelScope.launch {
            database.gatewayJobDao().observeAttachedConversations().collect { records ->
                _state.update {
                    it.copy(
                        localConversationKeys = records.map { record -> record.key }.toSet(),
                        localConversationChats = records.mapNotNull { record -> record.localChatId?.let { chatId -> record.key to chatId } }.toMap()
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(settings.observePlatformV2s(), connections, scopes.changes, knownProfiles.changes) { profiles, connections, _, _ ->
                choices(profiles, connections)
            }.collect { choices -> _state.update { it.copy(profileChoices = choices, profileChoicesLoaded = true) } }
        }
        viewModelScope.launch {
            combine(queue.pending, database.agentRunDao().observeActive(), queue.gatewayStates) { prompts, runs, gatewayStates ->
                Triple(prompts.groupingBy { it.chatId }.eachCount(), runs.map { it.chatId }.toSet(), gatewayStates)
            }.collect { (counts, active, gatewayStates) ->
                _state.update { it.copy(queuedCounts = counts, activeChatIds = active, gatewayStates = gatewayStates) }
            }
        }
    }

    fun newChat(onOpenLocal: (ChatRoomV2) -> Unit) {
        if (_state.value.creatingChat) return
        _state.update { it.copy(creatingChat = true, profilePickerVisible = false, creationError = null) }
        creationJob = viewModelScope.launch {
            try {
                val choices = withContext(Dispatchers.IO) { choices(settings.fetchPlatformV2s(), connections.value) }
                _state.update { it.copy(profileChoices = choices, profileChoicesLoaded = true) }
                when (choices.size) {
                    0 -> _state.update { it.copy(creationError = "No enabled profile has confirmed gateway support. Configure a gateway profile in Settings and let its connection check finish.") }
                    1 -> create(choices.single(), onOpenLocal)
                    else -> _state.update { it.copy(profilePickerVisible = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(creationError = "The gateway profiles could not be loaded. Check Settings and try again.") }
            } finally {
                _state.update { it.copy(creatingChat = false) }
            }
        }
    }

    fun chooseProfile(profileUid: String, onOpenLocal: (ChatRoomV2) -> Unit) {
        if (_state.value.creatingChat) return
        val choice = _state.value.profileChoices.singleOrNull { it.profileUid == profileUid } ?: return
        _state.update { it.copy(creatingChat = true, creationError = null) }
        creationJob = viewModelScope.launch {
            try {
                create(choice, onOpenLocal)
            } finally {
                _state.update { it.copy(creatingChat = false) }
            }
        }
    }

    fun dismissProfilePicker() {
        _state.update { it.copy(profilePickerVisible = false) }
    }

    fun clearCreationError() {
        _state.update { it.copy(creationError = null) }
    }

    private suspend fun choices(profiles: List<PlatformV2>, connections: List<GatewayMenuConnection>): List<GatewayChatProfileChoice> {
        val known = profiles.filter { profile -> knownProfiles.remembered(profile) }.map { it.uid }.toSet()
        val revisions = profiles.mapNotNull { profile -> scopes.configurationRevision(profile)?.let { profile.uid to it } }.toMap()
        return GatewayChatProfiles.choices(profiles, known, connections, revisions)
    }

    private suspend fun create(choice: GatewayChatProfileChoice, onOpenLocal: (ChatRoomV2) -> Unit) {
        try {
            val room = withContext(Dispatchers.IO) { chatCreator.create(choice.selection) }
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(profilePickerVisible = false, creationError = null) }
            onOpenLocal(room)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(creationError = "The gateway chat could not be created with that saved profile. Check its connection settings and choose it again.") }
        }
    }

    private fun resetHistory(connectionUid: String?, routeIdentity: String? = null) {
        _state.update {
            it.copy(
                connectionUid = connectionUid,
                routeIdentity = routeIdentity,
                page = null,
                loading = false,
                openingId = null,
                lastSyncedAt = null,
                error = null,
                transcriptKey = null,
                cachedInventory = false
            )
        }
    }

    fun select(connection: GatewayMenuConnection) {
        if (_state.value.connectionUid == connection.profileUid && _state.value.routeIdentity == connection.navigationIdentity) return
        generation++
        refreshJob?.cancel()
        openJob?.cancel()
        savedState["gateway_browser_profile"] = connection.profileUid
        resetHistory(connection.profileUid, connection.navigationIdentity)
        refresh()
    }

    fun updateConnections(connections: List<GatewayMenuConnection>) {
        val selected = connections.firstOrNull { it.profileUid == _state.value.connectionUid }
            ?: connections.firstOrNull { it.canBrowseHistory } ?: connections.firstOrNull()
        if (selected != null) {
            select(selected)
        } else if (_state.value.routeIdentity != null || _state.value.page != null) {
            generation++
            refreshJob?.cancel()
            openJob?.cancel()
            resetHistory(_state.value.connectionUid)
        }
    }

    fun refresh(loadMore: Boolean = false) {
        val previous = _state.value
        val uid = previous.connectionUid ?: return
        if (refreshJob?.isActive == true || previous.openingId != null) return
        val cursor = if (loadMore) previous.page?.takeIf { it.hasMore }?.nextCursor ?: return else null
        val requestGeneration = generation
        _state.value = previous.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                if (previous.page == null && cursor == null) {
                    val cached = try {
                        withContext(Dispatchers.IO) { jobs.cachedConversations(uid) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (requestGeneration != generation) return@launch
                    if (cached != null) _state.value = _state.value.copy(page = cached.page, lastSyncedAt = cached.lastSyncedAtMillis, cachedInventory = true)
                }
                val page = withContext(Dispatchers.IO) { jobs.listConversations(uid, cursor) }
                if (requestGeneration != generation) return@launch
                val merged = if (cursor != null && previous.page != null) {
                    if (page.gatewayId != previous.page.gatewayId || page.principalId != previous.page.principalId) {
                        _state.value = _state.value.copy(page = null, lastSyncedAt = null, loading = false, error = GatewayBrowserError.IDENTITY_CHANGED)
                        return@launch
                    }
                    page.copy(items = (previous.page.items + page.items).distinctBy { it.conversationId })
                } else {
                    page
                }
                _state.value = _state.value.copy(page = merged, lastSyncedAt = System.currentTimeMillis(), loading = false, error = null, cachedInventory = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GatewayHistoryUnsupported) {
                if (requestGeneration == generation) _state.value = _state.value.copy(loading = false, error = GatewayBrowserError.UNSUPPORTED, cachedInventory = _state.value.page != null)
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.value = _state.value.copy(loading = false, error = GatewayBrowserError.CONNECTION, cachedInventory = _state.value.page != null)
            } finally {
                if (requestGeneration == generation) _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun open(conversation: GatewayConversationSnapshot, onOpen: (GatewayConversationAttachment) -> Unit) {
        val current = _state.value
        val uid = current.connectionUid ?: return
        val page = current.page ?: return
        val key = gatewayIdentityKey(page.gatewayId, page.principalId, conversation.conversationId)
        if (current.openingId != null || (!conversation.transcriptAvailable && key !in current.localConversationKeys) || page.items.none { it.conversationId == conversation.conversationId }) return
        refreshJob?.cancel()
        val requestGeneration = generation
        _state.value = current.copy(openingId = conversation.conversationId, loading = false, error = null)
        openJob = viewModelScope.launch {
            try {
                val (attachment, blocker) = withContext(Dispatchers.IO) {
                    val attached = chatCreator.localAttachment(uid, page.gatewayId, page.principalId, conversation.conversationId)
                        ?: conversations.openConversation(uid, page.gatewayId, page.principalId, conversation.conversationId)
                    val adoption = try {
                        chatCreator.adopt(attached.chatId, attached.profileUid)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (!chatCreator.hasQueueBoundary(attached.chatId)) throw GatewayChatBoundaryUnavailable()
                        dev.chungjungsoo.gptmobile.data.github.GatewayChatAdoption(
                            ChatRoomV2(id = attached.chatId, title = "Retained Gateway chat", conversationMode = dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.GATEWAY),
                            "The saved Gateway queue binding could not be restored. Its retained transcript remains readable."
                        )
                    }
                    attached.copy(conversationMode = adoption.room.conversationMode) to adoption.blocker
                }
                if (requestGeneration == generation) {
                    _state.update { it.copy(creationError = blocker) }
                    onOpen(attachment)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (blocked: GatewayChatBoundaryUnavailable) {
                if (requestGeneration == generation) _state.update { it.copy(error = GatewayBrowserError.OPEN, creationError = blocked.message) }
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.value = _state.value.copy(error = GatewayBrowserError.OPEN)
            } finally {
                if (requestGeneration == generation) _state.value = _state.value.copy(openingId = null)
            }
        }
    }

    fun openLocalChat(room: ChatRoomV2, onOpenLocal: (ChatRoomV2) -> Unit) {
        if (_state.value.openingId != null) return
        refreshJob?.cancel()
        creationJob?.cancel()
        val requestGeneration = generation
        _state.update { it.copy(openingId = "local:${room.id}", loading = false, creationError = null) }
        openJob = viewModelScope.launch {
            try {
                val adopted = withContext(Dispatchers.IO) { chatCreator.adopt(room.id) }
                if (requestGeneration == generation) {
                    _state.update { it.copy(creationError = adopted.blocker) }
                    onOpenLocal(adopted.room)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) {
                    val boundarySaved = withContext(Dispatchers.IO) { chatCreator.hasQueueBoundary(room.id) }
                    if (requestGeneration != generation) return@launch
                    if (boundarySaved) {
                        _state.update { it.copy(creationError = "The saved Gateway queue binding could not be restored. You can read this conversation; restore its original profile before adding a message.") }
                        onOpenLocal(room.copy(conversationMode = dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.GATEWAY, combinedPrimaryUid = null))
                    } else {
                        _state.update { it.copy(creationError = GatewayChatBoundaryUnavailable().message) }
                    }
                }
            } finally {
                if (requestGeneration == generation) _state.update { it.copy(openingId = null) }
            }
        }
    }

    fun showTranscript(conversation: GatewayConversationSnapshot) {
        val current = _state.value
        val uid = current.connectionUid ?: return
        val page = current.page ?: return
        if (current.openingId != null || !conversation.transcriptAvailable || page.items.none { it.conversationId == conversation.conversationId }) return
        refreshJob?.cancel()
        val requestGeneration = generation
        _state.value = current.copy(openingId = conversation.conversationId, loading = false, error = null)
        openJob = viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { conversations.prepareTranscript(uid, page.gatewayId, page.principalId, conversation.conversationId) }
                if (requestGeneration == generation) _state.value = _state.value.copy(transcriptKey = key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.value = _state.value.copy(transcriptKey = gatewayIdentityKey(page.gatewayId, page.principalId, conversation.conversationId), error = GatewayBrowserError.OPEN)
            } finally {
                if (requestGeneration == generation) _state.value = _state.value.copy(openingId = null)
            }
        }
    }

    fun dismissTranscript() {
        _state.value = _state.value.copy(transcriptKey = null)
    }

    fun pause() {
        generation++
        refreshJob?.cancel()
        refreshJob = null
        openJob?.cancel()
        openJob = null
        creationJob?.cancel()
        creationJob = null
        _state.value = _state.value.copy(loading = false, openingId = null)
    }
}
