package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.domain.model.SortType
import dev.chungjungsoo.gptmobile.presentation.common.PlatformCheckBoxItem
import dev.chungjungsoo.gptmobile.presentation.ui.archive.ArchivedConversationsBar
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatMarkdown
import dev.chungjungsoo.gptmobile.presentation.ui.chat.GPTMobileIcon
import dev.chungjungsoo.gptmobile.util.getPlatformName

enum class PlatformSortOrder {
    DEFAULT,
    NAME,
    PROVIDER,
    ENABLED_FIRST
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit = {},
    onExistingChatClick: (chatRoom: ChatRoomV2, targetMessageId: Int?) -> Unit = { _, _ -> },
    navigateToNewChat: (enabledPlatforms: List<String>) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val currentTab by homeViewModel.currentTab.collectAsStateWithLifecycle()
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val showSelectModelDialog by homeViewModel.showSelectModelDialog.collectAsStateWithLifecycle()
    val showDeleteWarningDialog by homeViewModel.showDeleteWarningDialog.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val activeChatIds by homeViewModel.activeChatIds.collectAsStateWithLifecycle()
    val archivedChats by homeViewModel.archivedChats.collectAsStateWithLifecycle()
    val searchQuery by homeViewModel.searchQuery.collectAsStateWithLifecycle()
    val favoriteMessages by homeViewModel.favoriteMessages.collectAsStateWithLifecycle()
    val favoriteGroups by homeViewModel.favoriteGroups.collectAsStateWithLifecycle()
    val selectedFavoriteGroup by homeViewModel.selectedFavoriteGroup.collectAsStateWithLifecycle()
    val messageGroups by homeViewModel.messageGroups.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectedChatCount = chatListState.selectedChats.count { it }
    val selectedChat = chatListState.chats.filterIndexed { index, _ -> chatListState.selectedChats.getOrElse(index) { false } }.singleOrNull()
    val duplicatedChatMessage = stringResource(R.string.duplicated_chat)
    val deletedChatsMessage = stringResource(R.string.deleted_chats, selectedChatCount)

