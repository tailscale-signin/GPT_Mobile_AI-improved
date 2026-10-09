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
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet as ModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

private fun Modifier.chatViewportEdgeFade(
    topFadeStart: Dp,
    topFade: Dp,
    bottomFadeStartFromBottom: Dp,
    bottomFadeEndFromBottom: Dp
): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        if (size.height <= 0f) return@drawWithContent

        val topStart = (topFadeStart.toPx() / size.height).coerceIn(0f, 0.45f)
        val topStop = (topFade.toPx() / size.height).coerceIn(topStart, 0.5f)
        val bottomOpaqueStop = (1f - (bottomFadeStartFromBottom.toPx() / size.height))
            .coerceIn(topStop, 0.96f)
        val bottomTransparentStop = (1f - (bottomFadeEndFromBottom.toPx() / size.height))
            .coerceIn(bottomOpaqueStop, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                topStart to Color.Transparent,
                topStop to Color.Black,
                bottomOpaqueStop to Color.Black,
                bottomTransparentStop to Color.Transparent,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {},
    onOpenConversation: (Int, Boolean) -> Unit = { _, _ -> },
    onInspectContext: ((Int, String) -> Unit)? = null
) {
    val chatRoom by chatViewModel.chatRoom.collectAsStateWithLifecycle()
    val branchNavigation by chatViewModel.branchNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(branchNavigation) {
        branchNavigation?.let {
            chatViewModel.consumeBranchNavigation()
            onOpenConversation(it, true)
        }
    }
    val delegationRecoveryRequests by chatViewModel.pendingDelegationRecovery.collectAsStateWithLifecycle()
    delegationRecoveryRequests.firstOrNull { it.chatId == chatRoom.id }?.let { request ->
        DelegationRecoveryDialog(
            request = request,
            onSwitch = { profileUid -> chatViewModel.respondDelegationRecovery(request.id, profileUid) },
            onCancelDelegation = { chatViewModel.respondDelegationRecovery(request.id, null) }
        )
    }
    val inputRequests by chatViewModel.pendingMcpInput.collectAsStateWithLifecycle()
    inputRequests.firstOrNull()?.let { McpInputDialog(it, chatViewModel::respondMcpInput) }
    val approvals by chatViewModel.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    approvals.firstOrNull()?.let { approval ->
        ToolApprovalDialog(
            approval = approval,
            onDeny = { chatViewModel.decideToolApproval(approval.id, false) },
            onAllowOnce = { chatViewModel.decideToolApproval(approval.id, true) },
            onAllowInConversation = { chatViewModel.allowToolInConversation(approval.id) },
            onAlwaysAllowTool = { chatViewModel.alwaysAllowTool(approval.id) },
            onAlwaysAllowProvider = { chatViewModel.alwaysAllowToolProvider(approval.id) }
        )
    }
    val freeToolConsent by chatViewModel.pendingFreeToolConsent.collectAsStateWithLifecycle()
    freeToolConsent?.let { request ->
        FreeToolConsentDialog(
            request = request,
            onAccept = chatViewModel::confirmFreeToolConsent,
            onDismiss = chatViewModel::dismissFreeToolConsent
        )
    }
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val systemChatMargin = 16.dp
    val maximumUserChatBubbleWidth = (screenWidthDp - systemChatMargin) * 0.8F
    val maximumOpponentChatBubbleWidth = screenWidthDp - systemChatMargin
    val hasOlderHistory by chatViewModel.olderHistoryAvailable.collectAsStateWithLifecycle()
    val groupedMessages by chatViewModel.groupedMessages.collectAsStateWithLifecycle()
    val featureSettings by chatViewModel.featureSettings.collectAsStateWithLifecycle()
    val invocationDiagnostics by chatViewModel.invocationDiagnostics.collectAsStateWithLifecycle()
    val hasTargetMessage = chatViewModel.targetMessageId > 0
    val hasTargetAssistant = hasTargetMessage && groupedMessages.assistantMessages.any { responses -> responses.any { it.id == chatViewModel.targetMessageId } }
    var revealedArchivedTurns by rememberSaveable(chatRoom.id) { mutableIntStateOf(0) }
    val shouldCollapseHistory = featureSettings.archiveOlderAssistantReplies &&
        !hasTargetMessage &&
        groupedMessages.userMessages.size > RECENT_EXPANDED_TURNS
    val firstVisibleTurn = if (shouldCollapseHistory) {
        (groupedMessages.userMessages.size - RECENT_EXPANDED_TURNS - revealedArchivedTurns).coerceAtLeast(0)
    } else {
        0
    }
    val hiddenTurnCount = firstVisibleTurn
    val visibleTurnCount = groupedMessages.userMessages.size - firstVisibleTurn
    val historyHeaderCount = (if (hiddenTurnCount > 0) 1 else 0) + (if (hasOlderHistory) 1 else 0)
    val listState = rememberChatListState(
        messageCount = visibleTurnCount + historyHeaderCount,
        hasTargetMessage = hasTargetMessage
    )
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var isFollowingBottom by remember { mutableStateOf(!hasTargetMessage) }
    var isHoldingEntryCenter by remember { mutableStateOf(hasTargetMessage) }
    var isModelTabPositionLocked by remember { mutableStateOf(false) }
    var entryPositioned by remember { mutableStateOf(false) }
    var targetResponseOffset by remember(chatViewModel.targetMessageId) { mutableStateOf<Int?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showExportOptions by remember { mutableStateOf(false) }
    val isLoaded by chatViewModel.isLoaded.collectAsStateWithLifecycle()
    val agentRunsById by chatViewModel.agentRunsById.collectAsStateWithLifecycle()
    val activeAgentRuns by chatViewModel.activeAgentRuns.collectAsStateWithLifecycle()
    val runNoticesById by chatViewModel.runNoticesById.collectAsStateWithLifecycle()
    val toolEventsByRun by chatViewModel.toolEventsByRun.collectAsStateWithLifecycle()
    val indexStates by chatViewModel.indexStates.collectAsStateWithLifecycle()
    val loadingStates by chatViewModel.loadingStates.collectAsStateWithLifecycle()

    LaunchedEffect(chatRoom.id, groupedMessages, activeAgentRuns) {
        if (chatRoom.id > 0) {
            chatViewModel.markCurrentChatViewed()
        }
    }
    val disabledPlatformUids by chatViewModel.disabledPlatformUids.collectAsStateWithLifecycle()
    val activePlatformUids by chatViewModel.activePlatformUids.collectAsStateWithLifecycle()
    val isChatTitleDialogOpen by chatViewModel.isChatTitleDialogOpen.collectAsStateWithLifecycle()
    val isChatModelDialogOpen by chatViewModel.isChatModelDialogOpen.collectAsStateWithLifecycle()
    val messageEditSession by chatViewModel.messageEditSession.collectAsStateWithLifecycle()
    val isSelectTextSheetOpen by chatViewModel.isSelectTextSheetOpen.collectAsStateWithLifecycle()
    val selectedAttachments by chatViewModel.selectedAttachments.collectAsStateWithLifecycle()
    val pendingPrompts by chatViewModel.pendingPrompts.collectAsStateWithLifecycle()
    val followUpProgress by chatViewModel.followUpProgress.collectAsStateWithLifecycle()
    val queuedPromptCount by chatViewModel.queuedPromptCount.collectAsStateWithLifecycle()
    val attachmentNotice by chatViewModel.attachmentNotice.collectAsStateWithLifecycle()
    val needsLocalNetworkAccess by chatViewModel.needsLocalNetworkAccess.collectAsStateWithLifecycle()
    val appEnabledPlatforms by chatViewModel.enabledPlatformsInApp.collectAsStateWithLifecycle()
    val appAllPlatforms by chatViewModel.platformsInApp.collectAsStateWithLifecycle()
    LaunchedEffect(chatRoom.id, appAllPlatforms, groupedMessages.userMessages.size, groupedMessages.assistantMessages.size) { chatViewModel.resumeBranchIfReady() }
    val chatPlatformModels by chatViewModel.chatPlatformModels.collectAsStateWithLifecycle()
    val availableChatTools by chatViewModel.availableChatTools.collectAsStateWithLifecycle()
    val chatToolConfig by chatViewModel.chatToolConfig.collectAsStateWithLifecycle()
    val downloadedLocalModels by chatViewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val debugMode by chatViewModel.debugMode.collectAsStateWithLifecycle()
    val debugMemorySources by chatViewModel.debugMemorySources.collectAsStateWithLifecycle()
    val enabledPlatformLookup = remember(appAllPlatforms) { appAllPlatforms.associateBy { it.uid } }
    val enabledProfileUids = remember(appEnabledPlatforms) { appEnabledPlatforms.mapTo(mutableSetOf()) { it.uid } }
    val canUseChat = activePlatformUids.isNotEmpty() && activePlatformUids.all { it in enabledProfileUids }
    val isIdle = loadingStates.all { it == ChatViewModel.LoadingState.Idle }
    val context = LocalContext.current
    val lastMessageIndex = groupedMessages.userMessages.lastIndex
    var inspectedCombinedTurn by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    var inspectedCombinedProfileUid by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    var combinedTargetSelected by rememberSaveable(chatRoom.id, chatViewModel.targetMessageId) { mutableStateOf(false) }
    LaunchedEffect(isLoaded, hasTargetAssistant) {
        if (isLoaded && hasTargetAssistant && !combinedTargetSelected) {
            if (chatRoom.conversationMode == ConversationMode.COMBINED) {
                val turnIndex = groupedMessages.assistantMessages.indexOfFirst { responses ->
                    responses.any { it.id == chatViewModel.targetMessageId }
                }
                val target = groupedMessages.assistantMessages.getOrNull(turnIndex)
                    ?.firstOrNull { it.id == chatViewModel.targetMessageId }
                val question = groupedMessages.userMessages.getOrNull(turnIndex)
                if (target != null && !target.isCombinedSynthesis() && question != null) {
                    inspectedCombinedTurn = chatMessagePairKey(question, turnIndex)
                    inspectedCombinedProfileUid = target.platformType
                }
            }
            combinedTargetSelected = true
        }
    }
    val inspectedTurnIndex = groupedMessages.userMessages.withIndex().indexOfFirst { (index, message) ->
        chatMessagePairKey(message, index) == inspectedCombinedTurn
    }
    val inspectableProfiles = if (inspectedCombinedProfileUid != null) {
        combinedResponseProfiles(
            responses = groupedMessages.assistantMessages.getOrNull(inspectedTurnIndex).orEmpty(),
            platformUids = chatViewModel.enabledPlatformsInChat,
            profileNames = emptyMap(),
            participatingUids = activePlatformUids.toSet() - disabledPlatformUids,
            preparing = inspectedTurnIndex == lastMessageIndex && !isIdle,
            runsById = agentRunsById
        )
    } else {
        emptyList()
    }
    val inspectingCombinedSource = chatRoom.conversationMode == ConversationMode.COMBINED &&
        (inspectableProfiles.size > 1 || groupedMessages.assistantMessages.getOrNull(inspectedTurnIndex).orEmpty().any { it.isCombinedSynthesis() }) &&
        inspectableProfiles.any { it.uid == inspectedCombinedProfileUid }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(inspectingCombinedSource) {
        if (inspectingCombinedSource) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
    val backFade = dev.chungjungsoo.gptmobile.presentation.common.rememberBackFade()
    val onBackFromChat: () -> Unit = {
        if (inspectingCombinedSource) {
            backFade.fade {
                inspectedCombinedTurn = null
                inspectedCombinedProfileUid = null
            }
        } else {
            chatViewModel.leaveConversation(onBackAction)
        }
    }
    androidx.activity.compose.BackHandler(onBack = onBackFromChat)
    var previousMessageCount by rememberSaveable { mutableIntStateOf(groupedMessages.userMessages.size) }
    var requestedNotificationPermission by rememberSaveable { mutableStateOf(false) }
    var sendAfterNotificationPermission by rememberSaveable { mutableStateOf(false) }
    var sendAfterLocalNetworkPermission by rememberSaveable { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            chatViewModel.setDeviceLocationEnabled(true)
        } else {
            Toast.makeText(context, "Location permission is needed to share phone coordinates with this conversation.", Toast.LENGTH_LONG).show()
        }
    }
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
    if (showExportOptions) {
        ChatExportDialog(onDismiss = { showExportOptions = false }) { format ->
            showExportOptions = false
            scope.launch { exportChat(context, chatViewModel, format) }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        chatViewModel.refreshLocalNetworkRequirement()
    }

    LaunchedEffect(isLoaded, groupedMessages.userMessages.size, visibleTurnCount, historyHeaderCount, pendingPrompts.size, combinedTargetSelected) {
        if (!isLoaded || entryPositioned || groupedMessages.userMessages.isEmpty()) return@LaunchedEffect
        if (hasTargetAssistant && !combinedTargetSelected) return@LaunchedEffect
        // The first measured layout can still contain the previous loading state.
        val expectedItemCount = visibleTurnCount + historyHeaderCount + pendingPrompts.size + 1
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= expectedItemCount }
        val targetTurn = groupedMessages.userMessages.indices.firstOrNull { turn ->
            groupedMessages.userMessages[turn].id == chatViewModel.targetMessageId ||
                groupedMessages.assistantMessages.getOrNull(turn)?.any { it.id == chatViewModel.targetMessageId } == true
        }
        if (hasTargetMessage && targetTurn != null) {
            isFollowingBottom = false
            val targetIndex = groupedMessages.assistantMessages.getOrNull(targetTurn).orEmpty()
                .indexOfFirst { it.id == chatViewModel.targetMessageId }
            if (targetIndex >= 0) chatViewModel.updateChatPlatformIndex(targetTurn, targetIndex)
            val itemIndex = targetTurn - firstVisibleTurn + historyHeaderCount
            listState.scrollToConversationEntry(itemIndex)
            if (targetIndex >= 0) {
                // A missing measurement must never leave the conversation invisible.
                val responseOffset = kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                    snapshotFlow { targetResponseOffset }.filterNotNull().first()
                }
                if (responseOffset != null) listState.scrollToConversationEntry(itemIndex, responseOffset, centerResponse = true)
            }
        } else {
            // Explicitly override a restored list position on each conversation entry.
            listState.scrollToConversationEntry()
            isFollowingBottom = true
        }
        entryPositioned = true
    }

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) isHoldingEntryCenter = false
    }

    LaunchedEffect(entryPositioned, isHoldingEntryCenter, hasTargetAssistant) {
        if (!entryPositioned || !isHoldingEntryCenter || !hasTargetAssistant) return@LaunchedEffect
        val targetTurn = groupedMessages.assistantMessages.indexOfFirst { responses -> responses.any { it.id == chatViewModel.targetMessageId } }
        val itemIndex = targetTurn - firstVisibleTurn + historyHeaderCount
        // The composer can shrink after entering a combined profile. Keep the
        // response centered as that animation finishes, until the user scrolls.
        snapshotFlow { Triple(listState.layoutInfo.beforeContentPadding, listState.layoutInfo.afterContentPadding, targetResponseOffset) }
            .distinctUntilChanged()
            .collect { (_, _, offset) ->
                if (offset != null) listState.scrollToConversationEntry(itemIndex, offset, centerResponse = true)
            }
    }

    LaunchedEffect(isUserDragging, listState.isScrollInProgress, listState.canScrollForward, listState.lastScrolledBackward) {
        if (isModelTabPositionLocked && isUserDragging) isModelTabPositionLocked = false
        if (entryPositioned && !isModelTabPositionLocked && (isFollowingBottom || isUserDragging || listState.isScrollInProgress)) {
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
        if (currentCount > previousMessageCount && entryPositioned) {
            isHoldingEntryCenter = false
            isModelTabPositionLocked = false
            isFollowingBottom = true
        }
        previousMessageCount = currentCount
    }

    var latestResponseOffset by remember(chatRoom.id, lastMessageIndex) { mutableIntStateOf(-1) }
    val responseBoundary = remember(chatRoom.id, lastMessageIndex) { LatestResponseBoundary() }
    val latestTurnItem = lastMessageIndex - firstVisibleTurn + historyHeaderCount
    var navigatedFromTarget by remember(chatRoom.id) { mutableStateOf(false) }
    LaunchedEffect(isUserDragging) { if (isUserDragging) navigatedFromTarget = true }
    val boundaryEnabled = isIdle && entryPositioned && (!hasTargetMessage || navigatedFromTarget) && latestResponseOffset >= 0
    val latestBoundaryEnabled by androidx.compose.runtime.rememberUpdatedState(boundaryEnabled)
    val latestBoundaryItem by androidx.compose.runtime.rememberUpdatedState(latestTurnItem)
    val minimumFlingVelocity = with(LocalDensity.current) { 1000.dp.toPx() }
    val boundaryConnection = remember(listState, responseBoundary, minimumFlingVelocity) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!latestBoundaryEnabled) return Offset.Zero
                val turn = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestBoundaryItem } ?: return Offset.Zero
                val responseTop = turn.offset + latestResponseOffset
                val consumed = responseBoundary.consume(available.y, -responseTop.toFloat(), android.os.SystemClock.elapsedRealtime(), source == NestedScrollSource.UserInput)
                return Offset(0f, consumed)
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (latestBoundaryEnabled) responseBoundary.beginFling(available.y, minimumFlingVelocity)
                return androidx.compose.ui.unit.Velocity.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                responseBoundary.endFling()
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    ChatBottomAutoScroller(
        listState = listState,
        animate = !isIdle,
        isEnabled = featureSettings.smoothStreaming &&
            entryPositioned &&
            !isModelTabPositionLocked &&
            shouldAutoScrollToBottom(
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
        modifier = Modifier.then(backFade.modifier)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ChatTopBar(
                title = chatRoom.title,
                isTitleCustomized = chatRoom.isTitleCustomized,
                isMenuItemEnabled = chatRoom.id > 0,
                isModelItemEnabled = chatViewModel.enabledPlatformsInChat.isNotEmpty(),
                onBackAction = onBackFromChat,
                scrollBehavior = scrollBehavior,
                onChatTitleItemClick = chatViewModel::openChatTitleDialog,
                onChatModelItemClick = chatViewModel::openChatModelDialog,
                onExportChatItemClick = { showExportOptions = true },
                onDisablePlatformClick = {
                    Toast.makeText(context, R.string.disable_platform, Toast.LENGTH_SHORT).show()
                }
            )
        }
    ) { innerPadding ->
        val density = LocalDensity.current
        var composerHeightPx by remember { mutableIntStateOf(with(density) { 88.dp.roundToPx() }) }
        val composerHeight = with(density) { composerHeightPx.toDp() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(boundaryConnection)
                    .pointerInput(responseBoundary) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            responseBoundary.beginGesture()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    // Position the measured list before revealing an existing chat.
                    // Restored positions and favourite targeting never animate on entry.
                    .graphicsLayer { alpha = if (entryPositioned || groupedMessages.userMessages.isEmpty()) 1f else 0f }
                    .chatViewportEdgeFade(
                        topFadeStart = if (featureSettings.edgeFades) (innerPadding.calculateTopPadding() + 16.dp - configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(0.dp) else 0.dp,
                        topFade = if (featureSettings.edgeFades) (innerPadding.calculateTopPadding() + 48.dp - configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(0.dp) else 0.dp,
                        // Content continues behind the composer. Fade it from the top edge
                        // of the input surface to transparent halfway through the bar.
                        bottomFadeStartFromBottom = if (featureSettings.edgeFades && !inspectingCombinedSource) composerHeight else 0.dp,
                        bottomFadeEndFromBottom = if (featureSettings.edgeFades && !inspectingCombinedSource) composerHeight * 0.5f else 0.dp
                    ),
                state = listState,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = composerHeight + 16.dp)
            ) {
                if (hasOlderHistory) item(key = "load-earlier-messages") { TextButton(onClick = chatViewModel::loadOlderMessages) { Text("Load earlier messages") } }
                if (hiddenTurnCount > 0) {
                    item(key = "archived-history-header") {
                        ArchivedHistoryHeader(
                            hiddenTurnCount = hiddenTurnCount,
                            onExpand = {
                                revealedArchivedTurns = (revealedArchivedTurns + ARCHIVE_REVEAL_STEP)
                                    .coerceAtMost(groupedMessages.userMessages.size)
                            }
                        )
                    }
                }
                itemsIndexed(
                    items = groupedMessages.userMessages.drop(firstVisibleTurn),
                    key = { visibleIndex, message ->
                        chatMessagePairKey(message, firstVisibleTurn + visibleIndex)
                    }
                ) { visibleIndex, message ->
                    val index = firstVisibleTurn + visibleIndex
                    ChatMessagePair(
                        messageIndex = index,
                        message = message,
                        assistantMessages = groupedMessages.assistantMessages.getOrNull(index) ?: emptyList(),
                        agentRunsById = agentRunsById,
                        activeAgentRuns = activeAgentRuns,
                        runNoticesById = runNoticesById,
                        toolEventsByRun = toolEventsByRun,
                        platformIndexState = indexStates.getOrElse(index) { 0 },
                        loadingStates = loadingStates,
                        enabledPlatformsInChat = chatViewModel.enabledPlatformsInChat,
                        enabledPlatformLookup = enabledPlatformLookup,
                        disabledPlatformUids = disabledPlatformUids,
                        activePlatformUids = activePlatformUids.toSet(),
                        canUseChat = canUseChat,
                        isIdle = isIdle,
                        isActiveMessage = index == lastMessageIndex,
                        maximumUserChatBubbleWidth = maximumUserChatBubbleWidth,
                        maximumOpponentChatBubbleWidth = maximumOpponentChatBubbleWidth,
                        debugMode = debugMode,
                        invocationDiagnostics = invocationDiagnostics,
                        debugSettings = featureSettings,
                        debugMemorySources = debugMemorySources,
                        showReasoning = featureSettings.showReasoning,
                        combinedMode = chatRoom.conversationMode == ConversationMode.COMBINED,
                        selectedCombinedProfileUid = inspectedCombinedProfileUid.takeIf {
                            inspectingCombinedSource && inspectedCombinedTurn == chatMessagePairKey(message, index)
                        },
                        onCombinedProfileClick = { uid ->
                            isHoldingEntryCenter = false
                            isFollowingBottom = false
                            isModelTabPositionLocked = true
                            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            inspectedCombinedTurn = chatMessagePairKey(message, index).takeIf { uid != null }
                            inspectedCombinedProfileUid = uid
                        },
                        smartSuggestionsEnabled = featureSettings.smartSuggestions,
                        isUserTyping = chatViewModel.question.text.isNotEmpty(),
                        targetMessageId = chatViewModel.targetMessageId,
                        onTargetResponseOffset = { targetResponseOffset = it },
                        onResponseOffset = { if (index == lastMessageIndex) latestResponseOffset = it },
                        onEditQuestion = chatViewModel::openUserMessageEditDialog,
                        onEditAssistant = chatViewModel::openAssistantMessageEditDialog,
                        onCopyText = { copiedText ->
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copiedText, copiedText)))
                            }
                        },
                        onPlatformClick = { turn, platform ->
                            isHoldingEntryCenter = false
                            isFollowingBottom = false
                            isModelTabPositionLocked = true
                            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            chatViewModel.updateChatPlatformIndex(turn, platform)
                        },
                        onPlatformLongPress = chatViewModel::togglePlatformDisabled,
                        onSelectText = chatViewModel::openSelectTextSheet,
                        onRetry = chatViewModel::retryChat,
                        onInspectContext = onInspectContext?.let { inspect ->
                            { run -> inspect(chatRoom.id, run) }
                        },
                        onFavoriteClick = chatViewModel::toggleMessageFavorite,
                        onFavoriteLongPress = {
                            Toast.makeText(context, R.string.favorite, Toast.LENGTH_SHORT).show()
                        },
                        onShowPreviousRevision = chatViewModel::showPreviousAssistantRevision,
                        onShowNextRevision = chatViewModel::showNextAssistantRevision,
                        onContinueClick = { chatViewModel.sendContinueResponse() },
                        onActionClick = { prompt -> chatViewModel.sendPromptResponse(prompt) }
                    )
                }
                items(pendingPrompts.size, key = { "queued-${pendingPrompts[it].id}" }) { index ->
                    QueuedPromptBubble(pendingPrompts[index], chatViewModel::editQueuedPrompt, chatViewModel::removeQueuedPrompt, chatViewModel::pauseQueuedPrompt, followUpProgress[pendingPrompts[index].id])
                }
                if (groupedMessages.userMessages.isNotEmpty()) {
                    item(key = "chat-bottom-anchor") {
                        Spacer(Modifier.size(1.dp))
                    }
                }
            }

            if (!isFollowingBottom && listState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = composerHeight + 12.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ScrollToBottomButton(isGenerating = !isIdle) {
                        navigatedFromTarget = true
                        isHoldingEntryCenter = false
                        isModelTabPositionLocked = false
                        scope.launch {
                            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                            isFollowingBottom = true
                        }
                    }
                }
            }

            CombinedChatComposer(
                visible = !inspectingCombinedSource,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
            ) {
                ChatInputBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    inputState = chatViewModel.question,
                    chatEnabled = canUseChat && !inspectingCombinedSource,
                    sendButtonEnabled = selectedAttachments.none { it.status != ChatAttachmentDraft.Status.Ready },
                    isRunning = !isIdle,
                    queuedPromptCount = queuedPromptCount,
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
            val dialogPlatformOrder = appAllPlatforms.filter { it.enabled }.map { it.uid }
            val platformNames = appAllPlatforms.associate { it.uid to it.name }
            val locationToolIds = availableChatTools.filter { tool ->
                val searchable = (tool.name + " " + tool.description + " " + tool.source).lowercase()
                "location" in searchable || "maps" in searchable || "geolocation" in searchable
            }.map { it.id }
            val webSearchToolIds = availableChatTools.filter { tool ->
                val searchable = (tool.name + " " + tool.description + " " + tool.source).lowercase()
                "web search" in searchable ||
                    "web-search" in searchable ||
                    "search web" in searchable ||
                    "firecrawl" in searchable ||
                    "perplexity" in searchable ||
                    "exa" in searchable
            }.map { it.id }
            val initialCreativity = appAllPlatforms
                .filter { it.uid in activePlatformUids }
                .mapNotNull { it.temperature }
                .average()
                .takeIf { !it.isNaN() }
                ?.toFloat()
                ?: 0.5f
            val currentProfileUid = groupedMessages.assistantMessages.lastOrNull()
                ?.getOrNull(indexStates.lastOrNull() ?: 0)?.platformType
                ?.takeIf { it in activePlatformUids && it !in disabledPlatformUids }
                ?: activePlatformUids.firstOrNull { it !in disabledPlatformUids }.orEmpty()
            ChatModelDialog(
                isTemporary = chatRoom.isTemporary,
                onTemporaryChanged = chatViewModel::setTemporary,
                onForgetMemories = chatViewModel::forgetConversationMemories,
                parentChatId = chatRoom.parentChatId,
                onOpenParent = { onOpenConversation(it, false) },
                initialSelectedProfile = currentProfileUid,
                platformOrder = dialogPlatformOrder,
                activePlatformUids = activePlatformUids.toSet(),
                initialModels = appAllPlatforms.associate { it.uid to it.model } + chatPlatformModels,
                platformNames = platformNames,
                platformClientTypes = appAllPlatforms.associate { it.uid to it.compatibleType },
                platformApiUrls = appAllPlatforms.associate { it.uid to it.apiUrl },
                downloadedLocalModels = downloadedLocalModels,
                delegationSettings = chatToolConfig.effectiveDelegation(featureSettings.delegationFor(currentProfileUid)),
                delegationProfiles = appAllPlatforms.filter { it.enabled && it.uid !in activePlatformUids && !it.excludesMemory() },
                usesDefaultDelegation = chatToolConfig.delegation == null,
                onDelegationChanged = chatViewModel::setConversationDelegation,
                initialReasoning = chatToolConfig.reasoning ?: appAllPlatforms.firstOrNull { it.uid in activePlatformUids }?.reasoning ?: false,
                onReasoningChanged = chatViewModel::setConversationReasoning,
                initialCreativity = initialCreativity,
                locationToolsEnabled = locationToolIds.isNotEmpty() &&
                    availableChatTools.any { it.id in locationToolIds && it.isEnabled && chatToolConfig.isToolEnabled(it.id) },
                webSearchToolsEnabled = webSearchToolIds.isNotEmpty() &&
                    availableChatTools.any { it.id in webSearchToolIds && it.isEnabled && chatToolConfig.isToolEnabled(it.id) },
                locationToolsAvailable = locationToolIds.isNotEmpty(),
                webSearchToolsAvailable = availableChatTools.any { it.id in webSearchToolIds && it.isEnabled },
                disabledPlatformUids = disabledPlatformUids,
                onPlatformActiveChanged = chatViewModel::setPlatformMembership,
                onLocationToolsChanged = { enabled ->
                    if (enabled && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        chatViewModel.setDeviceLocationEnabled(enabled)
                        chatViewModel.setChatToolsEnabled(locationToolIds, enabled)
                    }
                },
                mcpTools = availableChatTools.filter { it.source == "MCP" },
                isToolEnabled = chatToolConfig::isToolEnabled,
                onToolChanged = { id, enabled -> chatViewModel.setChatToolsEnabled(listOf(id), enabled) },
                loadModels = chatViewModel::loadProfileModels,
                onWebSearchToolsChanged = { enabled ->
                    chatViewModel.setChatToolsEnabled(webSearchToolIds, enabled)
                },
                onNavigateToLocalModels = onNavigateToLocalModels,
                onDismissRequest = chatViewModel::closeChatModelDialog,
                onConfirmRequest = { models, creativity ->
                    chatViewModel.updateChatPlatformModels(models)
                    chatViewModel.updateChatCreativity(creativity)
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
                        initialMessage = session.message,
                        attachments = session.attachments,
                        onFileSelected = chatViewModel::addMessageEditFile,
                        onCopyFailed = chatViewModel::notifyAttachmentCopyFailed,
                        onFileRemoved = chatViewModel::removeMessageEditFile,
                        onDismissRequest = chatViewModel::discardMessageEditDialog,
                        onConfirmRequest = { message, thoughts ->
                            if (chatViewModel.saveAssistantMessageEdit(message, thoughts, session.attachments)) {
                                chatViewModel.finishMessageEditDialog()
                            }
                        }
                    )
                }
            }
        }

        if (isSelectTextSheetOpen) {
            val selectedText by chatViewModel.selectedText.collectAsStateWithLifecycle()
            ModalBottomSheet(onDismissRequest = chatViewModel::closeSelectTextSheet) {
                SelectionContainer(
                    modifier = Modifier
                        .padding(24.dp)
                        .heightIn(min = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(selectedText)
                }
            }
        }
    }
}

