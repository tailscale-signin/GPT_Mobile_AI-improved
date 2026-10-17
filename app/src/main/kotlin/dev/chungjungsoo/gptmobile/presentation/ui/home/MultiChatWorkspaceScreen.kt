package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Compatibility entry point for workspaces created before shared-input multi-chat. */
@HiltViewModel
class MultiChatWorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chats: ChatRepository,
    private val settings: SettingRepository
) : ViewModel() {
    private val workspaceId: Int = checkNotNull(savedStateHandle["rootChatId"])
    private val _rooms = MutableStateFlow<List<ChatRoomV2>>(emptyList())
    val rooms = _rooms.asStateFlow()
    private val _laneNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val laneNames = _laneNames.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val ids = settings.getFeatureSettings().multiChatWorkspaces[workspaceId].orEmpty()
                check(ids.isNotEmpty()) { "Multi-chat workspace could not be found." }
                val byId = (chats.fetchChatListV2() + chats.fetchArchivedChatListV2()).associateBy { it.id }
                val resolved = ids.mapNotNull(byId::get)
                check(resolved.isNotEmpty()) { "The workspace no longer has any available conversations." }
                val profiles = settings.fetchPlatformV2s().associateBy { it.uid }
                _laneNames.value = resolved.associate { room ->
                    room.id to (room.enabledPlatform.firstOrNull()?.let { profiles[it]?.name } ?: room.title)
                }
                // Upgrade the root to a shared composer without rewriting or deleting any
                // of the independent lane histories created by the previous UI.
                val root = resolved.firstOrNull { it.id == workspaceId } ?: resolved.first()
                val uids = resolved.flatMap { it.enabledPlatform }.distinct()
                val shared = if (root.enabledPlatform == uids) root else chats.updateChatPlatforms(root, uids)
                _rooms.value = listOf(shared) + resolved.filterNot { it.id == shared.id }
                _error.value = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not open Multi-chat."
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiChatWorkspaceScreen(
    onBack: () -> Unit,
    onOpenConversation: (Int, Boolean) -> Unit,
    onReviewEvidenceAccess: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    viewModel: MultiChatWorkspaceViewModel = hiltViewModel()
) {
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val names by viewModel.laneNames.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Multi-chat · " + (rooms.firstOrNull()?.enabledPlatform?.size ?: 0).toString() + " AIs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).testTag("multi_chat_workspace")) {
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            if (rooms.isNotEmpty()) {
                val innerNav = rememberNavController()
                if (rooms.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rooms.drop(1).forEach { room ->
                            TextButton(onClick = { onOpenConversation(room.id, false) }) {
                                Text("Previous chat · " + (names[room.id] ?: room.title))
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    val first = rooms.first()
                    NavHost(
                        navController = innerNav,
                        startDestination = "lane_" + first.id,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        listOf(first).forEach { room ->
                            composable(
                                route = "lane_" + room.id,
                                arguments = listOf(
                                    navArgument("chatRoomId") {
                                        type = NavType.IntType
                                        defaultValue = room.id
                                    },
                                    navArgument("enabledPlatforms") {
                                        defaultValue = room.enabledPlatform.joinToString(",")
                                    },
                                    navArgument("conversationMode") {
                                        defaultValue = "STANDARD"
                                    }
                                )
                            ) { entry ->
                                val child: ChatViewModel = hiltViewModel(entry)
                                ChatScreen(
                                    chatViewModel = child,
                                    onBackAction = onBack,
                                    onReviewEvidenceAccess = onReviewEvidenceAccess,
                                    onNavigateToLocalModels = onNavigateToLocalModels,
                                    onOpenConversation = onOpenConversation
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