    var selectedDetailMessage by remember { mutableStateOf<MessageV2?>(null) }
    var showAddGroupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
            homeViewModel.fetchChats()
            homeViewModel.fetchPlatformStatus()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopBar(
                chatListState = chatListState,
                scrollBehavior = scrollBehavior,
                selectedChatCount = selectedChatCount,
                canDuplicate = selectedChat != null && !activeChatIds.contains(selectedChat.id),
                onCloseSelectionMode = homeViewModel::disableSelectionMode,
                onDuplicateClick = {
                    homeViewModel.duplicateSelectedChat()
                    Toast.makeText(context, duplicatedChatMessage, Toast.LENGTH_SHORT).show()
                },
                onDeleteClick = homeViewModel::openDeleteWarningDialog,
                onSettingClick = settingOnClick,
                onSearchToggle = {
                    if (chatListState.isSearchMode) {
                        homeViewModel.disableSearchMode()
                    } else {
                        homeViewModel.enableSearchMode()
                    }
                },
                onSearchQueryChanged = homeViewModel::updateSearchQuery,
                searchQuery = searchQuery
            )
        },
        floatingActionButton = {
            if (currentTab == HomeTab.CHATS && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
                NewChatButton(expanded = listState.isScrollingUp(), onClick = {
                    val enabledApiTypes = platformState.filter { it.enabled }.map { it.uid }
                    if (enabledApiTypes.size == 1) {
                        // Navigate to new chat directly if only one platform is enabled
                        navigateToNewChat(enabledApiTypes)
                    } else {
                        homeViewModel.openSelectModelDialog()
                    }
                })
            }
        },
        bottomBar = {
            if (currentTab == HomeTab.CHATS && !chatListState.isSelectionMode && !chatListState.isSearchMode) {
                ArchivedConversationsBar(
                    archivedChats = archivedChats,
                    onUnarchiveChat = { room ->
                        homeViewModel.unarchiveChat(room)
                        Toast.makeText(context, R.string.chat_unarchived, Toast.LENGTH_SHORT).show()
                    },
                    onDeleteChat = { room ->
                        homeViewModel.deleteArchivedChat(room)
                    },
                    onChatClick = { room ->
                        onExistingChatClick(room, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (!chatListState.isSelectionMode && !chatListState.isSearchMode) {
                PrimaryTabRow(
                    selectedTabIndex = currentTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(
                        selected = currentTab == HomeTab.CHATS,
                        onClick = { homeViewModel.selectTab(HomeTab.CHATS) },
                        text = { Text(stringResource(R.string.chats)) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = stringResource(R.string.chats)
                            )
                        }
                    )
                    Tab(
                        selected = currentTab == HomeTab.FAVORITES,
                        onClick = { homeViewModel.selectTab(HomeTab.FAVORITES) },
                        text = { Text(stringResource(R.string.favorites)) },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == HomeTab.FAVORITES) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = stringResource(R.string.favorites)
                            )
                        }
                    )
                }
            }

            when (currentTab) {
                HomeTab.CHATS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        state = listState
                    ) {
                        if (!chatListState.isSearchMode) {
                            item(key = "home-chats-title", contentType = "header") {
                                ChatsTitle(scrollBehavior)
                            }
                        }
                        if (chatListState.isSearchMode && chatListState.chats.isEmpty() && searchQuery.isNotEmpty()) {
                            item(key = "home-chats-empty", contentType = "empty-notice") {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    text = stringResource(R.string.no_search_results),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        itemsIndexed(
                            items = chatListState.chats,
                            key = { _, it -> it.id },
                            contentType = { _, _ -> "chat-room-item" }
                        ) { idx, chatRoom ->
                            val usingPlatform = chatRoom.enabledPlatform.joinToString(", ") { uid -> platformState.getPlatformName(uid) }
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onLongClick = {
                                            if (!chatListState.isSearchMode) {
                                                homeViewModel.enableSelectionMode()
                                                homeViewModel.selectChat(idx)
                                            }
                                        },
                                        onClick = {
                                            if (chatListState.isSelectionMode) {
                                                homeViewModel.selectChat(idx)
                                            } else {
                                                onExistingChatClick(chatRoom, null)
                                            }
                                        }
                                    )
                                    .padding(start = 8.dp, end = 8.dp)
                                    .animateItem(),
                                headlineContent = { Text(text = chatRoom.title) },
                                leadingContent = {
                                    if (chatListState.isSelectionMode) {
                                        Checkbox(
                                            checked = chatListState.selectedChats[idx],
                                            onCheckedChange = { homeViewModel.selectChat(idx) }
                                        )
                                    } else {
                                        Icon(
                                            ImageVector.vectorResource(id = R.drawable.ic_rounded_chat),
                                            contentDescription = stringResource(R.string.chat_icon)
                                        )
                                    }
                                },
                                supportingContent = { Text(text = stringResource(R.string.using_certain_platform, usingPlatform)) },
                                trailingContent = {
                                    if (!chatListState.isSelectionMode && !chatListState.isSearchMode) {
                                        IconButton(
                                            onClick = {
                                                homeViewModel.archiveChat(chatRoom)
                                                Toast.makeText(context, R.string.chat_archived, Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Archive,
                                                contentDescription = stringResource(R.string.archive_chat),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                HomeTab.FAVORITES -> {
                    FavoritesList(
                        favorites = favoriteMessages,
                        favoriteGroups = favoriteGroups,
                        selectedGroup = selectedFavoriteGroup,
                        messageGroups = messageGroups,
                        platformState = platformState,
                        onSelectGroup = homeViewModel::selectFavoriteGroup,
                        onAddGroupClick = { showAddGroupDialog = true },
                        onFavoriteClick = { message ->
                            selectedDetailMessage = message
                        },
                        onToggleFavorite = { message ->
                            homeViewModel.toggleFavorite(message.id, !message.isFavorite)
                        }
                    )
                }
            }
        }

        if (showAddGroupDialog) {
            AddFavoriteGroupDialog(
                onDismissRequest = { showAddGroupDialog = false },
                onAddGroup = { newGroupName ->
                    homeViewModel.addFavoriteGroup(newGroupName)
                    showAddGroupDialog = false
                }
            )
        }

        selectedDetailMessage?.let { detailMessage ->
            val platformName = detailMessage.platformType?.let { platformState.getPlatformName(it) }
                ?: stringResource(R.string.unknown)
            val currentGroup = messageGroups[detailMessage.id]
            FavoriteDetailDialog(
                message = detailMessage,
                platformName = platformName,
                favoriteGroups = favoriteGroups,
                currentGroup = currentGroup,
                onDismiss = { selectedDetailMessage = null },
                onAssignGroup = { group ->
                    homeViewModel.assignFavoriteMessageGroup(detailMessage.id, group)
                },
                onViewInChat = {
                    homeViewModel.getChatRoom(detailMessage.chatId) { targetChat ->
                        selectedDetailMessage = null
                        if (targetChat != null) {
                            onExistingChatClick(targetChat, detailMessage.id)
                        }
                    }
                },
                onUnfavorite = {
                    homeViewModel.toggleFavorite(detailMessage.id, false)
                    selectedDetailMessage = null
                }
            )
        }

        if (showSelectModelDialog) {
            SelectPlatformDialog(
                platformState,
                selectedPlatforms = chatListState.selectedPlatforms,
                onDismissRequest = { homeViewModel.closeSelectModelDialog() },
                onConfirmation = {
                    navigateToNewChat(it)
                    homeViewModel.closeSelectModelDialog()
                },
                onPlatformSelect = { homeViewModel.updatePlatformCheckedState(it) },
                onTogglePlatformFavorite = { platformId ->
                    val target = platformState.find { it.id == platformId }
                    if (target != null) {
                        homeViewModel.togglePlatformFavorite(platformId, !target.isFavorite)
                    }
                }
            )
        }

        if (showDeleteWarningDialog) {
            DeleteWarningDialog(
                onDismissRequest = homeViewModel::closeDeleteWarningDialog,
                onConfirm = {
                    homeViewModel.deleteSelectedChats()
                    Toast.makeText(context, deletedChatsMessage, Toast.LENGTH_SHORT).show()
                    homeViewModel.closeDeleteWarningDialog()
                }
            )
        }
    }
}

@Composable
fun FavoritesList(
    favorites: List<MessageV2>,
    favoriteGroups: List<String>,
    selectedGroup: String,
    messageGroups: Map<Int, String>,
    platformState: List<PlatformV2>,
    onSelectGroup: (String) -> Unit,
    onAddGroupClick: () -> Unit,
    onFavoriteClick: (MessageV2) -> Unit,
    onToggleFavorite: (MessageV2) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            favoriteGroups.forEach { group ->
                FilterChip(
                    selected = selectedGroup == group,
                    onClick = { onSelectGroup(group) },
                    label = { Text(group) }
                )
            }
            IconButton(onClick = onAddGroupClick) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_group)
                )
            }
        }

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_favorites_yet),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favorites, key = { it.id }) { message ->
                    val platformName = message.platformType?.let { platformState.getPlatformName(it) }
                        ?: stringResource(R.string.unknown)
                    val assignedGroup = messageGroups[message.id]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFavoriteClick(message) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = platformName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (assignedGroup != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = assignedGroup,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddFavoriteGroupDialog(
    onDismissRequest: () -> Unit,
    onAddGroup: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.add_group)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onAddGroup(text.trim()) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun FavoriteDetailDialog(
    message: MessageV2,
    platformName: String,
    favoriteGroups: List<String>,
    currentGroup: String?,
    onDismiss: () -> Unit,
    onAssignGroup: (String?) -> Unit,
    onViewInChat: () -> Unit,
    onUnfavorite: () -> Unit
) {
    var showGroupDropdown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(platformName, style = MaterialTheme.typography.titleMedium)
                Box {
                    TextButton(onClick = { showGroupDropdown = true }) {
                        Text(currentGroup ?: stringResource(R.string.none_group))
                    }
                    DropdownMenu(
                        expanded = showGroupDropdown,
                        onDismissRequest = { showGroupDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.none_group)) },
                            onClick = {
                                onAssignGroup(null)
                                showGroupDropdown = false
                            }
                        )
                        favoriteGroups.filter { it != HomeViewModel.GROUP_ALL }.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group) },
                                onClick = {
                                    onAssignGroup(group)
                                    showGroupDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ChatMarkdown(content = message.content)
            }
        },
        confirmButton = {
            TextButton(onClick = onViewInChat) {
                Text(stringResource(R.string.view_in_chat))
            }
        },
        dismissButton = {
            TextButton(onClick = onUnfavorite) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
fun SelectPlatformDialog(
    platforms: List<PlatformV2>,
    selectedPlatforms: List<Boolean>,
    onDismissRequest: () -> Unit,
    onConfirmation: (enabledPlatforms: List<String>) -> Unit,
    onPlatformSelect: (idx: Int) -> Unit,
    onTogglePlatformFavorite: (platformId: Int) -> Unit = {}
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var sortOrder by remember { mutableStateOf(PlatformSortOrder.DEFAULT) }

    // Map platform indices for stable checkbox selection even when sorted
    val indexedPlatforms = remember(platforms, sortOrder) {
        val list = platforms.mapIndexed { index, platform -> Pair(index, platform) }
        when (sortOrder) {
            PlatformSortOrder.DEFAULT -> list
            PlatformSortOrder.NAME -> list.sortedBy { it.second.name.lowercase() }
            PlatformSortOrder.PROVIDER -> list.sortedBy { it.second.compatibleType.name }
            PlatformSortOrder.ENABLED_FIRST -> list.sortedWith(
                compareByDescending<Pair<Int, PlatformV2>> { it.second.enabled }
                    .thenByDescending { it.second.isFavorite }
            )
        }
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.select_platform),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.select_platform_description),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                // Interactive Sort Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.DEFAULT,
                        onClick = { sortOrder = PlatformSortOrder.DEFAULT },
                        label = { Text("Default") }
                    )
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.NAME,
                        onClick = { sortOrder = PlatformSortOrder.NAME },
                        label = { Text("Name") }
                    )
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.PROVIDER,
                        onClick = { sortOrder = PlatformSortOrder.PROVIDER },
                        label = { Text("Provider") }
                    )
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.ENABLED_FIRST,
                        onClick = { sortOrder = PlatformSortOrder.ENABLED_FIRST },
                        label = { Text("Enabled") }
                    )
                }
            }
        },
        text = {
            HorizontalDivider()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (platforms.any { it.enabled }) {
                    indexedPlatforms.forEach { (originalIndex, platform) ->
                        PlatformCheckBoxItem(
                            title = platform.name,
                            enabled = platform.enabled,
                            selected = selectedPlatforms.getOrElse(originalIndex) { false },
                            isFavorite = platform.isFavorite,
                            labels = platform.labels,
                            description = null,
                            onLongClickEvent = { onTogglePlatformFavorite(platform.id) },
                            onClickEvent = { onPlatformSelect(originalIndex) }
                        )
                    }
                } else {
                    EnablePlatformWarningText()
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedPlatforms.any { it },
                onClick = { onConfirmation(platforms.filterIndexed { i, _ -> selectedPlatforms[i] }.map { it.uid }) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDismissRequest() }
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EnablePlatformWarningText() {
    Text(
        text = stringResource(R.string.enable_at_leat_one_platform),
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

@Composable
fun DeleteWarningDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.delete_selected_chats)) },
        text = { Text(stringResource(R.string.this_operation_can_t_be_undone)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousScrollOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@Composable
fun NewChatButton(expanded: Boolean, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(Icons.Filled.Add, stringResource(R.string.new_chat)) },
        text = { Text(text = stringResource(R.string.new_chat)) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    chatListState: HomeViewModel.ChatListState,
    scrollBehavior: TopAppBarScrollBehavior,
    selectedChatCount: Int,
    canDuplicate: Boolean,
    onCloseSelectionMode: () -> Unit,
    onDuplicateClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSettingClick: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchQuery: String
) {
    if (chatListState.isSelectionMode) {
        TopAppBar(
            title = { Text(stringResource(R.string.chats_selected, selectedChatCount)) },
            navigationIcon = {
                IconButton(onClick = onCloseSelectionMode) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                }
            },
            actions = {
                if (canDuplicate) {
                    IconButton(onClick = onDuplicateClick) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.duplicate))
                    }
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        )
    } else if (chatListState.isSearchMode) {
        TopAppBar(
            title = {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = { Text(stringResource(R.string.search_chats)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            },
            navigationIcon = {
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                }
            }
        )
    } else {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                }
                IconButton(onClick = onSettingClick) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsTitle(scrollBehavior: TopAppBarScrollBehavior) {
    Text(
        text = stringResource(R.string.chats),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}
