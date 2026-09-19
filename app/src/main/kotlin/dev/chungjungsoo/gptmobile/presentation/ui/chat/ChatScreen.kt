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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val systemChatMargin = 16.dp
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
    val indexStates by chatViewModel.indexStates.collectAsStateWithLifecycle()
    val loadingStates by chatViewModel.loadingStates.collectAsStateWithLifecycle()
    val isChatTitleDialogOpen by chatViewModel.isChatTitleDialogOpen.collectAsStateWithLifecycle()
    val isChatModelDialogOpen by chatViewModel.isChatModelDialogOpen.collectAsStateWithLifecycle()
    val messageEditSession by chatViewModel.messageEditSession.collectAsStateWithLifecycle()
    val isSelectTextSheetOpen by chatViewModel.isSelectTextSheetOpen.collectAsStateWithLifecycle()
    val selectedAttachments by chatViewModel.selectedAttachments.collectAsStateWithLifecycle()
    val attachmentNotice by chatViewModel.attachmentNotice.collectAsStateWithLifecycle()
    val needsLocalNetworkAccess by chatViewModel.needsLocalNetworkAccess.collectAsStateWithLifecycle()
    val appEnabledPlatforms by chatViewModel.enabledPlatformsInApp.collectAsStateWithLifecycle()
    val appAllPlatforms by chatViewModel.platformsInApp.collectAsStateWithLifecycle()
    val chatPlatformModels by chatViewModel.chatPlatformModels.collectAsStateWithLifecycle()
    val downloadedLocalModels by chatViewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val debugMode by chatViewModel.debugMode.collectAsStateWithLifecycle()
    val enabledPlatformLookup = remember(appEnabledPlatforms) { appEnabledPlatforms.associateBy { it.uid } }
    val canUseChat = (chatViewModel.enabledPlatformsInChat.toSet() - appEnabledPlatforms.map { it.uid }.toSet()).isEmpty()
    val isIdle = loadingStates.all { it == ChatViewModel.LoadingState.Idle }
    val context = LocalContext.current
    val lastMessageIndex = groupedMessages.userMessages.lastIndex
    var previousMessageCount by rememberSaveable { mutableIntStateOf(groupedMessages.userMessages.size) }
    var requestedNotificationPermission by rememberSaveable { mutableStateOf(false) }
    var sendAfterNotificationPermission by rememberSaveable { mutableStateOf(false) }
    var sendAfterLocalNetworkPermission by rememberSaveable { mutableStateOf(false) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && sendAfterLocalNetworkPermission) {
            chatViewModel.askQuestion()
            focusManager.clearFocus()
        } else if (!granted) {
            Toast.makeText(context, R.string.local_network_permission_required, Toast.LENGTH_SHORT).show()
        }
        sendAfterLocalNetworkPermission = false
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestedNotificationPermission = true
        if (sendAfterNotificationPermission) {
            if (needsLocalNetworkAccess &&
                Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) {
                sendAfterLocalNetworkPermission = true
                localNetworkPermissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
            } else {
                chatViewModel.askQuestion()
                focusManager.clearFocus()
            }
        }
        sendAfterNotificationPermission = false
    }

    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        chatViewModel.refreshLocalNetworkRequirement()
    }

    LaunchedEffect(isLoaded, groupedMessages.userMessages.size) {
        if (isLoaded && !hasScrolledToTarget && chatViewModel.targetMessageId > 0) {
            val targetTurn = groupedMessages.userMessages.indices.firstOrNull { turn ->
                val userMatched = groupedMessages.userMessages.getOrNull(turn)?.id == chatViewModel.targetMessageId
                val assistantMatched = groupedMessages.assistantMessages.getOrNull(turn)?.any { it.id == chatViewModel.targetMessageId } == true
                userMatched || assistantMatched
            }
            if (targetTurn != null) {
                hasScrolledToTarget = true
                isFollowingBottom = false
                val assistantList = groupedMessages.assistantMessages.getOrNull(targetTurn).orEmpty()
                val targetPlatformIndex = assistantList.indexOfFirst { it.id == chatViewModel.targetMessageId }
                if (targetPlatformIndex >= 0) {
                    chatViewModel.updateChatPlatformIndex(targetTurn, targetPlatformIndex)
                }
                listState.scrollToItem(targetTurn)
            }
        }
    }

    LaunchedEffect(isUserDragging, listState.isScrollInProgress, listState.canScrollForward, listState.lastScrolledBackward) {
        if (!hasTargetMessage) {
            isFollowingBottom = nextFollowBottom(
                isFollowing = isFollowingBottom,
                isUserScrolling = isUserDragging || listState.isScrollInProgress,
                isScrollingAway = listState.lastScrolledBackward,
                canScrollForward = listState.canScrollForward
            )
        }
    }

    LaunchedEffect(groupedMessages.userMessages.size) {
        val currentCount = groupedMessages.userMessages.size
        if (currentCount > previousMessageCount && !hasTargetMessage) {
            isFollowingBottom = true
        }
        previousMessageCount = currentCount
    }

    ChatBottomAutoScroller(
        listState = listState,
        isEnabled = !hasTargetMessage && shouldAutoScrollToBottom(
            isFollowing = isFollowingBottom,
            isUserDragging = isUserDragging,
            isScrollInProgress = listState.isScrollInProgress,
            isScrollingAway = listState.lastScrolledBackward
        )
    )

    LaunchedEffect(attachmentNotice) {
        attachmentNotice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            chatViewModel.consumeAttachmentNotice()
        }
    }

    Scaffold(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                title = chatRoom.title,
                isMenuItemEnabled = chatRoom.id > 0,
                isModelItemEnabled = chatViewModel.enabledPlatformsInChat.isNotEmpty(),
                onBackAction = onBackAction,
                scrollBehavior = scrollBehavior,
                onChatTitleItemClick = chatViewModel::openChatTitleDialog,
                onChatModelItemClick = chatViewModel::openChatModelDialog,
                onExportChatItemClick = { exportChat(context, chatViewModel) },
                onDisablePlatformClick = {
                    Toast.makeText(context, R.string.disable_platform, Toast.LENGTH_SHORT).show()
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState
                ) {
                    itemsIndexed(
                        items = groupedMessages.userMessages,
                        key = { index, message -> chatMessagePairKey(message, index) }
                    ) { index, message ->
                        ChatMessagePair(
                            messageIndex = index,
                            message = message,
                            assistantMessages = groupedMessages.assistantMessages.getOrNull(index) ?: emptyList(),
                            agentRunsById = agentRunsById,
                            runNoticesById = runNoticesById,
                            toolEventsByRun = toolEventsByRun,
                            platformIndexState = indexStates.getOrElse(index) { 0 },
                            loadingStates = loadingStates,
                            enabledPlatformsInChat = chatViewModel.enabledPlatformsInChat,
                            enabledPlatformLookup = enabledPlatformLookup,
                            canUseChat = canUseChat,
                            isIdle = isIdle,
                            isActiveMessage = index == lastMessageIndex,
                            maximumUserChatBubbleWidth = maximumUserChatBubbleWidth,
                            maximumOpponentChatBubbleWidth = maximumOpponentChatBubbleWidth,
                            debugMode = debugMode,
                            onEditQuestion = chatViewModel::openUserMessageEditDialog,
                            onEditAssistant = chatViewModel::openAssistantMessageEditDialog,
                            onCopyText = { copiedText ->
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copiedText, copiedText)))
                                }
                            },
                            onPlatformClick = chatViewModel::updateChatPlatformIndex,
                            onSelectText = chatViewModel::openSelectTextSheet,
                            onRetry = chatViewModel::retryChat,
                            onFavoriteClick = { chatViewModel.toggleMessageFavorite(index, indexStates.getOrElse(index) { 0 }) },
                            onFavoriteLongPress = {
                                Toast.makeText(context, R.string.favorite, Toast.LENGTH_SHORT).show()
                            },
                            onShowPreviousRevision = chatViewModel::showPreviousAssistantRevision,
                            onShowNextRevision = chatViewModel::showNextAssistantRevision,
                            onContinueClick = { chatViewModel.sendContinueResponse() },
                            onActionClick = { prompt -> chatViewModel.sendPromptResponse(prompt) }
                        )
                    }
                    if (groupedMessages.userMessages.isNotEmpty()) {
                        item(key = "chat-bottom-anchor") {
                            Spacer(Modifier.size(1.dp))
                        }
                    }
                }

                if (!isFollowingBottom && listState.canScrollForward && !hasTargetMessage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ScrollToBottomButton {
                            scope.launch {
                                listState.animateScrollToLatestChatMessage()
                                isFollowingBottom = true
                            }
                        }
                    }
                }
            }

            ChatInputBox(
                inputState = chatViewModel.question,
                chatEnabled = canUseChat,
                sendButtonEnabled = isIdle,
                isRunning = !isIdle,
                selectedAttachments = selectedAttachments,
                onFileSelected = { filePath -> chatViewModel.addSelectedFile(filePath) },
                onFileRemoved = { filePath -> chatViewModel.removeSelectedFile(filePath) },
                onCancelButtonClick = chatViewModel::cancelActiveRuns
            ) {
                if (!requestedNotificationPermission &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    sendAfterNotificationPermission = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (needsLocalNetworkAccess &&
                    Build.VERSION.SDK_INT >= 37 &&
                    ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                ) {
                    sendAfterLocalNetworkPermission = true
                    localNetworkPermissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
                } else {
                    chatViewModel.askQuestion()
                    focusManager.clearFocus()
                }
            }
        }

        if (isChatTitleDialogOpen) {
            ChatTitleDialog(
                initialTitle = chatRoom.title,
                onDefaultTitleMode = chatViewModel::generateDefaultChatTitle,
                onConfirmRequest = { title -> chatViewModel.updateChatTitle(title) },
                onDismissRequest = chatViewModel::closeChatTitleDialog
            )
        }

        if (isChatModelDialogOpen) {
            val platformNames = chatViewModel.enabledPlatformsInChat.associateWith { uid ->
                appAllPlatforms.find { it.uid == uid }?.name ?: stringResource(R.string.unknown)
            }
            ChatModelDialog(
                platformOrder = chatViewModel.enabledPlatformsInChat,
                initialModels = chatPlatformModels,
                platformNames = platformNames,
                platformClientTypes = appAllPlatforms.associate { it.uid to it.compatibleType },
                downloadedLocalModels = downloadedLocalModels,
                onNavigateToLocalModels = onNavigateToLocalModels,
                onDismissRequest = chatViewModel::closeChatModelDialog,
                onConfirmRequest = { models ->
                    chatViewModel.updateChatPlatformModels(models)
                    chatViewModel.closeChatModelDialog()
                }
            )
        }

        messageEditSession?.let { session ->
            when (session.role) {
                ChatViewModel.MessageEditRole.USER -> {
                    UserMessageEditDialog(
                        initialQuestion = session.message,
                        attachments = session.attachments,
                        onFileSelected = chatViewModel::addMessageEditFile,
                        onCopyFailed = chatViewModel::notifyAttachmentCopyFailed,
                        onFileRemoved = chatViewModel::removeMessageEditFile,
                        onDismissRequest = chatViewModel::discardMessageEditDialog,
                        onConfirmRequest = { question ->
                            if (chatViewModel.saveUserMessageEdit(question, session.attachments)) {
                                chatViewModel.finishMessageEditDialog()
                            }
                        }
                    )
                }

                ChatViewModel.MessageEditRole.ASSISTANT -> {
                    AssistantMessageEditDialog(
                        initialResponse = session.message.content,
                        onDismissRequest = chatViewModel::discardMessageEditDialog,
                        onConfirmRequest = { response ->
                            if (chatViewModel.saveAssistantMessageEdit(
                                    turnIndex = session.turnIndex,
                                    platformIndex = session.platformIndex,
                                    editedContent = response
                                )
                            ) {
                                chatViewModel.finishMessageEditDialog()
                            }
                        }
                    )
                }
            }
        }

        if (isSelectTextSheetOpen) {
            SelectTextBottomSheet(
                text = chatViewModel.selectedText.value,
                onDismissRequest = chatViewModel::closeSelectTextSheet
            )
        }
    }
}

