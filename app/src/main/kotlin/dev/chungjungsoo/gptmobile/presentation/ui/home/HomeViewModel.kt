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

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val agentRunCoordinator: AgentRunCoordinator,
    private val managePlatformsUseCase: ManagePlatformsUseCase
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        const val GROUP_ALL = "All"
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
                    DEFAULT_GROUPS
                } else {
                    (DEFAULT_GROUPS + savedGroups).distinct()
                }
                _favoriteGroups.update { merged }
            }
            .launchIn(viewModelScope)

        settingRepository.observeFavoriteMessageGroups()
            .onEach { savedMappings ->
                _messageGroups.update { savedMappings }
            }
            .launchIn(viewModelScope)

        agentRunCoordinator.activeRuns
            .onEach { runs -> _activeChatIds.update { runs.values.mapTo(mutableSetOf()) { it.chatId } } }
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
        _selectedFavoriteGroup.update { group }
    }

    fun addFavoriteGroup(newGroup: String) {
        val trimmed = newGroup.trim()
        if (trimmed.isNotEmpty() && !_favoriteGroups.value.contains(trimmed)) {
            val updated = _favoriteGroups.value + trimmed
            _favoriteGroups.update { updated }
            _selectedFavoriteGroup.update { trimmed }
            viewModelScope.launch {
                settingRepository.saveFavoriteGroups(updated.filter { it !in DEFAULT_GROUPS })
            }
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

    fun toggleFavorite(messageId: Int, isFavorite: Boolean) {
        viewModelScope.launch {
            chatRepository.setMessageFavorite(messageId, isFavorite)
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

    fun fetchPlatformStatus() {
        viewModelScope.launch {
            val platforms = managePlatformsUseCase.getManagePlatformsStatus()
            _platformState.update { platforms }
            _chatListState.update { it.copy(selectedPlatforms = platforms.map { p -> p.enabled }) }
        }
    }

    fun deleteSelectedChats() {
        viewModelScope.launch {
            val chatsToDelete = _chatListState.value.chats.filterIndexed { index, _ ->
                _chatListState.value.selectedChats.getOrElse(index) { false }
            }
            chatRepository.deleteChatsV2(chatsToDelete)
            fetchChatList()
            disableSelectionMode()
        }
    }

    fun fetchChatList() {
        viewModelScope.launch {
            val chats = chatRepository.fetchChatListV2()
            _chatListState.update {
                it.copy(
                    chats = chats,
                    selectedChats = List(chats.size) { false }
                )
            }
        }
    }

    fun fetchArchivedChats() {
        viewModelScope.launch {
            val archived = chatRepository.fetchArchivedChats()
            _archivedChats.update { archived }
        }
    }

    fun toggleArchiveChat(chat: ChatRoomV2) {
        viewModelScope.launch {
            chatRepository.toggleChatArchive(chat.id, !chat.isArchived)
            fetchChatList()
            fetchArchivedChats()
        }
    }

    fun getChatRoom(chatId: Int, onResult: (ChatRoomV2?) -> Unit) {
        viewModelScope.launch {
            val chat = chatRepository.fetchChatListV2().firstOrNull { it.id == chatId }
                ?: chatRepository.fetchArchivedChats().firstOrNull { it.id == chatId }
            onResult(chat)
        }
    }

    fun enableSelectionMode() {
        _chatListState.update { it.copy(isSelectionMode = true) }
    }

    fun disableSelectionMode() {
        _chatListState.update {
            it.copy(
                isSelectionMode = false,
                selectedChats = List(it.chats.size) { false }
            )
        }
    }

    fun enableSearchMode() {
        _chatListState.update { it.copy(isSearchMode = true) }
    }

    fun disableSearchMode() {
        _chatListState.update { it.copy(isSearchMode = false) }
        updateSearchQuery("")
        fetchChatList()
    }

    fun openSelectModelDialog() {
        _showSelectModelDialog.update { true }
    }

    fun closeSelectModelDialog() {
        _showSelectModelDialog.update { false }
    }

    fun updateSelectedChat(idx: Int) {
        if (idx < 0 || idx >= _chatListState.value.selectedChats.size) return
        _chatListState.update {
            it.copy(
                selectedChats = it.selectedChats.mapIndexed { index, b ->
                    if (index == idx) {
                        !b
                    } else {
                        b
                    }
                }
            )
        }
    }
}
