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

    private fun sortChats(chats: List<ChatRoomV2>): List<ChatRoomV2> =
        chats.sortedWith(compareByDescending<ChatRoomV2> { it.isFavorite }.thenByDescending { it.updatedAt })

    data class ChatListState(
        val chats: List<ChatRoomV2> = emptyList(),
        val selectedChats: List<Boolean> = emptyList(),
        val selectedPlatforms: List<Boolean> = emptyList(),
        val isSelectionMode: Boolean = false,
        val isSearchMode: Boolean = false
    )

    private val _chatListState = MutableStateFlow(ChatListState())
    val chatListState: StateFlow<ChatListState> = _chatListState.asStateFlow()

    private val _platformState = MutableStateFlow<List<PlatformV2>>(emptyList())
    val platformState: StateFlow<List<PlatformV2>> = _platformState.asStateFlow()

    private val _showSelectModelDialog = MutableStateFlow(false)
    val showSelectModelDialog: StateFlow<Boolean> = _showSelectModelDialog.asStateFlow()

    private val _showDeleteWarningDialog = MutableStateFlow(false)
    val showDeleteWarningDialog: StateFlow<Boolean> = _showDeleteWarningDialog.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _favoriteSearchQuery = MutableStateFlow("")
    val favoriteSearchQuery: StateFlow<String> = _favoriteSearchQuery.asStateFlow()

    private val _selectedHomeTab = MutableStateFlow(HomeTab.CHATS)
    val selectedHomeTab: StateFlow<HomeTab> = _selectedHomeTab.asStateFlow()

    private val _favoriteGroups = MutableStateFlow(DEFAULT_GROUPS)
    val favoriteGroups: StateFlow<List<String>> = _favoriteGroups.asStateFlow()

    private val _selectedFavoriteGroup = MutableStateFlow(GROUP_ALL)
    val selectedFavoriteGroup: StateFlow<String> = _selectedFavoriteGroup.asStateFlow()

    private val _messageGroups = MutableStateFlow<Map<Int, String>>(emptyMap())
    val messageGroups: StateFlow<Map<Int, String>> = _messageGroups.asStateFlow()

    private val _archivedChats = MutableStateFlow<List<ChatRoomV2>>(emptyList())
    val archivedChats: StateFlow<List<ChatRoomV2>> = _archivedChats.asStateFlow()

    val favoriteMessages: StateFlow<List<MessageV2>> = combine(
        _favoriteSearchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged().flatMapLatest { query ->
            chatRepository.searchFavoriteAssistantMessages(query)
        },
        _selectedFavoriteGroup,
        _messageGroups
    ) { messages, group, groupsMap ->
        when (group) {
            GROUP_ALL -> messages
            else -> messages.filter { groupsMap[it.id] == group }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        _searchQuery
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { query ->
                if (_chatListState.value.isSearchMode) {
                    searchChats(query)
                }
            }
            .launchIn(viewModelScope)

        loadFavoriteSettings()
    }

    private fun loadFavoriteSettings() {
        viewModelScope.launch {
            settingRepository.fetchFavoriteGroups().collect { savedGroups ->
                val combined = (DEFAULT_GROUPS + savedGroups).distinct()
                _favoriteGroups.update { combined }
            }
        }
        viewModelScope.launch {
            settingRepository.fetchFavoriteMessageGroups().collect { savedMessageGroups ->
                _messageGroups.update { savedMessageGroups }
            }
        }
    }

    fun selectHomeTab(tab: HomeTab) {
        _selectedHomeTab.update { tab }
    }

    fun selectFavoriteGroup(group: String) {
        _selectedFavoriteGroup.update { group }
    }

    fun addFavoriteGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank() && trimmed !in _favoriteGroups.value) {
            val updated = _favoriteGroups.value + trimmed
            _favoriteGroups.update { updated }
            viewModelScope.launch {
                val customOnly = updated.filter { it !in DEFAULT_GROUPS }
                settingRepository.saveFavoriteGroups(customOnly)
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

    fun toggleChatFavorite(chatId: Int, isFavorite: Boolean) {
        viewModelScope.launch {
            chatRepository.setChatFavorite(chatId, isFavorite)
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

    fun deleteChat(chatRoom: ChatRoomV2) {
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
            val rawChats = chatRepository.fetchChatListV2()
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
