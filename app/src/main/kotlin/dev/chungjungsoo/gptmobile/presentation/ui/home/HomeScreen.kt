package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
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
import androidx.compose.ui.res.pluralStringResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.common.ChatMarkdown
import dev.chungjungsoo.gptmobile.presentation.ui.chat.GPTMobileIcon
import dev.chungjungsoo.gptmobile.presentation.ui.chat.PlatformCheckBoxItem
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior

private fun List<PlatformV2>.getPlatformName(uid: String): String = firstOrNull { it.uid == uid }?.name ?: ""

enum class HomeTab {
    CHATS,
    FAVORITES
}

enum class PlatformSortOrder {
    DEFAULT,
    NAME,
    PROVIDER,
    ENABLED_FIRST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = hiltViewModel(),
    settingOnClick: () -> Unit = {},
    onExistingChatClick: (chatRoom: ChatRoomV2, targetMessageId: Int?) -> Unit = { _, _ -> },
    navigateToNewChat: (enabledPlatforms: List<String>) -> Unit = {}
) {
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior()
    val chatListState by homeViewModel.chatListState.collectAsStateWithLifecycle()
    val platformState by homeViewModel.platformState.collectAsStateWithLifecycle()
    val favoriteMessages by homeViewModel.favoriteMessages.collectAsStateWithLifecycle()
    val favoriteGroups by homeViewModel.favoriteGroups.collectAsStateWithLifecycle()
    val selectedFavoriteGroup by homeViewModel.selectedFavoriteGroup.collectAsStateWithLifecycle()
    val messageGroups by homeViewModel.messageGroups.collectAsStateWithLifecycle()
    val isChatListEmpty = chatListState.chatList.isEmpty()
    val showSelectModelDialog = chatListState.isSelectPlatformDialogOpen
    val showDeleteWarningDialog = chatListState.isDeleteWarningDialogOpen
    val selectedChatCount = chatListState.selectedChats.count { it }
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current
    val deletedChatsMessage = pluralStringResource(
        R.plurals.deleted_chats,
        selectedChatCount,
        selectedChatCount
    )

    var currentTab by remember { mutableStateOf(HomeTab.CHATS) }
    var selectedDetailMessage by remember { mutableStateOf<MessageV2?>(null) }
    var showAddGroupDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = chatListState.isSelectionMode || chatListState.isSearchMode) {
        if (chatListState.isSearchMode) {
            homeViewModel.disableSearchMode()
        } else {
            homeViewModel.disableSelectionMode()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                HomeTopAppBar(
                    isSelectionMode = chatListState.isSelectionMode,
                    isSearchMode = chatListState.isSearchMode,
                    selectedChats = selectedChatCount,
                    duplicateEnabled = selectedChatCount == 1,
                    scrollBehavior = scrollBehavior,
                    actionOnClick = settingOnClick,
                    duplicateOnClick = {
                        homeViewModel.duplicateSelectedChat()
                        Toast.makeText(context, R.string.duplicated_chat, Toast.LENGTH_SHORT).show()
                    },
                    navigationOnClick = {
                        if (chatListState.isSearchMode) {
                            homeViewModel.disableSearchMode()
                        } else {
                            homeViewModel.disableSelectionMode()
                        }
                    },
                    onSearchQueryChanged = homeViewModel::onSearchQueryChanged,
                    searchQuery = chatListState.searchQuery
                )
                PrimaryTabRow(
                    selectedTabIndex = currentTab.ordinal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = currentTab == HomeTab.CHATS,
                        onClick = { currentTab = HomeTab.CHATS },
                        text = { Text(stringResource(R.string.chats)) }
                    )
                    Tab(
                        selected = currentTab == HomeTab.FAVORITES,
                        onClick = { currentTab = HomeTab.FAVORITES },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(stringResource(R.string.favorites))
                                if (favoriteMessages.isNotEmpty()) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Cyan.copy(alpha = 0.3f)
                                    ) {
                                        Text(
                                            text = "${favoriteMessages.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == HomeTab.CHATS) {
                NewChatButton(
                    expanded = !lazyListState.isScrollInProgress,
                    onClick = {
                        homeViewModel.refreshPlatforms()
                        homeViewModel.openSelectModelDialog()
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                HomeTab.CHATS -> {
                    if (isChatListEmpty) {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            text = if (chatListState.isSearchMode) {
                                stringResource(R.string.no_search_results)
                            } else {
                                stringResource(R.string.no_chats)
                            },
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(
                                items = chatListState.chatList,
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
                }
            )
        }
    }
}

@Composable
fun FavoritesList(
    favorites: List<MessageV2>,
    favoriteGroups: List<String>,
    selectedGroup: String?,
    messageGroups: Map<Int, String>,
    platformState: List<PlatformV2>,
    onSelectGroup: (String?) -> Unit,
    onAddGroupClick: () -> Unit,
    onFavoriteClick: (MessageV2) -> Unit,
    onToggleFavorite: (MessageV2) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = if (selectedGroup == null) 0 else favoriteGroups.indexOf(selectedGroup) + 1,
            edgePadding = 16.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedGroup == null,
                onClick = { onSelectGroup(null) },
                text = { Text(stringResource(R.string.all)) }
            )
            favoriteGroups.forEach { groupName ->
                Tab(
                    selected = selectedGroup == groupName,
                    onClick = { onSelectGroup(groupName) },
                    text = { Text(groupName) }
                )
            }
            Tab(
                selected = false,
                onClick = onAddGroupClick,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.add_group),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.add_group))
                    }
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
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
        title = { Text(stringResource(R.string.add_group)) },
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
            Button(
                onClick = {
                    if (groupName.isNotBlank()) {
                        onAddGroup(groupName.trim())
                    }
                },
                enabled = groupName.isNotBlank()
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
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.none_group)) },
                                                onClick = {
                                                    onAssignGroup(null)
                                                    showGroupDropdown = false
                                                },
                                                leadingIcon = {
                                                    if (currentGroup == null) {
                                                        Icon(Icons.Filled.Check, null)
                                                    }
                                                }
                                            )
                                            favoriteGroups.forEach { group ->
                                                DropdownMenuItem(
                                                    text = { Text(group) },
                                                    onClick = {
                                                        onAssignGroup(group)
                                                        showGroupDropdown = false
                                                    },
                                                    leadingIcon = {
                                                        if (currentGroup == group) {
                                                            Icon(Icons.Filled.Check, null)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f)),
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
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        }
                    )
                }
                isSelectionMode -> {
                    Text(
                        stringResource(R.string.chats_selected, selectedChats),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        navigationIcon = {
            if (isSelectionMode || isSearchMode) {
                IconButton(onClick = navigationOnClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.arrow_icon)
                    )
                }
            }
        },
        actions = {
            if (isSelectionMode) {
                IconButton(
                    onClick = duplicateOnClick,
                    enabled = duplicateEnabled
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.duplicate)
                    )
                }
            } else if (!isSearchMode) {
                IconButton(onClick = { onSearchQueryChanged("") }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.search)
                    )
                }
                IconButton(onClick = actionOnClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings)
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeExpandedTopBar(
    scrollBehavior: TopAppBarScrollBehavior
) {
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

@OptIn(ExperimentalMaterial3Api::class)
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

    val indexedPlatforms = remember(platforms, sortOrder) {
        val list = platforms.mapIndexed { index, platform -> Pair(index, platform) }
        when (sortOrder) {
            PlatformSortOrder.DEFAULT -> list
            PlatformSortOrder.NAME -> list.sortedBy { it.second.name.lowercase() }
            PlatformSortOrder.PROVIDER -> list.sortedBy { it.second.compatibleType.name }
            PlatformSortOrder.ENABLED_FIRST -> list.sortedByDescending { it.second.enabled }
        }
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 32.dp)
            .heightIn(max = screenHeight - 64.dp),
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.select_platform),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.select_platform_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Fancy Modern Interactive Sort Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.DEFAULT,
                        onClick = { sortOrder = PlatformSortOrder.DEFAULT },
                        label = { Text(stringResource(R.string.default_label)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.NAME,
                        onClick = { sortOrder = PlatformSortOrder.NAME },
                        label = { Text(stringResource(R.string.sort_name)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.PROVIDER,
                        onClick = { sortOrder = PlatformSortOrder.PROVIDER },
                        label = { Text(stringResource(R.string.sort_provider)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = sortOrder == PlatformSortOrder.ENABLED_FIRST,
                        onClick = { sortOrder = PlatformSortOrder.ENABLED_FIRST },
                        label = { Text(stringResource(R.string.sort_enabled)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        },
        text = {
            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (platforms.any { it.enabled }) {
                    indexedPlatforms.forEach { (originalIndex, platform) ->
                        PlatformCheckBoxItem(
                            title = platform.name,
                            enabled = platform.enabled,
                            selected = selectedPlatforms.getOrElse(originalIndex) { false },
                            description = "${platform.compatibleType.name} • ${platform.model.ifBlank { "Default model" }}",
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
            Button(
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