@Composable
private fun ChatMessagePair(
    messageIndex: Int,
    message: MessageV2,
    assistantMessages: List<MessageV2>,
    agentRunsById: Map<String, AgentRun>,
    activeAgentRuns: Map<String, ActiveAgentRun>,
    runNoticesById: Map<String, List<ChatRunNotice>>,
    toolEventsByRun: Map<String, List<ToolEvent>>,
    platformIndexState: Int,
    loadingStates: List<ChatViewModel.LoadingState>,
    enabledPlatformsInChat: List<String>,
    enabledPlatformLookup: Map<String, PlatformV2>,
    disabledPlatformUids: Set<String>,
    activePlatformUids: Set<String>,
    canUseChat: Boolean,
    isIdle: Boolean,
    isActiveMessage: Boolean,
    maximumUserChatBubbleWidth: Dp,
    maximumOpponentChatBubbleWidth: Dp,
    debugMode: Boolean = false,
    debugMemorySources: Map<String, DebugMemorySource> = emptyMap(),
    invocationDiagnostics: List<dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation> = emptyList(),
    debugSettings: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(),
    showReasoning: Boolean = true,
    combinedMode: Boolean = false,
    selectedCombinedProfileUid: String? = null,
    onCombinedProfileClick: (String?) -> Unit = {},
    smartSuggestionsEnabled: Boolean = true,
    isUserTyping: Boolean = false,
    targetMessageId: Int = -1,
    onTargetResponseOffset: (Int) -> Unit = {},
    onResponseOffset: (Int) -> Unit = {},
    onEditQuestion: (MessageV2) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onCopyText: (String) -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    onPlatformLongPress: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    onInspectContext: ((String) -> Unit)? = null,
    onFavoriteClick: (Int, Int) -> Unit,
    onFavoriteLongPress: () -> Unit,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit,
    onContinueClick: () -> Unit = {},
    onActionClick: (String) -> Unit = {}
) {
    val combinedSynthesisIndex = assistantMessages.indexOfFirst { it.isCombinedSynthesis() }
    val participatingUids = activePlatformUids - disabledPlatformUids
    val combinedProfiles = if (combinedMode) {
        combinedResponseProfiles(
            responses = assistantMessages,
            platformUids = enabledPlatformsInChat,
            profileNames = enabledPlatformLookup.mapValues { it.value.name },
            participatingUids = participatingUids,
            preparing = isActiveMessage && !isIdle,
            runsById = agentRunsById
        )
    } else {
        emptyList()
    }
    val hasCombinedTabs = combinedMode && (combinedSynthesisIndex >= 0 || combinedProfiles.size > 1)
    val selectedProfile = combinedProfiles.firstOrNull { it.uid == selectedCombinedProfileUid }.takeIf { hasCombinedTabs }
    val isSourceResponse = selectedProfile != null
    val isCombinedConversation = hasCombinedTabs && !isSourceResponse
    val combinedStatus = combinedAnswerStatus(assistantMessages.getOrNull(combinedSynthesisIndex), combinedProfiles, agentRunsById)
    val targetAssistantIndex = assistantMessages.indexOfFirst { targetMessageId > 0 && it.id == targetMessageId }
    val displayPlatformIndex = when {
        selectedProfile != null -> selectedProfile.assistantIndex
        isCombinedConversation -> combinedSynthesisIndex.takeIf { it >= 0 } ?: combinedProfiles.firstOrNull()?.assistantIndex ?: 0
        else -> platformIndexState
    }
    val selectedAssistantMessage = if (selectedProfile != null) selectedProfile.message else assistantMessages.getOrNull(displayPlatformIndex)
    val responseInsetPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val synthesisStarted = isCombinedConversation && combinedSynthesisIndex >= 0
    val assistantContent = when {
        isCombinedConversation && !synthesisStarted && combinedStatus == CombinedResponseStatus.FAILED -> stringResource(R.string.combined_all_profiles_failed)
        isCombinedConversation && !synthesisStarted -> ""
        isSourceResponse && selectedProfile.status == CombinedResponseStatus.FAILED && selectedAssistantMessage?.content.isNullOrBlank() -> stringResource(R.string.combined_profile_no_response)
        else -> dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(selectedAssistantMessage?.effectiveContent().orEmpty())
    }
    val assistantThoughts = if (isCombinedConversation && !synthesisStarted) {
        ""
    } else {
        selectedAssistantMessage?.effectiveThoughts().orEmpty()
    }
    val assistantTimeline = if (isCombinedConversation && !synthesisStarted) {
        emptyList()
    } else {
        selectedAssistantMessage?.effectiveTimeline().orEmpty()
    }
    val selectedRunId = selectedAssistantMessage?.effectiveRunId()
    val agentRun = selectedRunId?.let(agentRunsById::get)
    val activeAgentRun = if (isCombinedConversation) {
        assistantMessages.asSequence().mapNotNull { it.currentRunId?.let(activeAgentRuns::get) }.firstOrNull()
    } else {
        selectedRunId?.let(activeAgentRuns::get)
    }
    val toolEvents = selectedRunId?.let(toolEventsByRun::get).orEmpty()
    val provenanceToolEvents = toolEventsForResponse(
        selected = selectedAssistantMessage,
        responses = assistantMessages,
        combined = isCombinedConversation,
        activeProfileUids = activePlatformUids - disabledPlatformUids,
        eventsByRun = toolEventsByRun
    )
    val canShowPreviousRevision = !hasCombinedTabs &&
        (
            selectedAssistantMessage?.let { assistantMessage ->
                assistantMessage.revisions.isNotEmpty() &&
                    assistantMessage.activeRevisionIndex < assistantMessage.revisions.lastIndex
            } ?: false
            )
    val canShowNextRevision = !hasCombinedTabs &&
        (
            selectedAssistantMessage?.let { assistantMessage ->
                assistantMessage.revisions.isNotEmpty() &&
                    assistantMessage.activeRevisionIndex != ACTIVE_REVISION_LATEST
            } ?: false
            )
    val selectedPlatformUid = selectedProfile?.uid ?: enabledPlatformsInChat.getOrElse(displayPlatformIndex) { "" }
    val isCurrentPlatformLoading = when {
        isCombinedConversation -> combinedStatus == CombinedResponseStatus.GENERATING
        isSourceResponse -> selectedProfile.status == CombinedResponseStatus.GENERATING
        else -> isActiveMessage && loadingStates.getOrElse(displayPlatformIndex) { ChatViewModel.LoadingState.Idle } == ChatViewModel.LoadingState.Loading
    }
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    StableChatResponseViewport(contentKey = if (isCombinedConversation) "combined" else selectedPlatformUid) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    onResponseOffset(it.height + responseInsetPx * 2)
                    if (targetAssistantIndex >= 0) onTargetResponseOffset(it.height + responseInsetPx)
                }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                UserChatBubble(
                    modifier = Modifier.widthIn(max = maximumUserChatBubbleWidth),
                    text = message.content,
                    timestamp = (message.createdAt * 1000L).takeIf { debugSettings.messageTimestamps },
                    files = message.attachments.map { it.filePathForDisplay },
                    canEdit = canUseChat && isIdle,
                    onCopyClick = { onCopyText(message.content) },
                    onSelectClick = { onSelectText(message.content) },
                    onEditClick = { onEditQuestion(message) },
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
                modifier = Modifier
                    .fillMaxWidth()

            ) {
                if (hasCombinedTabs) {
                    CombinedProfileBubbles(
                        profiles = combinedProfiles,
                        selectedUid = selectedProfile?.uid,
                        combinedStatus = combinedStatus,
                        animateArrival = isActiveMessage && !isIdle,
                        onSelectProfile = onCombinedProfileClick,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                } else {
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
                                        isLoading = isActiveMessage && loadingStates.getOrNull(platformIndex) == ChatViewModel.LoadingState.Loading,
                                        name = enabledPlatformLookup[uid]?.name ?: stringResource(R.string.unknown),
                                        selected = platformIndexState == platformIndex,
                                        disabled = uid in disabledPlatformUids,
                                        onPlatformClick = { onPlatformClick(messageIndex, platformIndex) },
                                        onPlatformLongPress = { onPlatformLongPress(uid) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                }
                OpponentChatBubble(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (!hasCombinedTabs && selectedPlatformUid in disabledPlatformUids) 0.5f else 1f)
                        .padding(horizontal = 2.dp)
                        .widthIn(max = maximumOpponentChatBubbleWidth),
                    canEdit = canUseChat && isIdle && !isSourceResponse,
                    canRetry = !hasCombinedTabs &&
                        canUseChat &&
                        (isActiveMessage || agentRun?.status in setOf(AgentRunStatus.FAILED, AgentRunStatus.INTERRUPTED, AgentRunStatus.CANCELED)) &&
                        !isCurrentPlatformLoading &&
                        selectedPlatformUid in activePlatformUids &&
                        selectedPlatformUid !in disabledPlatformUids,
                    isLoading = if (hasCombinedTabs) {
                        isCurrentPlatformLoading
                    } else {
                        activeAgentRun != null || (isCurrentPlatformLoading && agentRun?.status !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.INTERRUPTED, AgentRunStatus.CANCELED))
                    },
                    isError = agentRun?.status == AgentRunStatus.FAILED && isAssistantErrorMessage(assistantContent),
                    isFavorite = selectedAssistantMessage?.isFavorite ?: false,
                    debugMode = debugMode,
                    invocations = invocationDiagnostics.filter { it.parentRunId == selectedRunId },
                    debugSettings = debugSettings,
                    debugMemorySources = debugMemorySources,
                    showReasoning = showReasoning,
                    text = assistantContent,
                    timestamp = selectedAssistantMessage?.let { it.createdAt * 1000L }?.takeIf { debugSettings.messageTimestamps },
                    thoughts = assistantThoughts,
                    timeline = assistantTimeline,
                    attachments = selectedAssistantMessage?.attachments.orEmpty().map { it.filePathForDisplay },
                    agentRun = agentRun,
                    conversationId = selectedAssistantMessage?.chatId,
                    generationTiming = responseGenerationTiming(
                        agentRun,
                        if (isCombinedConversation) combinedProfiles.mapNotNull { it.message?.effectiveRunId()?.let(agentRunsById::get) } else emptyList()
                    ),
                    runNotices = selectedRunId?.let(runNoticesById::get).orEmpty(),
                    toolEvents = toolEvents,
                    locationToolEvents = provenanceToolEvents,
                    sourceToolEvents = provenanceToolEvents,
                    sourceProfilesByRun = agentRunsById.mapValues { it.value.profileUid },
                    contentIdentity = "$messageIndex:$selectedPlatformUid:${selectedRunId.orEmpty()}:${selectedAssistantMessage?.activeRevisionIndex}",
                    revisionIndexLabel = selectedAssistantMessage?.takeIf { !isCombinedConversation && it.revisions.isNotEmpty() }?.let { assistantMessage ->
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
                    onRetryClick = { onRetry(messageIndex, displayPlatformIndex) },
                    onInspectContext = selectedRunId?.let { id ->
                        onInspectContext?.let { inspect -> { inspect(id) } }
                    },
                    onEditClick = { onEditAssistant(messageIndex, displayPlatformIndex) },
                    onFavoriteClick = { onFavoriteClick(messageIndex, displayPlatformIndex) },
                    onFavoriteLongPress = onFavoriteLongPress,
                    onShowPreviousRevision = { onShowPreviousRevision(messageIndex, displayPlatformIndex) },
                    onShowNextRevision = { onShowNextRevision(messageIndex, displayPlatformIndex) },
                    isUserTyping = isUserTyping,
                    isLastMessage = isActiveMessage && !isSourceResponse,
                    onContinueClick = onContinueClick.takeIf { smartSuggestionsEnabled && !isSourceResponse },
                    onActionClick = onActionClick.takeIf { smartSuggestionsEnabled && !isSourceResponse }
                )
            }
        }
    }
}

