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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.chungjungsoo.gptmobile.presentation.common.PlatformCheckBoxItem
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
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit,
    onExistingChatClick: (ChatRoomV2, Int?) -> Unit,
    navigateToNewChat: (enabledPlatforms: List<String>) -> Unit
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val currentTab by homeViewModel.currentTab.collectAsStateWithLifecycle()
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val showSelectModelDialog by homeViewModel.showSelectModelDialog.collectAsStateWithLifecycle()
    val showDeleteWarningDialog by homeViewModel.showDeleteWarningDialog.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val activeChatIds by homeViewModel.activeChatIds.collectAsStateWithLifecycle()
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

    BackHandler(enabled = chatListState.isSelectionMode || chatListState.isSearchMode) {
        when {
            chatListState.isSelectionMode -> homeViewModel.disableSelectionMode()
            chatListState.isSearchMode -> homeViewModel.disableSearchMode()
        }
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopAppBar(
                isSelectionMode = chatListState.isSelectionMode,
                isSearchMode = chatListState.isSearchMode,
                selectedChats = selectedChatCount,
                duplicateEnabled = selectedChat != null && selectedChat.id !in activeChatIds,
                scrollBehavior = scrollBehavior,
                actionOnClick = {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.openDeleteWarningDialog()
                    } else {
                        settingOnClick()
                    }
                },
                duplicateOnClick = {
                    homeViewModel.duplicateSelectedChat()
                    Toast.makeText(context, duplicatedChatMessage, Toast.LENGTH_SHORT).show()
                },
                navigationOnClick = {
                    if (chatListState.isSelectionMode) {
                        homeViewModel.disableSelectionMode()
                        return@HomeTopAppBar
                    }

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
                                supportingContent = { Text(text = stringResource(R.string.using_certain_platform, usingPlatform)) }
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
                onPlatformSelect = { homeViewModel.updatePlatformCheckedState(it) }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        // Custom Groups horizontal filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            favoriteGroups.forEach { group ->
                val isSelected = (group == selectedGroup)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectGroup(group) },
                    label = {
                        Text(
                            text = if (group == HomeViewModel.GROUP_ALL) stringResource(R.string.all) else group,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color.Cyan.copy(alpha = 0.25f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
            FilterChip(
                selected = false,
                onClick = onAddGroupClick,
                label = { Text(stringResource(R.string.add_group)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_group),
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        if (favorites.isEmpty()) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                text = stringResource(R.string.no_favorites_yet),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = favorites,
                    key = { it.id },
                    contentType = { "favorite-message-item" }
                ) { message ->
                    val platformName = message.platformType?.let { platformState.getPlatformName(it) }
                        ?: stringResource(R.string.unknown)
                    val assignedGroup = messageGroups[message.id]
                    FavoriteMessageItem(
                        message = message,
                        platformName = platformName,
                        groupName = assignedGroup,
                        onClick = { onFavoriteClick(message) },
                        onUnfavoriteClick = { onToggleFavorite(message) }
                    )
                }
            }
        }
    }
}

@Composable
fun FavoriteMessageItem(
    message: MessageV2,
    platformName: String,
    groupName: String?,
    onClick: () -> Unit,
    onUnfavoriteClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val topColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val bottomColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 110.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .combinedClickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = platformName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!groupName.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Cyan.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = groupName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                IconButton(onClick = onUnfavoriteClick) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = stringResource(R.string.unfavorite),
                        tint = Color.Cyan
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AddFavoriteGroupDialog(
    onDismissRequest: () -> Unit,
    onAddGroup: (String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(R.string.add_group))
        },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(stringResource(R.string.group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = groupName.isNotBlank(),
                onClick = { onAddGroup(groupName) }
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
    favoriteGroups: List<String> = emptyList(),
    currentGroup: String? = null,
    onDismiss: () -> Unit,
    onAssignGroup: ((String?) -> Unit)? = null,
    onViewInChat: () -> Unit,
    onUnfavorite: () -> Unit
) {
    var showUnfavoriteConfirmDialog by remember { mutableStateOf(false) }
    var showGroupDropdown by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            GPTMobileIcon(loading = false)
                            Column {
                                Text(
                                    text = platformName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.favorites),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    }
                },
                bottomBar = {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Persistent View Button to enter the chat room
                            Button(
                                onClick = onViewInChat,
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.view_in_chat),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.view_in_chat),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            // Middle Group Button & Cyan Favorite Star Button
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (onAssignGroup != null) {
                                    Box {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clickable { showGroupDropdown = true }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = stringResource(R.string.assign_group),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = showGroupDropdown,
                                            onDismissRequest = { showGroupDropdown = false }
                                        ) {
                                            val validGroups = favoriteGroups.filter { it != HomeViewModel.GROUP_ALL }
                                            if (validGroups.isEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.no_custom_groups)) },
                                                    onClick = { showGroupDropdown = false },
                                                    enabled = false
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.none_group)) },
                                                    leadingIcon = {
                                                        if (currentGroup == null) {
                                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Cyan)
                                                        }
                                                    },
                                                    onClick = {
                                                        onAssignGroup(null)
                                                        showGroupDropdown = false
                                                    }
                                                )
                                                validGroups.forEach { group ->
                                                    DropdownMenuItem(
                                                        text = { Text(group) },
                                                        leadingIcon = {
                                                            if (currentGroup == group) {
                                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Cyan)
                                                            }
                                                        },
                                                        onClick = {
                                                            onAssignGroup(group)
                                                            showGroupDropdown = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Persistent Cyan Favorite Star Button
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Cyan.copy(alpha = 0.2f),
                                    border = BorderStroke(1.5.dp, Color.Cyan),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clickable { showUnfavoriteConfirmDialog = true }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Star,
                                            contentDescription = stringResource(R.string.unfavorite),
                                            tint = Color.Cyan,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                // Fully scrollable message content with Markdown, LaTeX math, code highlighting, and custom typography
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ChatMarkdown(
                        content = message.content,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showUnfavoriteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnfavoriteConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.unfavorite_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.unfavorite_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnfavoriteConfirmDialog = false
                        onUnfavorite()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.unfavorite),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnfavoriteConfirmDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopAppBar(
    isSelectionMode: Boolean,
    isSearchMode: Boolean,
    selectedChats: Int,
    duplicateEnabled: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    actionOnClick: () -> Unit,
    duplicateOnClick: () -> Unit,
    navigationOnClick: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchQuery: String
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            scrolledContainerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified,
            containerColor = if (isSelectionMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            titleContentColor = if (isSelectionMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground
        ),
        title = {
            when {
                isSearchMode -> {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChanged,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_chats)) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.clear)
                                    )
                                }
                            }
                        }
                    )
                }

                isSelectionMode -> {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(R.string.chats_selected, selectedChats),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                else -> {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        text = stringResource(R.string.chats),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = scrollBehavior.state.overlappedFraction),
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            when {
                isSelectionMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = navigationOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }

                isSearchMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = navigationOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }

                else -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = navigationOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search_chats)
                        )
                    }
                }
            }
        },
        actions = {
            when {
                isSelectionMode -> {
                    if (selectedChats == 1) {
                        IconButton(
                            modifier = Modifier.padding(4.dp),
                            enabled = duplicateEnabled,
                            onClick = duplicateOnClick
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                contentDescription = stringResource(R.string.duplicate)
                            )
                        }
                    }
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = actionOnClick
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = stringResource(R.string.delete)
                        )
                    }
                }

                !isSearchMode -> {
                    IconButton(
                        modifier = Modifier.padding(4.dp),
                        onClick = actionOnClick
                    ) {
                        Icon(imageVector = Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsTitle(scrollBehavior: TopAppBarScrollBehavior) {
    Text(
        modifier = Modifier
            .padding(top = 32.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        text = stringResource(R.string.chats),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 1.0F - scrollBehavior.state.overlappedFraction),
        style = MaterialTheme.typography.headlineLarge
    )
}

@Composable
private fun LazyListState.isScrollingUp(): Boolean {
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

@Preview
@Composable
fun NewChatButton(
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onClick: () -> Unit = { }
) {
    val orientation = LocalConfiguration.current.orientation
    val fabModifier = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
        modifier.systemBarsPadding()
    } else {
        modifier
    }
    ExtendedFloatingActionButton(
        modifier = fabModifier,
        onClick = { onClick() },
        expanded = expanded,
        icon = { Icon(Icons.Filled.Add, stringResource(R.string.new_chat)) },
        text = { Text(text = stringResource(R.string.new_chat)) }
    )
}

@Composable
fun SelectPlatformDialog(
    platforms: List<PlatformV2>,
    selectedPlatforms: List<Boolean>,
    onDismissRequest: () -> Unit,
    onConfirmation: (enabledPlatforms: List<String>) -> Unit,
    onPlatformSelect: (idx: Int) -> Unit
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
            PlatformSortOrder.PROVIDER -> list.sortedBy { it.second.apiType.name }
            PlatformSortOrder.ENABLED_FIRST -> list.sortedByDescending { it.second.enabled }
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
                            description = null,
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

@Preview
@Composable
fun EnablePlatformWarningText() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .wrapContentHeight(align = Alignment.CenterVertically)
            .padding(16.dp),
        textAlign = TextAlign.Center,
        text = stringResource(R.string.enable_at_leat_one_platform)
    )
}

@Composable
fun DeleteWarningDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = {
            Text(
                text = stringResource(R.string.delete_selected_chats),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(stringResource(R.string.this_operation_can_t_be_undone))
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
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
