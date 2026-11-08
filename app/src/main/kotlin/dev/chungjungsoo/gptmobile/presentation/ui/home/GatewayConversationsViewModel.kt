package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationAttachment
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationPage
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.GatewayHistoryUnsupported
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    val cachedInventory: Boolean = false
)

@HiltViewModel
internal class GatewayConversationsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    settings: SettingRepository,
    availability: GatewayAvailabilityRepository,
    private val jobs: GatewayJobRepository,
    private val conversations: GatewayConversationRepository,
    scopes: GatewayVerifiedScopeStore
) : ViewModel() {
    val connections = gatewayMenuConnections(settings, availability, scopes)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), emptyList())
    private val _state = MutableStateFlow(GatewayConversationsState(connectionUid = savedState["gateway_browser_profile"]))
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var openJob: Job? = null
    private var generation = 0L

    fun select(connection: GatewayMenuConnection) {
        if (_state.value.connectionUid == connection.profileUid && _state.value.routeIdentity == connection.navigationIdentity) return
        generation++
        refreshJob?.cancel()
        openJob?.cancel()
        savedState["gateway_browser_profile"] = connection.profileUid
        _state.value = GatewayConversationsState(connectionUid = connection.profileUid, routeIdentity = connection.navigationIdentity)
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
            _state.value = GatewayConversationsState(connectionUid = _state.value.connectionUid)
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
                if (requestGeneration == generation) _state.value = _state.value.copy(loading = false, error = GatewayBrowserError.UNSUPPORTED)
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.value = _state.value.copy(loading = false, error = GatewayBrowserError.CONNECTION)
            } finally {
                if (requestGeneration == generation) _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun open(conversation: GatewayConversationSnapshot, onOpen: (GatewayConversationAttachment) -> Unit) {
        val current = _state.value
        val uid = current.connectionUid ?: return
        val page = current.page ?: return
        if (current.openingId != null || !conversation.transcriptAvailable || page.items.none { it.conversationId == conversation.conversationId }) return
        refreshJob?.cancel()
        val requestGeneration = generation
        _state.value = current.copy(openingId = conversation.conversationId, loading = false, error = null)
        openJob = viewModelScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) {
                    conversations.openConversation(uid, page.gatewayId, page.principalId, conversation.conversationId)
                }
                if (requestGeneration == generation) onOpen(attachment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == generation) _state.value = _state.value.copy(error = GatewayBrowserError.OPEN)
            } finally {
                if (requestGeneration == generation) _state.value = _state.value.copy(openingId = null)
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
        _state.value = _state.value.copy(loading = false, openingId = null)
    }
}