@Composable
private fun ArchivedHistoryHeader(
    hiddenTurnCount: Int,
    onExpand: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "^",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            modifier = Modifier.semantics {
                contentDescription = "Show older conversation history ($hiddenTurnCount hidden)"
            }
        )
    }
}

private const val RECENT_EXPANDED_TURNS = 3
private const val ARCHIVE_REVEAL_STEP = 3

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
    isEnabled: Boolean,
    animate: Boolean = true
) {
    LaunchedEffect(listState, isEnabled, animate) {
        if (!isEnabled) return@LaunchedEffect

        // Observe measured growth, not token count. User gestures cancel the animation.
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.index, last?.let { it.offset + it.size })
        }.distinctUntilChanged().conflate().collect { (totalItems, _, _) ->
            kotlinx.coroutines.delay(32)
            val latestItemIndex = totalItems - 1
            if (latestItemIndex >= 0 && listState.canScrollForward) {
                if (animate) listState.animateScrollToItem(latestItemIndex) else listState.scrollToItem(latestItemIndex)
            }
        }
    }
}

internal suspend fun LazyListState.scrollToConversationEntry(targetItem: Int? = null, responseOffset: Int = 0, centerResponse: Boolean = false) {
    snapshotFlow { layoutInfo }.first { it.totalItemsCount > (targetItem ?: 0) && it.viewportSize.height > 0 }
    val index = targetItem ?: (layoutInfo.totalItemsCount - 1)
    val offset = if (centerResponse) {
        responseOffset - conversationEntryCenter(
            viewportStart = layoutInfo.viewportStartOffset,
            viewportEnd = layoutInfo.viewportEndOffset,
            topInset = layoutInfo.beforeContentPadding,
            bottomInset = layoutInfo.afterContentPadding
        )
    } else {
        responseOffset
    }
    if (index >= 0) scrollToItem(index, if (targetItem == null) 0 else offset)
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
    isTitleCustomized: Boolean,
    isMenuItemEnabled: Boolean,
    isModelItemEnabled: Boolean,
    onBackAction: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onChatTitleItemClick: () -> Unit,
    onChatModelItemClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onDisablePlatformClick: () -> Unit = {}
) {
    var isDropDownMenuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isTitleCustomized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = isMenuItemEnabled, onClick = onChatTitleItemClick)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBackAction,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(start = 8.dp).size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.go_back),
                    modifier = Modifier.size(21.dp)
                )
            }
        },
        actions = {
            IconButton(
                enabled = isModelItemEnabled,
                onClick = onChatModelItemClick
            ) {
                Icon(
                    imageVector = Icons.Rounded.Build,
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = "Conversation settings"
                )
            }
            IconButton(
                onClick = { isDropDownMenuExpanded = isDropDownMenuExpanded.not() }
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.options), tint = MaterialTheme.colorScheme.primary)
            }

            ChatDropdownMenu(
                isDropdownMenuExpanded = isDropDownMenuExpanded,
                isMenuItemEnabled = isMenuItemEnabled,
                onDismissRequest = { isDropDownMenuExpanded = false },
                onChatTitleItemClick = {
                    onChatTitleItemClick.invoke()
                    isDropDownMenuExpanded = false
                },
                onExportChatItemClick = onExportChatItemClick,
                onDisablePlatformClick = {
                    onDisablePlatformClick()
                    isDropDownMenuExpanded = false
                }
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun ChatDropdownMenu(
    isDropdownMenuExpanded: Boolean,
    isMenuItemEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onChatTitleItemClick: () -> Unit,
    onExportChatItemClick: () -> Unit,
    onDisablePlatformClick: () -> Unit = {}
) {
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary, leadingIconColor = MaterialTheme.colorScheme.primary, trailingIconColor = MaterialTheme.colorScheme.primary, disabledTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledLeadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledTrailingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
            enabled = isMenuItemEnabled,
            text = { Text(text = stringResource(R.string.update_chat_title)) },
            onClick = onChatTitleItemClick
        )
        /* Export Chat */
        DropdownMenuItem(
            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary, leadingIconColor = MaterialTheme.colorScheme.primary, trailingIconColor = MaterialTheme.colorScheme.primary, disabledTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledLeadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledTrailingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
            enabled = isMenuItemEnabled,
            text = { Text(text = stringResource(R.string.export_chat)) },
            onClick = {
                onExportChatItemClick()
                onDismissRequest()
            }
        )
        /* Disable Platform in current session */
        DropdownMenuItem(
            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary, leadingIconColor = MaterialTheme.colorScheme.primary, trailingIconColor = MaterialTheme.colorScheme.primary, disabledTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledLeadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledTrailingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
            enabled = isMenuItemEnabled,
            text = { Text(text = stringResource(R.string.disable_platform)) },
            onClick = {
                onDisablePlatformClick()
                onDismissRequest()
            }
        )
    }
}

