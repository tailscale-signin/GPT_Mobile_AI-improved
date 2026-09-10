package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider.getUriForFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.util.PERMISSION_ACCESS_LOCAL_NETWORK
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {}
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidthDp = with(LocalDensity.current) { containerSize.width.toDp() }
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboard.current
    val systemChatMargin = 32.dp
    val maximumUserChatBubbleWidth = (screenWidthDp - systemChatMargin) * 0.8F
    val maximumOpponentChatBubbleWidth = screenWidthDp - systemChatMargin
    val chatRoom by chatViewModel.chatRoom.collectAsStateWithLifecycle()
    val groupedMessages by chatViewModel.groupedMessages.collectAsStateWithLifecycle()
    val hasTargetMessage = chatViewModel.targetMessageId > 0
    val listState = rememberChatListState(
        messageCount = groupedMessages.userMessages.size,
        hasTargetMessage = hasTargetMessage
    )
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var isFollowingBottom by remember { mutableStateOf(!hasTargetMessage) }
    var hasScrolledToTarget by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val isLoaded by chatViewModel.isLoaded.collectAsStateWithLifecycle()
    val agentRunsById by chatViewModel.agentRunsById.collectAsStateWithLifecycle()
    val runNoticesById by chatViewModel.runNoticesById.collectAsStateWithLifecycle()
    val toolEventsByRun by chatViewModel.toolEventsByRun.collectAsStateWithLifecycle()
    val enabledPlatformsInChat by chatViewModel.enabledPlatformsInChat.collectAsStateWithLifecycle()
    val enabledPlatformLookup by chatViewModel.enabledPlatformsLookup.collectAsStateWithLifecycle()
    val isKeyboardOpen by chatViewModel.isKeyboardOpen.collectAsStateWithLifecycle()
    val isIdle by chatViewModel.isIdle.collectAsStateWithLifecycle()
    val canUseChat by chatViewModel.canUseChat.collectAsStateWithLifecycle()
    val anyPlatformDisabled by chatViewModel.anyPlatformDisabled.collectAsStateWithLifecycle()
    val loadingStates by chatViewModel.loadingStates.collectAsStateWithLifecycle()
    val activeRuns by chatViewModel.activeRuns.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var editIndex by remember { mutableIntStateOf(-1) }
    var editAssistantPlatformIndex by remember { mutableIntStateOf(-1) }
    var editAssistantMessageIndex by remember { mutableIntStateOf(-1) }
    var isChatTitleDialogOpen by remember { mutableStateOf(false) }
    var isEditAssistantDialogOpen by remember { mutableStateOf(false) }
    var isToolSelectionOpen by remember { mutableStateOf(false) }
    var isSelectTextOpen by remember { mutableStateOf(false) }
    var selectTextContent by remember { mutableStateOf("") }
    var pendingNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingNetworkAction
        pendingNetworkAction = null
        if (granted) {
            pending?.invoke()
        } else {
            Toast.makeText(context, R.string.local_network_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    fun executeWithLocalNetworkPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNetworkAction = action
            localNetworkPermissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
        } else {
            action()
        }
    }

    LaunchedEffect(chatViewModel.targetMessageId, groupedMessages.userMessages, isLoaded) {
        if (!isLoaded || hasScrolledToTarget || !hasTargetMessage) return@LaunchedEffect
        val targetIndex = groupedMessages.userMessages.indexOfFirst { it.id == chatViewModel.targetMessageId }
        if (targetIndex >= 0) {
            hasScrolledToTarget = true
            listState.scrollToItem(targetIndex)
        }
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                title = chatRoom.name,
                isMenuItemEnabled = isLoaded,
                isModelItemEnabled = isLoaded && enabledPlatformsInChat.isNotEmpty(),
                onBackAction = onBackAction,
                scrollBehavior = scrollBehavior,
                onChatTitleItemClick = { isChatTitleDialogOpen = true },
                onChatModelItemClick = { isToolSelectionOpen = true },
                onExportChatItemClick = {
                    coroutineScope.launch {
                        val file = withContext(Dispatchers.IO) {
                            chatViewModel.exportChat(context)
                        }
                        if (file != null) {
                            val uri = getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_chat)))
                        } else {
                            Toast.makeText(context, context.getString(R.string.error_occurred), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = groupedMessages.userMessages,
                        key = { index, message -> chatMessagePairKey(message, index) }
                    ) { index, userMessage ->
                        val assistantRow = groupedMessages.assistantMessages.getOrNull(index).orEmpty()
                        ChatMessagePair(
                            message = userMessage,
                            messageIndex = index,
                            assistantMessages = assistantRow,
                            enabledPlatformsInChat = enabledPlatformsInChat,
                            enabledPlatformLookup = enabledPlatformLookup,
                            loadingStates = loadingStates,
                            isActiveMessage = index == groupedMessages.userMessages.lastIndex,
                            canUseChat = canUseChat,
                            isIdle = isIdle,
                            agentRunsById = agentRunsById,
                            runNoticesById = runNoticesById,
                            toolEventsByRun = toolEventsByRun,
                            maximumUserChatBubbleWidth = maximumUserChatBubbleWidth,
                            maximumOpponentChatBubbleWidth = maximumOpponentChatBubbleWidth,
                            onEditQuestion = { editIndex = index },
                            onCopyText = { text ->
                                clipboardManager.setClip(ClipEntry(ClipData.newPlainText("text", text)))
                                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            },
                            onSelectText = { text ->
                                selectTextContent = text
                                isSelectTextOpen = true
                            },
                            onRetry = { msgIdx, platformIdx ->
                                executeWithLocalNetworkPermission {
                                    chatViewModel.retry(msgIdx, platformIdx)
                                }
                            },
                            onEditAssistant = { msgIdx, platformIdx ->
                                editAssistantMessageIndex = msgIdx
                                editAssistantPlatformIndex = platformIdx
                                isEditAssistantDialogOpen = true
                            },
                            onFavoriteClick = {
                                assistantRow.getOrNull(chatViewModel.selectedPlatformIndex.value)?.let {
                                    chatViewModel.toggleFavorite(it)
                                }
                            },
                            onFavoriteLongPress = {},
                            onPlatformClick = { _, platformIdx ->
                                chatViewModel.selectPlatform(platformIdx)
                            },
                            platformIndexState = chatViewModel.selectedPlatformIndex.value,
                            onShowPreviousRevision = { msgIdx, platformIdx ->
                                chatViewModel.showPreviousRevision(msgIdx, platformIdx)
                            },
                            onShowNextRevision = { msgIdx, platformIdx ->
                                chatViewModel.showNextRevision(msgIdx, platformIdx)
                            }
                        )
                    }
                }

                if (!isFollowingBottom && listState.canScrollForward) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                isFollowingBottom = true
                                listState.animateScrollToLatestChatMessage()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.scroll_to_bottom_icon)
                        )
                    }
                }
            }

            ChatInputArea(
                chatViewModel = chatViewModel,
                canUseChat = canUseChat,
                isIdle = isIdle,
                anyPlatformDisabled = anyPlatformDisabled,
                activeRuns = activeRuns,
                onExecuteWithPermission = ::executeWithLocalNetworkPermission,
                onNavigateToLocalModels = onNavigateToLocalModels
            )
        }
    }

    if (isChatTitleDialogOpen) {
        ChatTitleDialog(
            currentTitle = chatRoom.name,
            onDismiss = { isChatTitleDialogOpen = false },
            onConfirm = { newTitle ->
                chatViewModel.updateChatRoomName(newTitle)
                isChatTitleDialogOpen = false
            }
        )
    }

    if (isToolSelectionOpen) {
        ChatToolSelectionBottomSheet(
            enabledPlatforms = enabledPlatformsInChat,
            platformLookup = enabledPlatformLookup,
            onDismiss = { isToolSelectionOpen = false }
        )
    }

    if (isSelectTextOpen) {
        SelectTextBottomSheet(
            content = selectTextContent,
            onDismiss = { isSelectTextOpen = false }
        )
    }

    ChatBottomAutoScroller(
        listState = listState,
        isEnabled = shouldAutoScrollToBottom(
            isFollowing = isFollowingBottom,
            isUserDragging = isUserDragging,
            isScrollInProgress = listState.isScrollInProgress,
            isScrollingAway = false
        )
    )
}

