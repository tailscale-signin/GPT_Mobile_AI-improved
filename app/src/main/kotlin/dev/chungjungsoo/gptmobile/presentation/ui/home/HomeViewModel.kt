package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
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

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val agentRunCoordinator: AgentRunCoordinator
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        const val GROUP_ALL = "All"
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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _favoriteSearchQuery = MutableStateFlow("")
    val favoriteSearchQuery = _favoriteSearchQuery.asStateFlow()

    private val rawFavoriteMessagesState = MutableStateFlow<List<MessageV2>>(emptyList())

    private val _favoriteGroups = MutableStateFlow<List<String>>(listOf(GROUP_ALL, "Starred", "Work", "Personal"))
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

        agentRunCoordinator.activeRuns
            .onEach { runs -> _activeChatIds.update { runs.values.mapTo(mutableSetOf()) { it.chatId } } }
            .launchIn(viewModelScope)
    }

    fun selectTab(tab: HomeTab) {
        _currentTab.update { tab }
        disableSelectionMode()
    }

    fun updateFavoriteSearchQuery(query: String) {
        _favoriteSearchQuery.update { query }
    }

    fun selectFavoriteGroup(group: String) {
        _selectedFavoriteGroup.update { group }
    }

    fun addFavoriteGroup(newGroup: String) {
        val trimmed = newGroup.trim()
        if (trimmed.isNotEmpty() && !_favoriteGroups.value.contains(trimmed)) {
            _favoriteGroups.update { it + trimmed }
            _selectedFavoriteGroup.update { trimmed }
        }
    }

    fun assignFavoriteMessageGroup(messageId: Int, groupName: String?) {
        _messageGroups.update { current ->
            if (groupName != null) {
                current + (messageId to groupName)
            } else {
                current - messageId
            }
        }
    }

    fun toggleFavorite(messageId: Int, isFavorite: Boolean) {
        viewModelScope.launch {
            chatRepository.setMessageFavorite(messageId, isFavorite)
        }
    }

    fun getChatRoom(chatId: Int, onResult: (ChatRoomV2?) -> Unit) {
        val existing = _chatListState.value.chats.find { it.id == chatId }
        if (existing != null) {
            onResult(existing)
            return
        }
        viewModelScope.launch {
            val allChats = chatRepository.fetchChatListV2()
            val chat = allChats.find { it.id == chatId }
            onResult(chat)
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
            val chats = chatRepository.searchChatsV2(query)
            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false }
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
            _chatListState.update { it.copy(chats = chats) }
            disableSelectionMode()
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
            _chatListState.update { it.copy(chats = chats) }
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
            val chats = chatRepository.fetchChatListV2()

            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false },
                    isSelectionMode = false
                )
            }

            Log.d("chats", "${_chatListState.value.chats}")
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
