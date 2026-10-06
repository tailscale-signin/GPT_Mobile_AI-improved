package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.domain.usecase.ManagePlatformsUseCase
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeTab {
    CHATS,
    FAVORITES
}

internal fun automaticArchiveTargets(
    chats: List<ChatRoomV2>,
    activeIds: Set<Int>,
    maxVisible: Int = 20
): List<ChatRoomV2> {
    val overflow = (chats.size - maxVisible).coerceAtLeast(0)
    if (overflow == 0) return emptyList()

    return chats
        .filter { !it.isFavorite && it.id !in activeIds }
        .sortedWith(compareBy<ChatRoomV2> { it.updatedAt }.thenBy { it.id })
        .take(overflow * 2)
        .filterIndexed { index, _ -> index % 2 == 0 }
        .take(overflow)
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val agentRunCoordinator: AgentRunCoordinator,
    private val managePlatformsUseCase: ManagePlatformsUseCase,
    private val conversationReadStateStore: dev.chungjungsoo.gptmobile.data.chat.ConversationReadStateStore,
    private val completionStore: GenerationCompletionStore,
    private val pinnedOrderStore: dev.chungjungsoo.gptmobile.data.chat.PinnedConversationOrderStore
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MAX_MAIN_CHAT_COUNT = 20
        const val GROUP_ALL = "All"
        private const val FAVORITE_GROUPS_INITIALIZED = "__favorite_groups_initialized__"
        private const val SELECTED_GROUP_PREFIX = "__selected_favorite_group__:"
        val DEFAULT_GROUPS = listOf(GROUP_ALL, "Starred", "Work", "Personal")
    }

    data class ChatListState(
        val chats: List<ChatRoomV2> = listOf(),
        val isSelectionMode: Boolean = false,
        val isSearchMode: Boolean = false,
        val selectedPlatforms: List<Boolean> = listOf(),
        val selectedChats: List<Boolean> = listOf()
    )

    private val _currentTab = MutableStateFlow(HomeTab.CHATS)
    val currentTab = _currentTab.asStateFlow()

    private val _chatListState = MutableStateFlow(ChatListState())
    val chatListState: StateFlow<ChatListState> = _chatListState.asStateFlow()

    private val _platformState = MutableStateFlow(listOf<PlatformV2>())
    val platformState = _platformState.asStateFlow()

    private val _archivedChats = MutableStateFlow<List<ChatRoomV2>>(emptyList())
    val archivedChats = _archivedChats.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _favoriteSearchQuery = MutableStateFlow("")
    val favoriteSearchQuery = _favoriteSearchQuery.asStateFlow()

    private val rawFavoriteMessagesState = MutableStateFlow<List<MessageV2>>(emptyList())

    private val _favoriteGroups = MutableStateFlow<List<String>>(DEFAULT_GROUPS)
    val favoriteGroups = _favoriteGroups.asStateFlow()

    private val _selectedFavoriteGroup = MutableStateFlow(GROUP_ALL)
    val selectedFavoriteGroup = _selectedFavoriteGroup.asStateFlow()

    private val _messageGroups = MutableStateFlow<Map<Int, String>>(emptyMap())
    val messageGroups = _messageGroups.asStateFlow()

    val favoriteMessages: StateFlow<List<MessageV2>> = combine(
        rawFavoriteMessagesState,
        _selectedFavoriteGroup,
        _messageGroups
    ) { rawFavorites, selectedGroup, msgGroups ->
        if (selectedGroup == GROUP_ALL) {
            rawFavorites
        } else {
            rawFavorites.filter { message ->
                msgGroups[message.id] == selectedGroup
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showSelectModelDialog = MutableStateFlow(false)
    val showSelectModelDialog: StateFlow<Boolean> = _showSelectModelDialog.asStateFlow()

    private val _showDeleteWarningDialog = MutableStateFlow(false)
    val showDeleteWarningDialog: StateFlow<Boolean> = _showDeleteWarningDialog.asStateFlow()

    private val _activeChatIds = MutableStateFlow<Set<Int>>(emptySet())
    val activeChatIds = _activeChatIds.asStateFlow()
    val unreadChatIds = conversationReadStateStore.unreadChatIds
    val completedGenerations = completionStore.items
    private var pinnedOrder = pinnedOrderStore.read()

    private fun sortChats(chats: List<ChatRoomV2>, activeIds: Set<Int> = _activeChatIds.value): List<ChatRoomV2> =
        chats.sortedWith(
            compareByDescending<ChatRoomV2> { it.isFavorite }
                .thenBy { if (it.isFavorite) pinnedOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE else Int.MAX_VALUE }
                .thenByDescending { activeIds.contains(it.id) }
                .thenByDescending { it.updatedAt }
        )

    init {
        // Set up debounced search for chats
        _searchQuery
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { query -> searchChats(query) }
            .launchIn(viewModelScope)

        // Set up debounced search / observation for favorite messages
        _favoriteSearchQuery
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .flatMapLatest { query ->
                if (query.isBlank()) {
                    chatRepository.observeFavoriteAssistantMessages()
                } else {
                    chatRepository.searchFavoriteAssistantMessages(query)
                }
            }
            .onEach { favorites -> rawFavoriteMessagesState.update { favorites } }
            .launchIn(viewModelScope)

        // Observe persisted favorite groups and message-to-group mappings
        settingRepository.observeFavoriteGroups()
            .onEach { savedGroups ->
                val merged = if (savedGroups.isEmpty()) {
                    viewModelScope.launch {
                        settingRepository.saveFavoriteGroups(
                            DEFAULT_GROUPS.filter { it != GROUP_ALL } + FAVORITE_GROUPS_INITIALIZED
                        )
                    }
                    DEFAULT_GROUPS
                } else {
                    (listOf(GROUP_ALL) + savedGroups.filter { it != FAVORITE_GROUPS_INITIALIZED && !it.startsWith(SELECTED_GROUP_PREFIX) }).distinct()
                }
                _favoriteGroups.update { merged }
                savedGroups.firstOrNull { it.startsWith(SELECTED_GROUP_PREFIX) }
                    ?.removePrefix(SELECTED_GROUP_PREFIX)
                    ?.takeIf { it in merged }
                    ?.let { _selectedFavoriteGroup.value = it }
            }
            .launchIn(viewModelScope)

        settingRepository.observeFavoriteMessageGroups()
            .onEach { savedMappings ->
                _messageGroups.update { savedMappings }
            }
            .launchIn(viewModelScope)

        agentRunCoordinator.activeRuns
            .onEach { runs ->
                val newActiveIds = runs.values.mapTo(mutableSetOf()) { it.chatId }
                val finishedIds = _activeChatIds.value - newActiveIds
                finishedIds.forEach(conversationReadStateStore::markUnread)
                _activeChatIds.update { newActiveIds }
                _chatListState.update { current ->
                    if (!current.isSelectionMode) {
                        current.copy(chats = sortChats(current.chats, newActiveIds))
                    } else {
                        current
                    }
                }
            }
            .launchIn(viewModelScope)

        fetchArchivedChats()
    }

    fun selectTab(tab: HomeTab) {
        _currentTab.update { tab }
        disableSelectionMode()
    }

    fun updateFavoriteSearchQuery(query: String) {
        _favoriteSearchQuery.update { query }
    }

    fun selectFavoriteGroup(group: String) {
        if (group !in _favoriteGroups.value) return
        _selectedFavoriteGroup.update { group }
        persistFavoriteGroups(_favoriteGroups.value)
    }

    private fun persistFavoriteGroups(groups: List<String>) {
        viewModelScope.launch {
            settingRepository.saveFavoriteGroups(
                groups.filter { it != GROUP_ALL } +
                    FAVORITE_GROUPS_INITIALIZED +
                    (SELECTED_GROUP_PREFIX + _selectedFavoriteGroup.value)
            )
        }
    }

    fun addFavoriteGroup(newGroup: String) {
        val trimmed = newGroup.trim()
        if (trimmed.isNotEmpty() && !_favoriteGroups.value.contains(trimmed)) {
            val updated = _favoriteGroups.value + trimmed
            _favoriteGroups.update { updated }
            _selectedFavoriteGroup.update { trimmed }
            persistFavoriteGroups(updated)
        }
    }

    fun assignFavoriteMessageGroup(messageId: Int, groupName: String?) {
        val updated = if (groupName != null) {
            _messageGroups.value + (messageId to groupName)
        } else {
            _messageGroups.value - messageId
        }
        _messageGroups.update { updated }
        viewModelScope.launch {
            settingRepository.saveFavoriteMessageGroups(updated)
        }
    }

    fun renameFavoriteGroup(groupName: String, newName: String) {
        val normalized = newName.trim()
        if (groupName == GROUP_ALL || normalized.isBlank() || normalized == groupName) return
        if (_favoriteGroups.value.any { it.equals(normalized, ignoreCase = true) }) return

        val updatedGroups = _favoriteGroups.value.map { if (it == groupName) normalized else it }
        val updatedMappings = _messageGroups.value.mapValues { (_, value) ->
            if (value == groupName) normalized else value
        }
        _favoriteGroups.value = updatedGroups
        _messageGroups.value = updatedMappings
        if (_selectedFavoriteGroup.value == groupName) {
            _selectedFavoriteGroup.value = normalized
        }
        persistFavoriteGroups(updatedGroups)
        viewModelScope.launch {
            settingRepository.saveFavoriteMessageGroups(updatedMappings)
        }
    }

    fun deleteFavoriteGroup(groupName: String) {
        if (groupName == GROUP_ALL) return
        val updatedGroups = _favoriteGroups.value.filterNot { it == groupName }
        // Removing the assignment moves those favorites back to the general "All" view.
        val updatedMappings = _messageGroups.value.filterValues { it != groupName }
        _favoriteGroups.value = updatedGroups
        _messageGroups.value = updatedMappings
        if (_selectedFavoriteGroup.value == groupName) {
            _selectedFavoriteGroup.value = GROUP_ALL
        }
        persistFavoriteGroups(updatedGroups)
        viewModelScope.launch {
            settingRepository.saveFavoriteMessageGroups(updatedMappings)
        }
    }

    fun toggleFavorite(messageId: Int, isFavorite: Boolean) {
        viewModelScope.launch {
            chatRepository.setMessageFavorite(messageId, isFavorite)
        }
    }

    fun toggleChatFavorite(chatId: Int, isFavorite: Boolean) {
        viewModelScope.launch {
            pinnedOrder = if (isFavorite) movePinnedConversation(pinnedOrder, chatId, 0) else pinnedOrder - chatId
            pinnedOrderStore.save(pinnedOrder)
            chatRepository.setChatFavorite(chatId, isFavorite)
            fetchChats()
        }
    }

    fun moveConversationPin(chatId: Int, targetIndex: Int) {
        viewModelScope.launch {
            val currentPins = _chatListState.value.chats.filter { it.isFavorite }.map { it.id }
            pinnedOrder = if (targetIndex < 0) pinnedOrder - chatId else movePinnedConversation((pinnedOrder.filter { it in currentPins } + currentPins).distinct(), chatId, targetIndex)
            pinnedOrderStore.save(pinnedOrder)
            chatRepository.setChatFavorite(chatId, targetIndex >= 0)
            fetchChats()
        }
    }

    fun togglePlatformFavorite(platformId: Int, isFavorite: Boolean) {
        viewModelScope.launch {
            managePlatformsUseCase.toggleFavoritePlatform(platformId, isFavorite)
            fetchPlatformStatus()
        }
    }

    fun updatePlatformCheckedState(idx: Int) {
        if (idx < 0 || idx >= _chatListState.value.selectedPlatforms.size) return

        _chatListState.update {
            it.copy(
                selectedPlatforms = it.selectedPlatforms.mapIndexed { index, b ->
                    if (index == idx) {
                        !b
                    } else {
                        b
                    }
                }
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.update { query }
    }

    private fun searchChats(query: String) {
        viewModelScope.launch {
            val rawChats = chatRepository.searchChatsV2(query)
            val sorted = sortChats(rawChats)
            _chatListState.update {
                it.copy(
                    chats = sorted,
                    selectedChats = List(sorted.size) { false }
                )
            }
        }
    }

    fun openDeleteWarningDialog() {
        closeSelectModelDialog()
        _showDeleteWarningDialog.update { true }
    }

    fun closeDeleteWarningDialog() {
        _showDeleteWarningDialog.update { false }
    }

    fun openSelectModelDialog() {
        _showSelectModelDialog.update { true }
        disableSelectionMode()
    }

    fun closeSelectModelDialog() {
        _showSelectModelDialog.update { false }
        _chatListState.update { it.copy(selectedPlatforms = List(it.selectedPlatforms.size) { false }) }
    }

    fun deleteSelectedChats() {
        viewModelScope.launch {
            val selectedChats = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats.getOrElse(index) { false }
            }

            val chats = agentRunCoordinator.withChatGate(selectedChats.map { it.id }) {
                selectedChats.forEach { agentRunCoordinator.cancelChatAndJoin(it.id) }
                chatRepository.deleteChatsV2(selectedChats)
                chatRepository.fetchChatListV2()
            }
            val sorted = sortChats(chats)
            _chatListState.update { it.copy(chats = sorted) }
            disableSelectionMode()
        }
    }

    fun openConversation(room: ChatRoomV2, onOpen: (ChatRoomV2, Int?) -> Unit) {
        viewModelScope.launch {
            val target = if (room.id in conversationReadStateStore.unreadChatIds.value) {
                chatRepository.newestAssistantMessageId(room.id)
            } else {
                null
            }
            markChatViewed(room.id)
            onOpen(room, target)
        }
    }

    fun markChatViewed(chatId: Int) {
        conversationReadStateStore.markViewed(chatId)
        completionStore.clearChat(chatId)
    }

    fun consumeCompletedGeneration(runId: String) {
        completionStore.consume(runId)
    }

    fun deleteChat(chatRoom: ChatRoomV2) {
        conversationReadStateStore.remove(chatRoom.id)
        completionStore.clearChat(chatRoom.id)
        viewModelScope.launch {
            agentRunCoordinator.withChatGate(chatRoom.id) {
                agentRunCoordinator.cancelChatAndJoin(chatRoom.id)
                chatRepository.deleteChatsV2(listOf(chatRoom))
            }
            fetchChats()
        }
    }

    fun archiveChat(chatRoom: ChatRoomV2) {
        viewModelScope.launch {
            chatRepository.setChatArchived(chatRoom.id, isArchived = true)
            fetchChats()
            fetchArchivedChats()
        }
    }

    fun unarchiveChat(chatRoom: ChatRoomV2) {
        viewModelScope.launch {
            chatRepository.setChatArchived(chatRoom.id, isArchived = false)
            fetchChats()
            fetchArchivedChats()
        }
    }

    fun deleteArchivedChat(chatRoom: ChatRoomV2) {
        conversationReadStateStore.remove(chatRoom.id)
        completionStore.clearChat(chatRoom.id)
        viewModelScope.launch {
            agentRunCoordinator.withChatGate(chatRoom.id) {
                agentRunCoordinator.cancelChatAndJoin(chatRoom.id)
                chatRepository.deleteChatsV2(listOf(chatRoom))
            }
            fetchArchivedChats()
        }
    }

    fun fetchArchivedChats() {
        viewModelScope.launch {
            val archived = chatRepository.fetchArchivedChatListV2()
            _archivedChats.update { archived }
        }
    }

    fun duplicateSelectedChat() {
        viewModelScope.launch {
            val selectedChats = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats.getOrElse(index) { false }
            }
            val selectedChat = selectedChats.singleOrNull() ?: return@launch
            val chats = agentRunCoordinator.withChatGate(selectedChat.id) {
                if (agentRunCoordinator.hasActiveRuns(selectedChat.id)) return@withChatGate null
                chatRepository.duplicateChatV2(selectedChat)
                chatRepository.fetchChatListV2()
            } ?: return@launch
            val sorted = sortChats(chats)
            _chatListState.update { it.copy(chats = sorted) }
            disableSelectionMode()
        }
    }

    fun disableSelectionMode() {
        _chatListState.update {
            it.copy(
                selectedChats = List(it.chats.size) { false },
                isSelectionMode = false
            )
        }
    }

    fun disableSearchMode() {
        _chatListState.update { it.copy(isSearchMode = false) }
        _searchQuery.update { "" }
    }

    fun enableSelectionMode() {
        disableSearchMode()
        _chatListState.update { it.copy(isSelectionMode = true) }
    }

    fun enableSearchMode() {
        disableSelectionMode()
        _chatListState.update { it.copy(isSearchMode = true) }
    }

    fun fetchChats() {
        viewModelScope.launch {
            var rawChats = chatRepository.fetchChatListV2()

            // Keep the main screen manageable without aggressively hiding history.
            // Once more than 20 chats are visible, archive alternating oldest eligible
            // conversations until the visible count returns to 20. Pinned/favorite and
            // actively generating chats are never selected for automatic archival.
            if (rawChats.size > MAX_MAIN_CHAT_COUNT) {
                val archiveTargets = automaticArchiveTargets(
                    chats = rawChats,
                    activeIds = _activeChatIds.value,
                    maxVisible = MAX_MAIN_CHAT_COUNT
                )
                archiveTargets.forEach { chatRepository.setChatArchived(it.id, isArchived = true) }
                if (archiveTargets.isNotEmpty()) {
                    rawChats = chatRepository.fetchChatListV2()
                }
            }

            val sorted = sortChats(rawChats)
            _chatListState.update {
                it.copy(
                    chats = sorted,
                    selectedChats = List(sorted.size) { false },
                    isSelectionMode = false
                )
            }
            fetchArchivedChats()

            Log.d("chats", "${_chatListState.value.chats}")
        }
    }

    fun getChatRoom(chatId: Int, onResult: (ChatRoomV2?) -> Unit) {
        val inMemory = _chatListState.value.chats.find { it.id == chatId }
            ?: _archivedChats.value.find { it.id == chatId }
        if (inMemory != null) {
            onResult(inMemory)
            return
        }
        viewModelScope.launch {
            val allChats = chatRepository.fetchChatListV2() + chatRepository.fetchArchivedChatListV2()
            onResult(allChats.find { it.id == chatId })
        }
    }

    fun fetchPlatformStatus() {
        viewModelScope.launch {
            val platforms = settingRepository.fetchPlatformV2s()
            _platformState.update { platforms }

            if (_chatListState.value.selectedPlatforms.size != platforms.size) {
                _chatListState.update { it.copy(selectedPlatforms = List(platforms.size) { false }) }
            }
        }
    }

    fun selectChat(chatRoomIdx: Int) {
        if (chatRoomIdx < 0 || chatRoomIdx >= _chatListState.value.chats.size) return

        _chatListState.update {
            it.copy(
                selectedChats = it.selectedChats.mapIndexed { index, b ->
                    if (index == chatRoomIdx) {
                        !b
                    } else {
                        b
                    }
                }
            )
        }

        if (_chatListState.value.selectedChats.count { it } == 0) {
            disableSelectionMode()
        }
    }
}