@Composable
fun ChatMessagePair(
    message: MessageV2,
    messageIndex: Int,
    assistantMessages: List<MessageV2>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    loadingStates: List<ChatViewModel.LoadingState>,
    isActiveMessage: Boolean,
    canUseChat: Boolean,
    isIdle: Boolean,
    agentRunsById: Map<String, AgentRun>,
    runNoticesById: Map<String, List<dev.chungjungsoo.gptmobile.data.database.entity.RunNotice>>,
    toolEventsByRun: Map<String, List<ToolEvent>>,
    maximumUserChatBubbleWidth: Dp,
    maximumOpponentChatBubbleWidth: Dp,
    onEditQuestion: (MessageV2) -> Unit,
    onCopyText: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onFavoriteClick: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    platformIndexState: Int,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit
) {
    val selectedAssistantMessage = assistantMessages.getOrNull(platformIndexState)
    val assistantContent = selectedAssistantMessage?.effectiveContent() ?: ""
    val assistantThoughts = selectedAssistantMessage?.effectiveThoughts() ?: ""
    val assistantTimeline = selectedAssistantMessage?.effectiveTimeline().orEmpty()
    val selectedRunId = selectedAssistantMessage?.effectiveRunId()
    val agentRun = selectedRunId?.let(agentRunsById::get)
    val toolEvents = selectedRunId?.let(toolEventsByRun::get).orEmpty()
    val canShowPreviousRevision = selectedAssistantMessage?.let { assistantMessage ->
        assistantMessage.revisions.isNotEmpty() &&
            assistantMessage.activeRevisionIndex < assistantMessage.revisions.lastIndex
    } ?: false
    val canShowNextRevision = selectedAssistantMessage?.let { assistantMessage ->
        assistantMessage.revisions.isNotEmpty() &&
            assistantMessage.activeRevisionIndex != ACTIVE_REVISION_LATEST
    } ?: false
    val selectedPlatformUid = enabledPlatformsInChat.getOrElse(platformIndexState) { "" }
    val isCurrentPlatformLoading =
        loadingStates.getOrElse(platformIndexState) { ChatViewModel.LoadingState.Idle } == ChatViewModel.LoadingState.Loading
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Expandable details section placed directly above user input bubble
        if (selectedAssistantMessage != null && assistantTimeline.any { it.type == AssistantTimelineItemType.TOOL }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // Inline status indicator/details badge above question
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                UserChatBubble(
                    modifier = Modifier.widthIn(max = maximumUserChatBubbleWidth),
                    text = message.content,
                    files = message.attachments.map { it.filePathForDisplay },
                    onLongPress = { isDropDownMenuExpanded = true }
                )
                ChatBubbleDropdownMenu(
                    isChatBubbleDropdownMenuExpanded = isDropDownMenuExpanded,
                    canEdit = canUseChat && isIdle,
                    onDismissRequest = { isDropDownMenuExpanded = false },
                    onEditItemClick = { onEditQuestion(message) },
                    onCopyItemClick = { onCopyText(message.content) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OpponentResponseContainer(
                isFavorite = selectedAssistantMessage?.isFavorite ?: false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GPTMobileIcon(loading = shouldShowReplyLoadingIndicator(isActiveMessage, loadingStates))
                    if (enabledPlatformsInChat.size > 1) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            enabledPlatformsInChat.forEachIndexed { platformIndex, uid ->
                                PlatformButton(
                                    isLoading = isActiveMessage && loadingStates[platformIndex] == ChatViewModel.LoadingState.Loading,
                                    name = enabledPlatformLookup[uid]?.name ?: stringResource(R.string.unknown),
                                    selected = platformIndexState == platformIndex,
                                    onPlatformClick = { onPlatformClick(messageIndex, platformIndex) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }
                }
                OpponentChatBubble(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .widthIn(max = maximumOpponentChatBubbleWidth),
                    canEdit = canUseChat && isIdle,
                    canRetry = canUseChat && isActiveMessage && !isCurrentPlatformLoading,
                    isLoading = isActiveMessage && isCurrentPlatformLoading,
                    isError = agentRun?.status == AgentRunStatus.FAILED && isAssistantErrorMessage(assistantContent),
                    isFavorite = selectedAssistantMessage?.isFavorite ?: false,
                    text = assistantContent,
                    thoughts = assistantThoughts,
                    timeline = assistantTimeline,
                    attachments = selectedAssistantMessage?.attachments.orEmpty().map { it.filePathForDisplay },
                    agentRun = agentRun,
                    runNotices = selectedRunId?.let(runNoticesById::get).orEmpty(),
                    toolEvents = toolEvents,
                    contentIdentity = "$messageIndex:$selectedPlatformUid:${selectedRunId.orEmpty()}:${selectedAssistantMessage?.activeRevisionIndex}",
                    revisionIndexLabel = selectedAssistantMessage?.let { assistantMessage ->
                        val totalRevisions = assistantMessage.revisions.size + 1
                        if (assistantMessage.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
                            stringResource(
                                R.string.revision_counter,
                                totalRevisions,
                                totalRevisions
                            )
                        } else {
                            stringResource(
                                R.string.revision_counter,
                                assistantMessage.revisions.size - assistantMessage.activeRevisionIndex,
                                totalRevisions
                            )
                        }
                    },
                    canShowPreviousRevision = canShowPreviousRevision,
                    canShowNextRevision = canShowNextRevision,
                    onCopyClick = { onCopyText(assistantContent) },
                    onSelectClick = { onSelectText(assistantContent) },
                    onRetryClick = { onRetry(messageIndex, platformIndexState) },
                    onEditClick = { onEditAssistant(messageIndex, platformIndexState) },
                    onFavoriteClick = onFavoriteClick,
                    onFavoriteLongPress = onFavoriteLongPress,
                    onShowPreviousRevision = { onShowPreviousRevision(messageIndex, platformIndexState) },
                    onShowNextRevision = { onShowNextRevision(messageIndex, platformIndexState) }
                )
            }
        }
    }
}

private fun chatMessagePairKey(message: MessageV2, index: Int): String = if (message.id > 0) {
    "message-${message.id}"
} else {
    "message-${message.createdAt}-$index"
}

@Composable
internal fun rememberChatListState(
    messageCount: Int,
    hasTargetMessage: Boolean = false
): LazyListState = key(if (hasTargetMessage) "target" else (messageCount > 0)) {
    rememberLazyListState(initialFirstVisibleItemIndex = if (hasTargetMessage) 0 else messageCount)
}

internal fun nextFollowBottom(
    isFollowing: Boolean,
    isUserScrolling: Boolean,
    isScrollingAway: Boolean,
    canScrollForward: Boolean
): Boolean = when {
    !canScrollForward -> true
    isUserScrolling && isScrollingAway -> false
    else -> isFollowing
}

internal fun shouldAutoScrollToBottom(
    isFollowing: Boolean,
    isUserDragging: Boolean,
    isScrollInProgress: Boolean,
    isScrollingAway: Boolean
): Boolean = isFollowing &&
    !isUserDragging &&
    !(isScrollInProgress && isScrollingAway)

@Composable
internal fun ChatBottomAutoScroller(
    listState: LazyListState,
    isEnabled: Boolean
) {
    LaunchedEffect(listState, isEnabled) {
        if (!isEnabled) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo }
            .collectLatest { layoutInfo ->
                val latestItemIndex = layoutInfo.totalItemsCount - 1
                if (latestItemIndex >= 0 && listState.canScrollForward) {
                    listState.requestScrollToItem(latestItemIndex)
                }
            }
    }
}

internal suspend fun LazyListState.animateScrollToLatestChatMessage() {
    val latestItemIndex = layoutInfo.totalItemsCount - 1
    if (latestItemIndex >= 0) {
        animateScrollToItem(latestItemIndex)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatTopBar(
    title: String,
    isMenuItemEnabled: Boolean,
    isModelItemEnabled: Boolean,
    onBackAction: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onChatTitleItemClick: () -> Unit,
    onChatModelItemClick: () -> Unit,
    onExportChatItemClick: () -> Unit
) {
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(
                onClick = onBackAction
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        actions = {
            IconButton(
                enabled = isModelItemEnabled,
                onClick = onChatModelItemClick
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_model),
                    contentDescription = stringResource(R.string.chat_models)
                )
            }
            IconButton(
                onClick = { isDropDownMenuExpanded = isDropDownMenuExpanded.not() }
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.options))
            }

            ChatDropdownMenu(
                isDropdownMenuExpanded = isDropDownMenuExpanded,
                isMenuItemEnabled = isMenuItemEnabled,
                onDismissRequest = { isDropDownMenuExpanded = false },
                onChatTitleItemClick = {
                    onChatTitleItemClick.invoke()
                    isDropDownMenuExpanded = false
                },
                onExportChatItemClick = onExportChatItemClick
            )
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun ChatDropdownMenu(
    isDropdownMenuExpanded: Boolean,
    isMenuItemEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onChatTitleItemClick: () -> Unit,
    onExportChatItemClick: () -> Unit
) {
    DropdownMenu(
        expanded = isDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            enabled = isMenuItemEnabled,
            text = { Text(stringResource(R.string.update_chat_title)) },
            onClick = onChatTitleItemClick
        )
        DropdownMenuItem(
            enabled = isMenuItemEnabled,
            text = { Text(stringResource(R.string.export_chat)) },
            onClick = onExportChatItemClick
        )
    }
}

@Composable
private fun ChatBubbleDropdownMenu(
    isChatBubbleDropdownMenuExpanded: Boolean,
    canEdit: Boolean,
    onDismissRequest: () -> Unit,
    onEditItemClick: () -> Unit,
    onCopyItemClick: () -> Unit
) {
    DropdownMenu(
        expanded = isChatBubbleDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        if (canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                onClick = {
                    onEditItemClick.invoke()
                    onDismissRequest.invoke()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.copy_text)) },
            onClick = {
                onCopyItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
    }
}

@Composable
private fun PlatformButton(
    isLoading: Boolean,
    name: String,
    selected: Boolean,
    onPlatformClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onPlatformClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 6.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatInputArea(
    chatViewModel: ChatViewModel,
    canUseChat: Boolean,
    isIdle: Boolean,
    anyPlatformDisabled: Boolean,
    activeRuns: Map<String, AgentRun>,
    onExecuteWithPermission: (() -> Unit) -> Unit,
    onNavigateToLocalModels: () -> Unit
) {
    val textFieldState = rememberTextFieldState()
    val isSending = !isIdle
    val canSend = textFieldState.text.isNotBlank() && canUseChat && isIdle

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (anyPlatformDisabled) {
                Text(
                    text = stringResource(R.string.some_platforms_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicTextField(
                    state = textFieldState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    lineLimits = TextFieldLineLimits.MultiLine(1, 5),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorator = { innerTextField ->
                        if (textFieldState.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.ask_a_question),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                )

                if (isSending) {
                    IconButton(
                        onClick = { chatViewModel.cancelAllRuns() }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.cancel_active_runs),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        enabled = canSend,
                        onClick = {
                            val text = textFieldState.text.toString().trim()
                            if (text.isNotBlank()) {
                                onExecuteWithPermission {
                                    chatViewModel.sendMessage(text)
                                    textFieldState.clearText()
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_send),
                            contentDescription = stringResource(R.string.send),
                            tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTitleDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(currentTitle) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_chat_title)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = title.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectTextBottomSheet(
    content: String,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

internal fun shouldShowReplyLoadingIndicator(
    isActiveMessage: Boolean,
    loadingStates: List<ChatViewModel.LoadingState>
): Boolean = isActiveMessage && loadingStates.any { it == ChatViewModel.LoadingState.Loading }
