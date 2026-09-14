package dev.chungjungsoo.gptmobile.presentation.ui.home

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.common.formatDisplayTime
import dev.chungjungsoo.gptmobile.presentation.ui.component.ChatItem
import dev.chungjungsoo.gptmobile.presentation.ui.component.CustomScrollbar
import dev.chungjungsoo.gptmobile.presentation.ui.component.PlatformCheckBoxItem
import dev.chungjungsoo.gptmobile.presentation.ui.theme.GPTMobileTheme
import kotlinx.coroutines.launch

enum class PlatformSortOrder {
    DEFAULT,
    NAME,
    PROVIDER,
    ENABLED_FIRST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    onChatRoomClick: (Int) -> Unit,
    onSettingClick: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val chatList by homeViewModel.chatList.collectAsState()
    val platforms by homeViewModel.platforms.collectAsState()
    val chatListState by homeViewModel.chatListState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showArchived by remember { mutableStateOf(false) }

    // Count archived chats
    val archivedCount = remember(chatList) {
        chatList.count { it.isArchived }
    }

    val duplicatedMessage = stringResource(R.string.duplicated_chat)
    val deletedMessage = stringResource(R.string.deleted_chats, chatListState.selectedChats.count { it })
    val chatArchivedMessage = stringResource(R.string.chat_archived)
    val chatUnarchivedMessage = stringResource(R.string.chat_unarchived)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HomeTopBar(
                chatListState = chatListState,
                scrollBehavior = scrollBehavior,
                selectedChatCount = chatListState.selectedChats.count { it },
                canDuplicate = chatListState.selectedChats.count { it } == 1,
                onCloseSelectionMode = homeViewModel::disableSelectionMode,
                onDuplicateClick = {
                    homeViewModel.duplicateSelectedChat()
                    homeViewModel.disableSelectionMode()
                    scope.launch {
                        snackbarHostState.showSnackbar(duplicatedMessage)
                    }
                },
                onDeleteClick = homeViewModel::openDeleteWarningDialog,
                onSettingClick = onSettingClick,
                onSearchToggle = homeViewModel::toggleSearchMode,
                onSearchQueryChanged = homeViewModel::updateSearchQuery,
                searchQuery = chatListState.searchQuery
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !chatListState.isSelectionMode,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                NewChatButton(
                    expanded = listState.isScrollingUp(),
                    onClick = {
                        val enabledPlatforms = platforms.filter { it.enabled }
                        if (enabledPlatforms.size == 1) {
                            homeViewModel.onSelectPlatforms(
                                enabledPlatforms.map { it.uid },
                                onChatRoomClick
                            )
                        } else {
                            homeViewModel.openPlatformSelectDialog()
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            val displayedChats = remember(chatList, showArchived) {
                chatList.filter { it.isArchived == showArchived }
            }

            if (displayedChats.isEmpty() && !showArchived) {
                EmptyStateView()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        ChatsTitle(scrollBehavior = scrollBehavior)
                    }

                    // Archived toggle chip
                    if (archivedCount > 0 || showArchived) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilterChip(
                                    selected = showArchived,
                                    onClick = { showArchived = !showArchived },
                                    label = {
                                        Text(
                                            if (showArchived) {
                                                stringResource(R.string.chats)
                                            } else {
                                                stringResource(R.string.archived_chats_count, archivedCount)
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (showArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        items = displayedChats,
                        key = { _, item -> item.id }
                    ) { index, chatRoom ->
                        val originalIndex = chatList.indexOf(chatRoom)
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        // Swipe left: Archive/Unarchive
                                        val newArchived = !chatRoom.isArchived
                                        homeViewModel.archiveChatRoom(chatRoom.id, newArchived)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = if (newArchived) chatArchivedMessage else chatUnarchivedMessage,
                                                actionLabel = "Undo",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                homeViewModel.archiveChatRoom(chatRoom.id, !newArchived)
                                            }
                                        }
                                        true
                                    }
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        // Swipe right: Delete
                                        homeViewModel.deleteChatRoom(chatRoom)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Chat deleted",
                                                actionLabel = "Undo",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                homeViewModel.restoreChatRoom(chatRoom)
                                            }
                                        }
                                        true
                                    }
                                    SwipeToDismissBoxValue.Settled -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val direction = dismissState.dismissDirection
                                val color = when (direction) {
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.secondaryContainer
                                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.errorContainer
                                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    contentAlignment = when (direction) {
                                        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                        else -> Alignment.CenterStart
                                    }
                                ) {
                                    Card(
                                        modifier = Modifier.fillMaxSize(),
                                        colors = CardDefaults.cardColors(containerColor = color),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = when (direction) {
                                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                                else -> Alignment.CenterStart
                                            }
                                        ) {
                                            when (direction) {
                                                SwipeToDismissBoxValue.EndToStart -> {
                                                    Icon(
                                                        imageVector = if (chatRoom.isArchived) {
                                                            Icons.Default.Unarchive
                                                        } else {
                                                            Icons.Default.Archive
                                                        },
                                                        contentDescription = stringResource(
                                                            if (chatRoom.isArchived) R.string.unarchive_chat else R.string.archive_chat
                                                        ),
                                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                                SwipeToDismissBoxValue.StartToEnd -> {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = stringResource(R.string.delete),
                                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                                    )
                                                }
                                                SwipeToDismissBoxValue.Settled -> {}
                                            }
                                        }
                                    }
                                }
                            },
                            content = {
                                Box(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    ChatItem(
                                        title = chatRoom.title,
                                        subtitle = if (!chatRoom.draftText.isNullOrBlank()) {
                                            "Draft: ${chatRoom.draftText}"
                                        } else {
                                            chatRoom.preview ?: ""
                                        },
                                        time = formatDisplayTime(chatRoom.updatedAt),
                                        selected = chatListState.selectedChats.getOrElse(originalIndex) { false },
                                        isSelectionMode = chatListState.isSelectionMode,
                                        onChatRoomClick = {
                                            if (chatListState.isSelectionMode) {
                                                homeViewModel.toggleChatSelection(originalIndex)
                                            } else {
                                                onChatRoomClick(chatRoom.id)
                                            }
                                        },
                                        onChatRoomLongClick = {
                                            if (!chatListState.isSelectionMode) {
                                                homeViewModel.enableSelectionMode(originalIndex)
                                            }
                                        }
                                    )
                                }
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(88.dp))
                    }
                }
            }

            CustomScrollbar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            )

            AnimatedVisibility(
                visible = chatListState.isSelectionMode,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                SelectionBottomBar(
                    selectedCount = chatListState.selectedChats.count { it },
                    totalCount = chatList.size,
                    canDuplicate = chatListState.selectedChats.count { it } == 1,
                    onSelectAll = homeViewModel::selectAllChats,
                    onDeselectAll = homeViewModel::deselectAllChats,
                    onDuplicate = {
                        homeViewModel.duplicateSelectedChat()
                        homeViewModel.disableSelectionMode()
                        scope.launch {
                            snackbarHostState.showSnackbar(duplicatedMessage)
                        }
                    },
                    onDelete = homeViewModel::openDeleteWarningDialog
                )
            }
        }

        if (chatListState.isPlatformSelectDialogOpen) {
            val initialSelection = remember(platforms) {
                platforms.map { it.isFavorite }
            }
            var selectedPlatforms by remember {
                mutableStateOf(
                    if (initialSelection.any { it }) initialSelection else platforms.map { false }
                )
            }

            SelectPlatformDialog(
                platforms = platforms,
                selectedPlatforms = selectedPlatforms,
                onDismissRequest = homeViewModel::closePlatformSelectDialog,
                onConfirmation = { platformUids ->
                    homeViewModel.closePlatformSelectDialog()
                    homeViewModel.onSelectPlatforms(platformUids, onChatRoomClick)
                },
                onPlatformSelect = { idx ->
                    selectedPlatforms = selectedPlatforms.toMutableList().also {
                        it[idx] = !it[idx]
                    }
                },
                onTogglePlatformFavorite = { platformId ->
                    homeViewModel.togglePlatformFavorite(platformId)
                }
            )
        }

        if (chatListState.isDeleteWarningDialogOpen) {
            DeleteWarningDialog(
                onDismissRequest = homeViewModel::closeDeleteWarningDialog,
                onConfirm = {
                    homeViewModel.deleteSelectedChats()
                    homeViewModel.closeDeleteWarningDialog()
                    homeViewModel.disableSelectionMode()
                    scope.launch {
                        snackbarHostState.showSnackbar(deletedMessage)
                    }
                }
            )
        }
    }
}