@Composable
fun ChatMessagePair(
    messageIndex: Int,
    message: MessageV2,
    assistantMessages: List<MessageV2>,
    agentRunsById: Map<String, AgentRun>,
    runNoticesById: Map<String, List<ChatRunNotice>>,
    toolEventsByRun: Map<String, List<ToolEvent>>,
    platformIndexState: Int,
    loadingStates: List<ChatViewModel.LoadingState>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    canUseChat: Boolean,
    isIdle: Boolean,
    isActiveMessage: Boolean,
    maximumUserChatBubbleWidth: Dp,
    maximumOpponentChatBubbleWidth: Dp,
    debugMode: Boolean = false,
    onEditQuestion: (MessageV2) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onCopyText: (String) -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    onFavoriteClick: () -> Unit,
    onFavoriteLongPress: () -> Unit,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit,
    onContinueClick: () -> Unit = {},
    onActionClick: (String) -> Unit = {}
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                UserChatBubble(
                    modifier = Modifier.widthIn(max = maximumUserChatBubbleWidth),
                    text = message.content,
                    timestamp = message.createdAt * 1000L,
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
                .padding(horizontal = 8.dp, vertical = 12.dp)
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
                                .padding(horizontal = 8.dp)
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
                        .padding(horizontal = 2.dp)
                        .widthIn(max = maximumOpponentChatBubbleWidth),
                    canEdit = canUseChat && isIdle,
                    canRetry = canUseChat && isActiveMessage && !isCurrentPlatformLoading,
                    isLoading = isActiveMessage && isCurrentPlatformLoading,
                    isError = agentRun?.status == AgentRunStatus.FAILED && isAssistantErrorMessage(assistantContent),
                    isFavorite = selectedAssistantMessage?.isFavorite ?: false,
                    debugMode = debugMode,
                    text = assistantContent,
                    timestamp = selectedAssistantMessage?.let { it.createdAt * 1000L },
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
                    onShowNextRevision = { onShowNextRevision(messageIndex, platformIndexState) },
                    onContinueClick = onContinueClick,
                    onActionClick = onActionClick
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
): Boolean {
    if (!canScrollForward) return true
    if (isUserScrolling && isScrollingAway) return false
    return isFollowing
}

private suspend fun LazyListState.animateScrollToLatestChatMessage() {
    val totalItemCount = layoutInfo.totalItemsCount
    if (totalItemCount <= 0) return
    animateScrollToItem(totalItemCount - 1)
}

private fun shouldShowReplyLoadingIndicator(
    isActiveMessage: Boolean,
    loadingStates: List<ChatViewModel.LoadingState>
): Boolean = isActiveMessage && loadingStates.any { it == ChatViewModel.LoadingState.Loading }