@Composable
fun ChatBubbleDropdownMenu(
    isChatBubbleDropdownMenuExpanded: Boolean,
    canEdit: Boolean,
    onDismissRequest: () -> Unit,
    onEditItemClick: () -> Unit,
    onCopyItemClick: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.wrapContentSize(),
        expanded = isChatBubbleDropdownMenuExpanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary, leadingIconColor = MaterialTheme.colorScheme.primary, trailingIconColor = MaterialTheme.colorScheme.primary, disabledTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledLeadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledTrailingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
            enabled = canEdit,
            leadingIcon = {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.edit)
                )
            },
            text = { Text(text = stringResource(R.string.edit)) },
            onClick = {
                onEditItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
        DropdownMenuItem(
            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary, leadingIconColor = MaterialTheme.colorScheme.primary, trailingIconColor = MaterialTheme.colorScheme.primary, disabledTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledLeadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledTrailingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(id = R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.copy_text)
                )
            },
            text = { Text(text = stringResource(R.string.copy_text)) },
            onClick = {
                onCopyItemClick.invoke()
                onDismissRequest.invoke()
            }
        )
    }
}

private suspend fun exportChat(context: Context, chatViewModel: ChatViewModel, format: ChatExportFormat) {
    try {
        val (fileName, fileContent) = chatViewModel.exportChat(format)
        if (fileContent.isBlank()) {
            Toast.makeText(context, "No AI responses to export yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(context.getExternalFilesDir(null), fileName)
        withContext(Dispatchers.IO) { file.writeText(fileContent) }
        val uri = getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Chat Export").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resInfo = context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        resInfo.forEach { res ->
            context.grantUriPermission(res.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ChatExport", "Failed to export chat", e)
        Toast.makeText(context, "Failed to export chat", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.toolTraceLabels(): ToolTraceLabels = ToolTraceLabels(
    expandToolTrace = getString(R.string.tool_trace_expand_content_description),
    collapseToolTrace = getString(R.string.tool_trace_collapse_content_description),
    expand = getString(R.string.tool_trace_expand),
    collapse = getString(R.string.tool_trace_collapse),
    call = getString(R.string.tool_trace_call_singular),
    calls = getString(R.string.tool_trace_call_plural),
    running = getString(R.string.tool_trace_status_running),
    failed = getString(R.string.tool_trace_status_failed),
    completedWithErrors = getString(R.string.tool_trace_status_completed_with_errors),
    canceled = getString(R.string.tool_trace_status_canceled),
    completed = getString(R.string.tool_trace_status_completed),
    status = getString(R.string.tool_trace_status),
    callId = getString(R.string.tool_trace_call_id),
    connection = getString(R.string.tool_trace_connection),
    tool = getString(R.string.tool_trace_tool),
    modelTool = getString(R.string.tool_trace_model_tool),
    timing = getString(R.string.tool_trace_timing),
    error = getString(R.string.tool_trace_error),
    arguments = getString(R.string.tool_trace_arguments),
    result = getString(R.string.tool_trace_result),
    exportHeader = { count -> getString(R.string.tool_trace_export_header, count) },
    startedAt = getString(R.string.tool_trace_timing_started_at)
)

@Preview
@Composable
fun ChatInputBox(
    modifier: Modifier = Modifier,
    inputState: TextFieldState = rememberTextFieldState(),
    chatEnabled: Boolean = true,
    sendButtonEnabled: Boolean = true,
    isRunning: Boolean = false,
    queuedPromptCount: Int = 0,
    selectedAttachments: List<ChatAttachmentDraft> = emptyList(),
    onFileSelected: (String) -> Unit = {},
    onFileRemoved: (String) -> Unit = {},
    onCancelButtonClick: () -> Unit = {},
    onSendButtonClick: () -> Unit = {}
) {
    val localStyle = LocalTextStyle.current
    val inputColor = MaterialTheme.colorScheme.background
    val mergedStyle = localStyle.merge(TextStyle(color = MaterialTheme.colorScheme.onBackground))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatInputLineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5)
    val hasQuestionText = inputState.text.isNotEmpty()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val filePath = withContext(Dispatchers.IO) {
                    copyFileToAppDirectory(context, it)
                }
                filePath?.let { path -> onFileSelected(path) }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = inputColor,
            contentColor = MaterialTheme.colorScheme.onBackground,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            shadowElevation = 0.dp
        ) {
            Column {
                if (selectedAttachments.isNotEmpty()) {
                    FileThumbnailRow(
                        selectedAttachments = selectedAttachments,
                        onFileRemoved = onFileRemoved
                    )
                }
                BasicTextField(
                    state = inputState,
                    modifier = Modifier.fillMaxWidth().background(inputColor),
                    enabled = chatEnabled,
                    textStyle = mergedStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = chatInputLineLimits,
                    decorator = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(inputColor)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                enabled = chatEnabled,
                                onClick = { filePickerLauncher.launch("*/*") }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    tint = MaterialTheme.colorScheme.primary,
                                    contentDescription = stringResource(R.string.attach_file)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                            ) {
                                if (inputState.text.isEmpty()) {
                                    Text(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                        text = if (chatEnabled) stringResource(R.string.write_a_message) else stringResource(R.string.some_platforms_disabled)
                                    )
                                }
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    innerTextField()
                                }
                            }
                            val showStop = isRunning && !hasQuestionText && selectedAttachments.isEmpty()
                            FilledIconButton(
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                                enabled = showStop || (chatEnabled && sendButtonEnabled && (hasQuestionText || selectedAttachments.isNotEmpty())),
                                onClick = if (showStop) onCancelButtonClick else onSendButtonClick
                            ) {
                                if (showStop) {
                                    Icon(
                                        imageVector = Icons.Rounded.Stop,
                                        contentDescription = stringResource(R.string.cancel_active_runs)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowUpward,
                                        contentDescription = stringResource(R.string.send)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun FileThumbnailRow(
    selectedAttachments: List<ChatAttachmentDraft>,
    onFileRemoved: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        selectedAttachments.forEach { attachment ->
            FileThumbnail(
                attachment = attachment,
                onRemove = { onFileRemoved(attachment.sourceFilePath) }
            )
        }
    }
}

@Composable
internal fun FileThumbnail(
    attachment: ChatAttachmentDraft,
    onRemove: () -> Unit
) {
    val file = File(attachment.preparedFilePath ?: attachment.sourceFilePath)
    val isImage = isImageFile(file.extension)

    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (isImage) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_image),
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            MaterialTheme.colorScheme.error,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.remove),
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }

            if (attachment.status == ChatAttachmentDraft.Status.Preparing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(72.dp)
        )

        attachment.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(72.dp)
            )
        }

        attachment.errorMessage?.let { errorMessage ->
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(72.dp)
            )
        }
    }
}

internal fun copyFileToAppDirectory(context: Context, uri: android.net.Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val rawFileName = getFileName(context, uri)
        val sanitizedFileName = sanitizeFileName(rawFileName)

        val attachmentsDir = File(context.filesDir, "attachments")
        attachmentsDir.mkdirs()

        var targetFile = File(attachmentsDir, sanitizedFileName)

        // If file exists, append timestamp to avoid overwrites
        if (targetFile.exists()) {
            val nameWithoutExt = sanitizedFileName.substringBeforeLast(".")
            val ext = sanitizedFileName.substringAfterLast(".", "")
            val uniqueName = if (ext.isNotEmpty()) {
                "${nameWithoutExt}_${System.currentTimeMillis()}.$ext"
            } else {
                "${sanitizedFileName}_${System.currentTimeMillis()}"
            }
            targetFile = File(attachmentsDir, uniqueName)
        }

        // Verify canonical path is within attachments directory to prevent path traversal
        val attachmentsDirCanonical = attachmentsDir.canonicalPath
        val targetFileCanonical = targetFile.canonicalPath
        if (!targetFileCanonical.startsWith(attachmentsDirCanonical + File.separator) &&
            targetFileCanonical != attachmentsDirCanonical
        ) {
            return null
        }

        inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        targetFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun getFileName(context: Context, uri: android.net.Uri): String {
    var fileName = "attachment_${System.currentTimeMillis()}"

    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex != -1) {
            fileName = cursor.getString(nameIndex) ?: fileName
        }
    }

    return fileName
}

private fun sanitizeFileName(fileName: String): String {
    val maxLength = 200

    // Remove path separators and ".." segments
    val withoutPathTraversal = fileName
        .replace("..", "")
        .replace("/", "")
        .replace("\\", "")

    // Keep only safe characters: alphanumerics, dash, underscore, dot
    val sanitized = withoutPathTraversal
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        .take(maxLength)
        .trim('.')

    // If sanitized name is empty, generate a fallback
    return sanitized.ifEmpty { "attachment_${System.currentTimeMillis()}" }
}

private fun isImageFile(extension: String?): Boolean {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    return extension?.lowercase() in imageExtensions
}

@Composable
fun ScrollToBottomButton(isGenerating: Boolean = false, onClick: () -> Unit) {
    val opacity = if (isGenerating) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "latest-response-pulse")
        val pulse by transition.animateFloat(
            initialValue = 0.82f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(750, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "latest-response-opacity"
        )
        pulse
    } else {
        1f
    }
    // The visual is half-size; the touch target remains accessible.
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(26.dp).graphicsLayer { alpha = opacity },
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 3.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom_icon), modifier = Modifier.size(18.dp))
            }
        }
    }
}