@Composable
fun EmptyStateView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_gpt_mobile_monochrome_foreground),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.no_chats),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_chats_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    totalCount: Int,
    canDuplicate: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (selectedCount == totalCount) onDeselectAll() else onSelectAll()
                    }
                ) {
                    Text(
                        if (selectedCount == totalCount) {
                            "Deselect All"
                        } else {
                            "Select All"
                        }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canDuplicate) {
                    IconButton(onClick = onDuplicate) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.duplicate)
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    val allLabels = remember(platforms) {
        platforms.flatMap { it.labels?.split(",") ?: emptyList() }
            .map { it.trim().substringBefore("#") }
            .filter { it.isNotBlank() }
            .distinct()
    }
    var selectedLabelFilter by remember { mutableStateOf<String?>(null) }

    // Map platform indices for stable checkbox selection even when sorted
    val indexedPlatforms = remember(platforms, sortOrder, selectedLabelFilter) {
        val list = platforms.mapIndexed { index, platform -> Pair(index, platform) }
            .filter { (_, platform) ->
                if (selectedLabelFilter == null) {
                    true
                } else {
                    platform.labels?.split(",")?.map { it.trim().substringBefore("#") }?.contains(selectedLabelFilter) == true
                }
            }
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

                if (allLabels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedLabelFilter == null,
                            onClick = { selectedLabelFilter = null },
                            label = { Text("All Labels") }
                        )
                        allLabels.forEach { label ->
                            FilterChip(
                                selected = selectedLabelFilter == label,
                                onClick = {
                                    selectedLabelFilter = if (selectedLabelFilter == label) null else label
                                },
                                label = { Text(label) }
                            )
                        }
                    }
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
