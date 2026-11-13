package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
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
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedManifest
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.database.entity.hasHistoricalRevisionSelected
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.UiOperationContext
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet as ModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropBox
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens
import dev.chungjungsoo.gptmobile.presentation.common.diagnosticFrameTiming
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdrop
import dev.chungjungsoo.gptmobile.presentation.common.hasGlassBackdrop
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {},
    onReviewEvidenceAccess: () -> Unit = {},
    onProfileSettings: (String) -> Unit = {},
    onOpenConversation: (Int, Boolean) -> Unit = { _, _ -> },
    onInspectContext: ((Int, String) -> Unit)? = null,
    showTopBar: Boolean = true,
    allowResearchPanels: Boolean = true,
    exportRequestedForChatId: Int? = null,
    onExportHandled: () -> Unit = {},
    bottomInteractionContent: (@Composable () -> Unit)? = null
) {
    ConversationViewport { viewportWidth, viewportHeight ->
        ChatScreenContent(
            chatViewModel = chatViewModel,
            onBackAction = onBackAction,
            onNavigateToLocalModels = onNavigateToLocalModels,
            onReviewEvidenceAccess = onReviewEvidenceAccess,
            onProfileSettings = onProfileSettings,
            onOpenConversation = onOpenConversation,
            onInspectContext = onInspectContext,
            showTopBar = showTopBar,
            allowResearchPanels = allowResearchPanels,
            exportRequestedForChatId = exportRequestedForChatId,
            onExportHandled = onExportHandled,
            bottomInteractionContent = bottomInteractionContent,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    chatViewModel: ChatViewModel = hiltViewModel(),
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {},
    onReviewEvidenceAccess: () -> Unit = {},
    onProfileSettings: (String) -> Unit = {},
    onOpenConversation: (Int, Boolean) -> Unit = { _, _ -> },
    onInspectContext: ((Int, String) -> Unit)? = null,
    showTopBar: Boolean = true,
    allowResearchPanels: Boolean,
    exportRequestedForChatId: Int?,
    onExportHandled: () -> Unit,
    bottomInteractionContent: (@Composable () -> Unit)?,
    viewportWidth: Dp,
    viewportHeight: Dp
) {
    val chatRoom by chatViewModel.chatRoom.collectAsStateWithLifecycle()
    val agentRunsById by chatViewModel.agentRunsById.collectAsStateWithLifecycle()
    val providerWaitDeadlines by chatViewModel.providerWaitDeadlines.collectAsStateWithLifecycle()
    val featureSettings by chatViewModel.featureSettings.collectAsStateWithLifecycle()
    val chatToolConfig by chatViewModel.chatToolConfig.collectAsStateWithLifecycle()
    val sendPreparation by chatViewModel.sendPreparation.collectAsStateWithLifecycle()
    val gatewayModelPreparation by chatViewModel.gatewayModelPreparation.collectAsStateWithLifecycle()
    val githubContextForLinks = chatToolConfig.github
    var githubLinkOrigin by remember(githubContextForLinks) { mutableStateOf<String?>(null) }
    LaunchedEffect(githubContextForLinks) {
        githubLinkOrigin = githubContextForLinks?.let { chatViewModel.gitHubWebOrigin(it.connectionUid) } ?: "https://github.com"
    }
    val githubResponseLinkContext = remember(githubContextForLinks, githubLinkOrigin) {
        val github = githubContextForLinks
        val origin = githubLinkOrigin
        if (github == null || origin == null) null else GitHubAssistantReferenceContext(github.owner, github.repo, origin, github.ref)
    }
    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val removedProfile by chatViewModel.removedCombinedProfile.collectAsStateWithLifecycle()
    LaunchedEffect(removedProfile) {
        removedProfile?.let { (_, name) ->
            val result = snackbarHost.showSnackbar("$name removed from this chat", actionLabel = "Undo", withDismissAction = true)
            chatViewModel.finishRemovedProfileNotice(result == androidx.compose.material3.SnackbarResult.ActionPerformed)
        }
    }
    // Only a focus bit and non-empty flag are observed on the UI thread.
    androidx.compose.runtime.DisposableEffect(chatViewModel) {
        onDispose { dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.clearComposer() }
    }
    LaunchedEffect(chatViewModel) {
        snapshotFlow { chatViewModel.question.text.isNotEmpty() }
            .distinctUntilChanged()
            .collect { hasDraft ->
                dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.composerDraftChanged(hasDraft)
            }
    }
    val branchNavigation by chatViewModel.branchNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(branchNavigation) {
        branchNavigation?.let {
            chatViewModel.consumeBranchNavigation()
            onOpenConversation(it, true)
        }
    }
    val delegationRecoveryRequests by chatViewModel.pendingDelegationRecovery.collectAsStateWithLifecycle()
    delegationRecoveryRequests.firstOrNull { it.chatId == chatRoom.id }?.takeIf { chatToolConfig.github == null }?.let { request ->
        DelegationRecoveryDialog(
            request = request,
            onSwitch = { profileUid -> chatViewModel.respondDelegationRecovery(request.id, profileUid) },
            onCancelDelegation = { chatViewModel.respondDelegationRecovery(request.id, null) }
        )
    }
    val reasoningAdmission by chatViewModel.reasoningAdmissionNotice.collectAsStateWithLifecycle()
    reasoningAdmission?.let { notice ->
        dev.chungjungsoo.gptmobile.presentation.ui.setting.ReasoningAdmissionDialog(
            notice.copy(
                state = gatewayModelPreparation[notice.profileUid]?.takeIf { notice.modelReadiness && it.model == notice.model && it.observation?.requested == notice.requested }?.let {
                    notice.state.copy(checking = it.busy, observation = it.observation, preparation = it)
                } ?: notice.state
            ),
            onRecheck = chatViewModel::recheckReasoningAdmission,
            onSwitchToAuto = { chatViewModel.switchProfileReasoningToAuto(notice.profileUid) },
            onSettings = {
                chatViewModel.dismissReasoningAdmission()
                onProfileSettings(notice.profileUid)
            },
            onChooseModel = {
                chatViewModel.dismissReasoningAdmission()
                chatViewModel.openChatModelDialog()
            },
            onDismiss = chatViewModel::dismissReasoningAdmission
        )
    }
    val combinedFailures by chatViewModel.combinedFailures.collectAsStateWithLifecycle()
    val reasoningRecoveries by chatViewModel.reasoningRecoveries.collectAsStateWithLifecycle()
    val billingUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val openProfileBilling: (String) -> Unit = { uid -> chatViewModel.billingUrlForProfile(uid)?.let { url -> runCatching { billingUriHandler.openUri(url) } } }

    val failureTurn = combinedFailures.firstOrNull()?.userMessageId
    if (failureTurn != null) {
        CombinedFailuresDialog(
            failures = combinedFailures.filter { it.userMessageId == failureTurn },
            identities = ProfileAttributionPalette.forSelection(combinedFailures.map { it.profileUid }, featureSettings.profileAttributionColors),
            onRetry = chatViewModel::retryCombinedProfile,
            onRemove = chatViewModel::removeCombinedFailureProfile,
            onContinue = { chatViewModel.combineAvailableResults(failureTurn) },
            onDismiss = { chatViewModel.dismissCombinedFailures(failureTurn) },
            canContinue = chatViewModel.canCombineAvailable(failureTurn),
            onExclude = chatViewModel::excludeCombinedFailureForTurn,
            onCheckQueuedJob = chatViewModel::checkQueuedJob,
            recoveryStates = reasoningRecoveries,
            billingProfiles = combinedFailures.mapNotNull { it.profileUid.takeIf { uid -> chatViewModel.billingUrlForProfile(uid) != null } }.toSet(),
            onChangeSettings = onProfileSettings,
            onManageBilling = openProfileBilling,
            onRecheckReasoning = chatViewModel::recheckReasoningFailure,
            onSwitchToAuto = chatViewModel::switchProfileReasoningToAuto
        )
    }
    val allInputRequests by chatViewModel.pendingMcpInput.collectAsStateWithLifecycle()
    val inputRequests = allInputRequests.filter { request -> request.runId?.let { agentRunsById[it]?.chatId } == chatRoom.id }
    inputRequests.firstOrNull()?.takeIf { chatToolConfig.github == null }?.let { McpInputDialog(it, respond = chatViewModel::respondMcpInput) }
    val allApprovals by chatViewModel.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    val approvals = allApprovals.filter { approval -> agentRunsById[approval.runId]?.chatId == chatRoom.id }
    approvals.firstOrNull()?.takeIf { chatToolConfig.github == null }?.let { approval ->
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
    freeToolConsent?.takeIf { chatToolConfig.github == null }?.let { request ->
        FreeToolConsentDialog(
            request = request,
            onAccept = chatViewModel::confirmFreeToolConsent,
            onDismiss = chatViewModel::dismissFreeToolConsent
        )
    }
    val focusManager = LocalFocusManager.current
    val systemChatMargin = 16.dp
    val maximumUserChatBubbleWidth = (viewportWidth - systemChatMargin).coerceAtLeast(0.dp) * 0.8F
    val maximumOpponentChatBubbleWidth = (viewportWidth - systemChatMargin).coerceAtLeast(0.dp)
    val hasOlderHistory by chatViewModel.olderHistoryAvailable.collectAsStateWithLifecycle()
    val groupedMessages by chatViewModel.groupedMessages.collectAsStateWithLifecycle()
    val academicGateways by chatViewModel.academicGateways.collectAsStateWithLifecycle()
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
    val visibleUserMessages = remember(groupedMessages.userMessages, firstVisibleTurn) {
        groupedMessages.userMessages.drop(firstVisibleTurn)
    }
    val historyHeaderCount = (if (hiddenTurnCount > 0) 1 else 0) + (if (hasOlderHistory) 1 else 0)
    // Existing expanded/retained turn counts, not a claim about viewport visibility.
    val transcriptTiming = remember(visibleTurnCount, groupedMessages.userMessages.size) {
        Modifier.diagnosticFrameTiming(
            CombinedOperationOwner(
                screen = "Chat",
                uiContext = UiOperationContext("Transcript", itemCount = visibleTurnCount, retainedItemCount = groupedMessages.userMessages.size)
            )
        )
    }
    val listState = rememberChatListState(
        messageCount = visibleTurnCount + historyHeaderCount,
        hasTargetMessage = hasTargetMessage
    )
    // Layout offsets change on every scroll/animation frame. Only a viewport
    // resize changes the reserved height; avoid invalidating long answer rows.
    val transcriptViewportHeight by remember(listState) {
        derivedStateOf { listState.layoutInfo.viewportSize.height }
    }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var isFollowingBottom by remember { mutableStateOf(!hasTargetMessage) }
    var isHoldingEntryCenter by remember { mutableStateOf(hasTargetMessage) }
    var isModelTabPositionLocked by remember { mutableStateOf(false) }
    var entryPositioned by remember { mutableStateOf(false) }
    var targetResponseOffset by remember(chatViewModel.targetMessageId) { mutableStateOf<Int?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showExportOptions by remember { mutableStateOf(false) }
    LaunchedEffect(exportRequestedForChatId, chatRoom.id) {
        if (exportRequestedForChatId != null && exportRequestedForChatId == chatRoom.id && chatRoom.id > 0) {
            showExportOptions = true
            onExportHandled()
        }
    }
    var showProfileManagement by rememberSaveable { mutableStateOf(false) }
    val freeProviderNotice by chatViewModel.freeProviderNotice.collectAsStateWithLifecycle()
    freeProviderNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = chatViewModel::dismissFreeProviderNotice,
            title = { Text("Provider unavailable") },
            text = { Text(notice) },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    chatViewModel.dismissFreeProviderNotice()
                    showProfileManagement = true
                }) { Text("Choose another provider") }
            },
            dismissButton = { TextButton(onClick = chatViewModel::dismissFreeProviderNotice) { Text("Keep draft") } }
        )
    }
    var showOutline by rememberSaveable { mutableStateOf(false) }
    var pendingJumpKey by rememberSaveable { mutableStateOf<String?>(null) }
    val isLoaded by chatViewModel.isLoaded.collectAsStateWithLifecycle()
    val activeAgentRuns by chatViewModel.activeAgentRuns.collectAsStateWithLifecycle()
    val runNoticesById by chatViewModel.runNoticesById.collectAsStateWithLifecycle()
    val toolEventsByRun by chatViewModel.toolEventsByRun.collectAsStateWithLifecycle()
    val indexStates by chatViewModel.indexStates.collectAsStateWithLifecycle()
    val loadingStates by chatViewModel.loadingStates.collectAsStateWithLifecycle()

    val pendingReplies by chatViewModel.pendingReplies.collectAsStateWithLifecycle()
    var dismissedRecovery by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    val recoveryTurn = groupedMessages.assistantMessages.lastIndex
    val blockedResponse = groupedMessages.assistantMessages.lastOrNull()?.withIndex()?.firstOrNull { (_, message) ->
        message.currentRunId != null && message.timeline.any { it.recoveryBlock?.generationId == message.currentRunId }
    }
    val recoveryBlock = blockedResponse?.value?.let { message -> message.timeline.lastOrNull { it.recoveryBlock?.generationId == message.currentRunId }?.recoveryBlock }
    val recoveryKey = recoveryBlock?.let { "${it.generationId}:${it.fingerprint}" }
    var showRecoveryDetails by rememberSaveable(recoveryKey) { mutableStateOf(false) }
    if (showRecoveryDetails && recoveryBlock != null && recoveryKey != dismissedRecovery && blockedResponse != null && loadingStates.none { it == ChatViewModel.LoadingState.Loading }) {
        EvidenceRecoveryDialog(
            recoveryBlock,
            onDismiss = { dismissedRecovery = recoveryKey },
            onReviewAccess = onReviewEvidenceAccess,
            onRetry = {
                dismissedRecovery = recoveryKey
                chatViewModel.retryChat(recoveryTurn, blockedResponse.index)
            },
            onWithoutEvidence = {
                dismissedRecovery = recoveryKey
                chatViewModel.retryWithoutSavedEvidence(recoveryTurn, blockedResponse.index)
            }
        )
    }
    val disabledPlatformUids by chatViewModel.disabledPlatformUids.collectAsStateWithLifecycle()
    val nextMessageProfileUid by chatViewModel.nextMessageProfileUid.collectAsStateWithLifecycle()
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
    val queuedJobInspection by chatViewModel.queuedJobInspection.collectAsStateWithLifecycle()
    queuedJobInspection?.let { result ->
        dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(onDismissRequest = chatViewModel::dismissQueuedJobInspection, title = { Text("Saved queued job") }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(result) } }, confirmButton = { TextButton(onClick = chatViewModel::dismissQueuedJobInspection) { Text("Close") } })
    }
    val needsLocalNetworkAccess by chatViewModel.needsLocalNetworkAccess.collectAsStateWithLifecycle()
    val appEnabledPlatforms by chatViewModel.enabledPlatformsInApp.collectAsStateWithLifecycle()
    val appAllPlatforms by chatViewModel.platformsInApp.collectAsStateWithLifecycle()
    LaunchedEffect(chatRoom.id, appAllPlatforms, groupedMessages.userMessages.size, groupedMessages.assistantMessages.size) { chatViewModel.resumeBranchIfReady() }
    val chatPlatformModels by chatViewModel.chatPlatformModels.collectAsStateWithLifecycle()
    val availableChatTools by chatViewModel.availableChatTools.collectAsStateWithLifecycle()
    val gatewayWriteBlocker by chatViewModel.gatewayWriteBlocker.collectAsStateWithLifecycle()
    val gatewayQueueState by chatViewModel.gatewayQueueState.collectAsStateWithLifecycle()
    val githubDecisions by chatViewModel.githubDecisions.collectAsStateWithLifecycle()
    val githubWaiting by chatViewModel.waitingGitHubDecisions.collectAsStateWithLifecycle()
    GitHubChatPresenceEffect(chatRoom.id.takeIf { chatToolConfig.github != null })
    val gatewayLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(chatRoom.id, chatRoom.isTemporary, gatewayLifecycle) {
        gatewayLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            chatViewModel.observeGatewayConversation(chatRoom.id)
        }
    }
    LaunchedEffect(chatRoom.id, chatToolConfig.github != null) { if (chatToolConfig.github != null) chatViewModel.loadGitHubDecisions() }
    val githubDecisionScope = chatToolConfig.github?.let(dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions::workspaceKey)
    val scopedGitHubDecisions = remember(githubDecisions, chatRoom.id, githubDecisionScope) { githubDecisions.filter { it.chatId == chatRoom.id && it.workspace == githubDecisionScope } }
    val visibleGitHubDecisions = remember(scopedGitHubDecisions) { scopedGitHubDecisions.filter { !it.delivered && !it.cancelled } }
    val nativeDecisions = LocalGitHubNativeDecisions.current
    val turnDecisions = rememberGitHubTurnDecisions(scopedGitHubDecisions, nativeDecisions, groupedMessages.userMessages, pendingPrompts)
    val showResearchPanels = researchPanelsVisible(allowResearchPanels, chatToolConfig)
    val downloadedLocalModels by chatViewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val debugMode by chatViewModel.debugMode.collectAsStateWithLifecycle()
    val debugMemorySources by chatViewModel.debugMemorySources.collectAsStateWithLifecycle()
    val researchSessions by chatViewModel.researchSessions.collectAsStateWithLifecycle()
    val researchExecutions by chatViewModel.researchExecutionState.collectAsStateWithLifecycle()
    val interruptedResearchReview by chatViewModel.interruptedResearchReview.collectAsStateWithLifecycle()
    interruptedResearchReview?.let { review ->
        ResearchInterruptedRequestsDialog(review, chatViewModel::dismissInterruptedResearchReview, chatViewModel::authorizeReviewedResearchAttempt)
    }
    val researchProfileStatistics by chatViewModel.researchProfileStatistics.collectAsStateWithLifecycle()
    LaunchedEffect(chatRoom.id) { chatViewModel.loadResearchHistory(chatRoom.id) }
    val enabledPlatformLookup = remember(appAllPlatforms) { appAllPlatforms.associateBy { it.uid } }
    val enabledProfileUids = remember(appEnabledPlatforms) { appEnabledPlatforms.mapTo(mutableSetOf()) { it.uid } }
    val canUseChat = gatewayWriteBlocker == null && activePlatformUids.isNotEmpty() && activePlatformUids.all { it in enabledProfileUids }
    val gitHubCompositionTiming = dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalGitHubCompositionTiming.current
    val isIdle = loadingStates.all { it == ChatViewModel.LoadingState.Idle }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val release = dev.chungjungsoo.gptmobile.data.diagnostics.FrameTimingRecorder.screen("Chat")
        onDispose { release() }
    }
    val context = LocalContext.current
    val lastMessageIndex = groupedMessages.userMessages.lastIndex
    val newestPromptKey = groupedMessages.userMessages.lastOrNull()?.let { chatMessagePairKey(it, lastMessageIndex) }
    val githubPromptMode = chatToolConfig.github != null
    val latestUser = groupedMessages.userMessages.lastOrNull()
    val latestResponses = groupedMessages.assistantMessages.getOrNull(lastMessageIndex).orEmpty()
    val activeGitHubResponse = latestResponses.any { response ->
        val runId = response.currentRunId ?: return@any false
        val status = agentRunsById[runId]?.status
        status?.let { it !in AgentRunStatus.terminal } ?: (runId in activeAgentRuns)
    }
    val githubResponseStarting = !isIdle &&
        latestResponses.none { response -> response.currentRunId?.let(agentRunsById::get)?.status in AgentRunStatus.terminal }
    val pendingGitHubInput = LocalGitHubApprovalPending.current ||
        approvals.any { agentRunsById[it.runId]?.userMessageId == latestUser?.id } ||
        inputRequests.any { it.runId?.let(agentRunsById::get)?.userMessageId == latestUser?.id }
    val waitingForGitHubInput = pendingGitHubInput || visibleGitHubDecisions.any { it.sourceMessageId == latestUser?.id }
    val awaitingGitHubTurn = latestUser != null &&
        githubPromptMode &&
        chatRoom.conversationMode != ConversationMode.COMBINED &&
        (waitingForGitHubInput || activeGitHubResponse || githubResponseStarting)
    var observedPromptKey by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    var promptTrackingReady by rememberSaveable(chatRoom.id) { mutableStateOf(false) }
    var anchoredPromptKey by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    var githubScrollMode by rememberSaveable(chatRoom.id) { mutableStateOf(if (hasTargetMessage) GitHubScrollMode.USER_HISTORY else GitHubScrollMode.FOLLOW_BOTTOM) }
    var githubGestureActive by remember(chatRoom.id) { mutableStateOf(false) }
    var promptAnchorRevision by remember(chatRoom.id) { mutableIntStateOf(0) }
    val promptDensity = LocalDensity.current
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    val promptAnchor = remember(chatRoom.id, touchSlop) { GitHubPromptAnchor(touchSlop, touchSlop, githubScrollMode) }
    LaunchedEffect(isLoaded, newestPromptKey, chatToolConfig.github != null) {
        if (!isLoaded) return@LaunchedEffect
        if (chatToolConfig.github != null && promptTrackingReady && newestPromptKey != null && newestPromptKey != observedPromptKey) {
            anchoredPromptKey = newestPromptKey
            promptAnchor.followBottom()
            githubScrollMode = promptAnchor.mode
            githubGestureActive = false
            promptAnchorRevision++
            isFollowingBottom = false
            isHoldingEntryCenter = false
            isModelTabPositionLocked = false
        }
        observedPromptKey = newestPromptKey
        promptTrackingReady = true
    }
    var inspectedCombinedTurn by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    var inspectedCombinedProfileUid by rememberSaveable(chatRoom.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(activePlatformUids, inspectedCombinedProfileUid) {
        if (inspectedCombinedProfileUid != null && inspectedCombinedProfileUid !in activePlatformUids) {
            inspectedCombinedProfileUid = null
            inspectedCombinedTurn = null
        }
    }
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
        rememberCombinedResponseProfiles(
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
    if (showTopBar) androidx.activity.compose.BackHandler(onBack = onBackFromChat)
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
            }
        }
        sendAfterNotificationPermission = false
    }

    val scope = rememberCoroutineScope()
    LaunchedEffect(pendingJumpKey, firstVisibleTurn, historyHeaderCount) {
        val key = pendingJumpKey ?: return@LaunchedEffect
        val index = groupedMessages.userMessages.withIndex().firstOrNull { (turn, message) -> chatMessagePairKey(message, turn) == key }?.index ?: -1
        if (index >= firstVisibleTurn) {
            if (githubPromptMode) {
                promptAnchor.detach()
                githubScrollMode = promptAnchor.mode
                githubGestureActive = false
                isFollowingBottom = false
            }
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= visibleTurnCount + historyHeaderCount }
            listState.animateToGitHubPrompt(index - firstVisibleTurn + historyHeaderCount, animate = featureSettings.responseAnimation)
        }
        pendingJumpKey = null
    }
    if (showExportOptions) {
        val diagnosticNotice by chatViewModel.diagnosticExportNotice.collectAsStateWithLifecycle()
        var resumableCapture by remember { mutableStateOf(false) }
        LaunchedEffect(chatRoom.id, diagnosticNotice) { resumableCapture = chatViewModel.hasResumableDiagnosticCapture() }
        ChatExportDialog(
            onDismiss = { showExportOptions = false },
            debugMode = debugMode,
            includeUserMessages = chatToolConfig.github != null,
            diagnosticNotice = diagnosticNotice,
            onFullDebugExport = {
                showExportOptions = false
                scope.launch { exportChat(context, chatViewModel, ChatExportFormat.DEBUG, fullHistory = true) }
            },
            onReducedDebugExport = {
                showExportOptions = false
                scope.launch { exportChat(context, chatViewModel, ChatExportFormat.DEBUG, reducedMemory = true) }
            },
            onResumeDebugExport = if (resumableCapture) {
                {
                    showExportOptions = false
                    scope.launch { exportChat(context, chatViewModel, ChatExportFormat.DEBUG, resume = true) }
                }
            } else {
                null
            }
        ) { format ->
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
                if (responseOffset != null) listState.scrollToConversationEntry(itemIndex, responseOffset, centerResponse = featureSettings.centerUnread)
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
        if (githubPromptMode || !entryPositioned || !isHoldingEntryCenter || !hasTargetAssistant) return@LaunchedEffect
        val targetTurn = groupedMessages.assistantMessages.indexOfFirst { responses -> responses.any { it.id == chatViewModel.targetMessageId } }
        val itemIndex = targetTurn - firstVisibleTurn + historyHeaderCount
        // The composer can shrink after entering a combined profile. Keep the
        // response centered as that animation finishes, until the user scrolls.
        snapshotFlow { Triple(listState.layoutInfo.beforeContentPadding, listState.layoutInfo.afterContentPadding, targetResponseOffset) }
            .distinctUntilChanged()
            .collect { (_, _, offset) ->
                if (offset != null) listState.scrollToConversationEntry(itemIndex, offset, centerResponse = featureSettings.centerUnread)
            }
    }

    LaunchedEffect(isUserDragging, listState.isScrollInProgress, listState.canScrollForward, listState.lastScrolledBackward) {
        if (isModelTabPositionLocked && isUserDragging) isModelTabPositionLocked = false
        if (entryPositioned && !isModelTabPositionLocked && (isFollowingBottom || isUserDragging || listState.isScrollInProgress)) {
            isFollowingBottom = nextFollowBottom(
                isFollowing = isFollowingBottom,
                isUserScrolling = isUserDragging,
                isScrollingAway = listState.lastScrolledBackward,
                canScrollForward = listState.canScrollForward
            )
        }
    }

    LaunchedEffect(groupedMessages.userMessages.size) {
        val currentCount = groupedMessages.userMessages.size
        if (currentCount > previousMessageCount && entryPositioned && chatToolConfig.github == null) {
            isHoldingEntryCenter = false
            isModelTabPositionLocked = false
            isFollowingBottom = true
        }
        previousMessageCount = currentCount
    }

    val latestTurnItem = lastMessageIndex - firstVisibleTurn + historyHeaderCount

    val promptModeState by androidx.compose.runtime.rememberUpdatedState(githubPromptMode)
    val nearBottomDistance = with(promptDensity) { 48.dp.toPx() }
    val farBottomDistance = with(promptDensity) { 96.dp.toPx() }
    fun endPromptGesture(cancelled: Boolean = false) {
        promptAnchor.endGesture(listState.gitHubDistanceToBottom(), nearBottomDistance, farBottomDistance, cancelled)
        githubScrollMode = promptAnchor.mode
        githubGestureActive = false
        promptAnchorRevision++
        if (githubScrollMode == GitHubScrollMode.PIN_PROMPT && anchoredPromptKey == null) anchoredPromptKey = newestPromptKey
    }
    val latestEndPromptGesture by androidx.compose.runtime.rememberUpdatedState(::endPromptGesture)
    val promptConnection = remember(listState, promptAnchor) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!promptModeState || source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                // Cancel automatic movement immediately, before the list consumes this drag.
                if (!promptAnchor.dragging) promptAnchor.beginGesture()
                githubGestureActive = true
                promptAnchor.move(available.y, 0f, userInput = true, nowMillis = android.os.SystemClock.elapsedRealtime())
                githubScrollMode = promptAnchor.mode
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (promptModeState && promptAnchor.dragging) latestEndPromptGesture(false)
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    GitHubScrollMotion(
        listState = listState,
        enabled = githubPromptMode && entryPositioned && !isModelTabPositionLocked,
        mode = githubScrollMode,
        dragging = githubGestureActive || isUserDragging,
        promptKey = anchoredPromptKey ?: newestPromptKey,
        promptItem = latestTurnItem,
        revision = promptAnchorRevision,
        fontScale = promptDensity.fontScale,
        animate = featureSettings.responseAnimation
    )
    ChatBottomAutoScroller(
        listState = listState,
        animate = featureSettings.responseAnimation,
        isEnabled = !githubPromptMode &&
            featureSettings.smoothStreaming &&
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

    val bottomProfileTurn by remember(groupedMessages.userMessages, listState) {
        derivedStateOf {
            val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapTo(hashSetOf()) { it.key }
            groupedMessages.userMessages.indices.lastOrNull { index ->
                chatMessagePairKey(groupedMessages.userMessages[index], index) in visibleKeys
            } ?: groupedMessages.userMessages.lastIndex
        }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHost) },
        modifier = Modifier.then(backFade.modifier)
            .diagnosticFrameTiming("Chat")
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (showTopBar) {
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
                        showProfileManagement = true
                    },
                    onOutlineClick = { showOutline = true }
                )
            }
        }
    ) { innerPadding ->
        val density = LocalDensity.current
        var composerHeightPx by remember { mutableIntStateOf(with(density) { 88.dp.roundToPx() }) }
        val composerHeight = with(density) { composerHeightPx.toDp() }

        GlassBackdropBox(
            blurEnabled = isIdle && !listState.isScrollInProgress,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .then(transcriptTiming)
                    .fillMaxSize()
                    .nestedScroll(promptConnection)
                    .pointerInput(promptAnchor) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (promptModeState) {
                                promptAnchor.beginGesture()
                                githubGestureActive = true
                            }
                            var released = false
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.pressed })
                                released = true
                            } finally {
                                if (promptModeState && promptAnchor.dragging) latestEndPromptGesture(!released)
                            }
                        }
                    }
                    .chatMessageBackdrop(
                        topFadeStart = if (featureSettings.edgeFades) (innerPadding.calculateTopPadding() + 16.dp - viewportHeight * 0.05f).coerceAtLeast(0.dp) else 0.dp,
                        topFade = if (featureSettings.edgeFades) (innerPadding.calculateTopPadding() + 48.dp - viewportHeight * 0.05f).coerceAtLeast(0.dp) else 0.dp,
                        // Content continues behind the composer. Fade it from the top edge
                        // of the input surface to transparent halfway through the bar.
                        bottomFadeStartFromBottom = if (featureSettings.edgeFades && !inspectingCombinedSource) composerHeight else 0.dp,
                        bottomFadeEndFromBottom = if (featureSettings.edgeFades && !inspectingCombinedSource) composerHeight * 0.5f else 0.dp,
                        contentVisible = entryPositioned || groupedMessages.userMessages.isEmpty()
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
                    items = visibleUserMessages,
                    key = { visibleIndex, message ->
                        chatMessagePairKey(message, firstVisibleTurn + visibleIndex)
                    },
                    contentType = { _, _ -> "chat_turn" }
                ) { visibleIndex, message ->
                    val index = firstVisibleTurn + visibleIndex
                    val pairKey = chatMessagePairKey(message, index)
                    // Read acknowledgment needs visibility transitions, not each
                    // measured position of every item during smooth scrolling.
                    val replyVisible by remember(listState, pairKey) {
                        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == pairKey } }
                    }
                    ChatMessagePair(
                        awaitingGitHubTurn = index == lastMessageIndex && awaitingGitHubTurn,
                        reservedTurnHeight = if (githubPromptMode && index == lastMessageIndex) (with(density) { transcriptViewportHeight.toDp() } - composerHeight - 16.dp - innerPadding.calculateTopPadding()).coerceAtLeast(0.dp) else 0.dp,
                        replyRevision = pendingReplies.hashCode().toLong(),
                        replyVisible = replyVisible,
                        onAnswerDisplayed = { displayed -> chatViewModel.acknowledgeDisplayedReplies(listOf(displayed), pendingReplies) },
                        currentResearchTools = { chatViewModel.chatToolConfig.value },
                        githubAssistantReferences = githubResponseLinkContext,
                        messageIndex = index,
                        message = message,
                        assistantMessages = groupedMessages.assistantMessages.getOrNull(index) ?: emptyList(),
                        agentRunsById = agentRunsById,
                        providerWaitDeadlines = providerWaitDeadlines,
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
                            inspectedCombinedTurn = chatMessagePairKey(message, index).takeIf { uid != null }
                            inspectedCombinedProfileUid = uid
                        },
                        smartSuggestionsEnabled = featureSettings.smartSuggestions || chatToolConfig.github != null,
                        isUserTyping = chatViewModel.question.text.isNotEmpty(),
                        targetMessageId = chatViewModel.targetMessageId,
                        onTargetResponseOffset = { targetResponseOffset = it },
                        onEditQuestion = chatViewModel::openUserMessageEditDialog,
                        onEditAssistant = chatViewModel::openAssistantMessageEditDialog,
                        loadCombinedBlocks = chatViewModel::combinedDocumentBlocks,
                        loadCombinedSourceBlocks = chatViewModel::combinedSourceBlocks,
                        resolveCombinedMessage = chatViewModel::resolveCombinedMessage,
                        onCopyText = { copiedText ->
                            scope.launch {
                                dev.chungjungsoo.gptmobile.util.SafeClipboard.copy(context, copiedText)
                            }
                        },
                        onPlatformClick = { turn, platform ->
                            isHoldingEntryCenter = false
                            isFollowingBottom = false
                            isModelTabPositionLocked = true
                            chatViewModel.updateChatPlatformIndex(turn, platform)
                        },
                        onPlatformLongPress = chatViewModel::togglePlatformDisabled,
                        onSelectText = chatViewModel::openSelectTextSheet,
                        onRetry = chatViewModel::retryChat,
                        reasoningRecoveries = reasoningRecoveries,
                        onRecheckReasoning = chatViewModel::recheckReasoningResponse,
                        onProfileSettings = onProfileSettings,
                        onManageBilling = openProfileBilling,
                        onSwitchToAuto = chatViewModel::switchProfileReasoningToAuto,
                        onRetryCombinedMerge = chatViewModel::retryCombinedMerge,
                        onReviseCombinedAnswer = chatViewModel::reviseCombinedAnswer,
                        onTryAnotherCombinedWriter = chatViewModel::tryAnotherCombinedWriter,
                        onReviewCombinedPrimary = { chatViewModel.reviewCombinedPrimary(message.id) },
                        onReviewCombinedFailures = { chatViewModel.reviewCombinedFailures(message.id) },
                        onRegenerateWithSelectedPrimary = chatViewModel::regenerateWithSelectedPrimary,
                        onCheckCombinedQueuedJob = chatViewModel::checkCombinedQueuedJob,
                        onCheckQueuedRun = chatViewModel::checkQueuedJob,
                        onUseCombinedReply = chatViewModel::useCombinedReply,
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
                    GitHubTurnDecisions(turnDecisions[message.id].orEmpty())
                    val researchExecution = researchExecutions.values.lastOrNull { it.accepted.chatId == chatRoom.id && it.accepted.originatingUserMessageId == message.id && it.accepted.targetSources > 0 }
                    researchExecution?.takeIf { showResearchPanels }?.let { execution ->
                        ResearchAttemptProgressCard(
                            execution = execution,
                            onStop = { chatViewModel.stopResearchAttempt(execution.accepted.rootWorkId) },
                            onRetry = { chatViewModel.retryResearch(execution.accepted.rootWorkId) },
                            onReviewInterruptedRequests = { chatViewModel.reviewInterruptedResearch(execution.accepted.rootWorkId) },
                            debugMode = debugMode,
                            profileStatistics = researchProfileStatistics[execution.accepted.rootWorkId].orEmpty(),
                            profileNames = appAllPlatforms.associate { it.uid to it.name }
                        )
                    }
                    val chatResearch = researchSessions.values.filter { session ->
                        session.chatId == chatRoom.id && (session.turnId.substringAfterLast(':').toIntOrNull() ?: agentRunsById[session.runId]?.userMessageId) == message.id
                    }
                    if (showResearchPanels && chatResearch.isNotEmpty() && researchExecution == null) {
                        val turnRuns = agentRunsById.values.filter { it.chatId == chatRoom.id && it.userMessageId == message.id }
                        val turnRow = groupedMessages.assistantMessages.getOrNull(index).orEmpty()
                        val selectedUid = inspectedCombinedProfileUid.takeIf { inspectedCombinedTurn == chatMessagePairKey(message, index) }
                        val owner = if (selectedUid != null) {
                            rememberCombinedResponseProfiles(responses = turnRow, platformUids = chatViewModel.enabledPlatformsInChat, profileNames = appAllPlatforms.associate { it.uid to it.name }, participatingUids = activePlatformUids.toSet(), preparing = false, runsById = agentRunsById).firstOrNull { it.uid == selectedUid }?.message
                        } else if (chatRoom.conversationMode == ConversationMode.COMBINED) {
                            turnRow.firstOrNull { it.isCombinedSynthesis() }
                        } else {
                            turnRow.getOrNull(indexStates.getOrElse(index) { 0 })
                        }
                        val displayedTimeline = owner?.effectiveTimeline().orEmpty()
                        val receipt = displayedTimeline.lastOrNull { it.researchDeliveryReceipt != null }?.researchDeliveryReceipt
                        val accepted = receipt?.displayValidityFor(owner?.effectiveContent()) == dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.ACCEPTED
                        val combinedQuality = displayedTimeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
                        val combinedComplete = combinedQuality?.let { it.complete && !it.correctionRequired && it.answerDigest == dev.chungjungsoo.gptmobile.data.research.researchHash(owner?.effectiveContent().orEmpty()) && it.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") }
                        val failure = if (accepted || combinedComplete == true) null else displayedTimeline.lastOrNull { it.runError != null }?.runError ?: owner?.takeUnless { it.hasHistoricalRevisionSelected() }?.currentRunId?.let(agentRunsById::get)?.takeIf { it.status == "FAILED" }?.terminalError
                        ResearchProgressCard(
                            chatResearch,
                            turnRuns.filter { it.runId in activeAgentRuns }.mapTo(mutableSetOf()) { it.runId },
                            chatViewModel::stopResearch,
                            writerFailure = failure,
                            onRecovery = { action -> chatViewModel.recoverResearch(message.id, action) },
                            deliveryReceipt = receipt,
                            cancelWriting = { chatViewModel.cancelResearchWriting(message.id) },
                            deliveryRevisionText = owner?.effectiveContent(),
                            profileStatistics = researchProfileStatistics[chatResearch.first().turnId].orEmpty(),
                            profileNames = appAllPlatforms.associate { it.uid to it.name } + turnRuns.associate { run -> run.runId to (appAllPlatforms.firstOrNull { it.uid == run.profileUid }?.name ?: run.profileUid) },
                            deliveryComplete = combinedComplete,
                            debugMode = debugMode
                        )
                    }
                }
                if (recoveryBlock != null && blockedResponse != null) {
                    item(key = "research-recovery-access") {
                        androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Saved work retained", style = MaterialTheme.typography.titleSmall)
                                Text(recoveryBlock.message, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    dismissedRecovery = null
                                    showRecoveryDetails = true
                                }) { Text("Review setup") }
                            }
                        }
                    }
                }
                gatewayWriteBlocker?.let { reason ->
                    item(key = "gateway-conversation-read-only") {
                        Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                gatewayQueueState?.let { queued ->
                    item(key = "gateway-queue-state") {
                        androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    when (queued.state) {
                                        dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState.QUEUED -> "Gateway message saved"
                                        dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState.WAITING_FOR_GATEWAY -> "Waiting for Gateway"
                                        dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState.RUNNING -> "Gateway work in progress"
                                        dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState.RECONCILING -> "Checking the previous Gateway job"
                                        dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState.BLOCKED -> "Gateway queue needs attention"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                queued.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (queued.queuedCount > 0) Text("${queued.queuedCount} saved message${if (queued.queuedCount == 1) "" else "s"} waiting", style = MaterialTheme.typography.bodySmall)
                                if (queued.state != dev.chungjungsoo.gptmobile.data.queue.GatewayQueuedState.RUNNING) {
                                    TextButton(onClick = chatViewModel::checkGatewayQueue) { Text("Check saved jobs") }
                                }
                                if (queued.canRetryModelPreparation) {
                                    queued.runId?.let { runId ->
                                        TextButton(onClick = { chatViewModel.retryGatewayModelPreparation(runId) }) { Text("Retry model preparation") }
                                    }
                                }
                                if (queued.canCancelUnsubmitted) TextButton(onClick = chatViewModel::cancelUnsubmittedGateway) { Text("Stop unsubmitted message") }
                            }
                        }
                    }
                }
                if (chatToolConfig.github != null) {
                    val activeMessage = groupedMessages.assistantMessages.flatten().lastOrNull { it.currentRunId in activeAgentRuns }
                    chatToolConfig.github?.let { github ->
                        item(key = "gateway-engineering-jobs") { GatewayEngineeringPanel(chatRoom.id, github, appAllPlatforms.firstOrNull { it.uid == github.mainProfileUid }, debugMode = debugMode) }
                    }
                    githubWorkingIndicator(isGenerating = activeMessage != null)
                }
                items(pendingPrompts.size, key = { "queued-${pendingPrompts[it].id}" }) { index ->
                    val prompt = pendingPrompts[index]
                    val receipt = if (chatToolConfig.github != null) gitHubCommitDecisionBubble(prompt.text, nativeDecisions) else null
                    QueuedPromptBubble(prompt, chatViewModel::editQueuedPrompt, chatViewModel::removeQueuedPrompt, chatViewModel::pauseQueuedPrompt, followUpProgress[prompt.id], receipt)
                }
                if (groupedMessages.userMessages.isNotEmpty()) {
                    item(key = "chat-bottom-anchor") {
                        Spacer(Modifier.size(1.dp))
                    }
                }
            }

            if (githubPromptMode) {
                val following = githubScrollMode == GitHubScrollMode.FOLLOW_BOTTOM && !githubGestureActive && !isUserDragging && !isModelTabPositionLocked
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 12.dp)) {
                    GitHubBottomIndicator(
                        listState = listState,
                        following = following,
                        generating = entryPositioned && awaitingGitHubTurn && !pendingGitHubInput && (activeGitHubResponse || githubResponseStarting),
                        animate = featureSettings.responseAnimation,
                        onFollow = {
                            isHoldingEntryCenter = false
                            isModelTabPositionLocked = false
                            promptAnchor.followBottom()
                            githubScrollMode = promptAnchor.mode
                            githubGestureActive = false
                            promptAnchorRevision++
                        }
                    )
                }
            } else if (!isFollowingBottom && listState.canScrollForward) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 12.dp)) {
                    ScrollToBottomButton(isGenerating = !isIdle) {
                        isHoldingEntryCenter = false
                        isModelTabPositionLocked = false
                        isFollowingBottom = featureSettings.smoothStreaming
                        if (!featureSettings.smoothStreaming) {
                            scope.launch { listState.animateToGitHubBottom(featureSettings.responseAnimation) }
                        }
                    }
                }
            }

            CombinedChatComposer(
                visible = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
                    .drawWithContent {
                        drawContent()
                        if (isLoaded && canUseChat) gitHubCompositionTiming?.let { it.trace.composerReady(it.cached) }
                    }
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    if (gatewayModelPreparation.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 128.dp).verticalScroll(rememberScrollState())) {
                            gatewayModelPreparation.values.forEach { state ->
                                key(state.profileUid, state.model) {
                                    dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayModelPreparationContent(
                                        state,
                                        onRecheck = { chatViewModel.recheckGatewayModel(state.profileUid) }
                                    )
                                }
                            }
                        }
                    }
                    if (!githubPromptMode && bottomInteractionContent != null) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = (viewportHeight * 0.42f).coerceAtMost(320.dp))
                                .verticalScroll(rememberScrollState())
                                .testTag("github_saved_action_notifications")
                        ) {
                            bottomInteractionContent()
                        }
                    }
                    if (githubPromptMode) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = (viewportHeight * 0.42f).coerceAtMost(320.dp))
                                .verticalScroll(rememberScrollState())
                                .testTag("github_action_notifications")
                        ) {
                            GatewayMergeInteractions(chatRoom.id)
                            bottomInteractionContent?.invoke()
                            visibleGitHubDecisions.forEach { decision ->
                                key(decision.id) {
                                    GitHubDecisionCard(decision, decision.id in githubWaiting, { option, answer ->
                                        chatViewModel.answerGitHubDecision(decision.id, option, answer)
                                    }, { chatViewModel.resumeGitHubDecision(decision.id) }, { chatViewModel.stopGitHubDecision(decision.id) })
                                }
                            }
                            approvals.forEach { approval ->
                                key(approval.id) {
                                    GitHubPendingPrompt {
                                        ToolApprovalDialog(
                                            approval = approval,
                                            onDeny = { chatViewModel.decideToolApproval(approval.id, false) },
                                            onAllowOnce = { chatViewModel.decideToolApproval(approval.id, true) },
                                            onAllowInConversation = { chatViewModel.allowToolInConversation(approval.id) },
                                            onAlwaysAllowTool = { chatViewModel.alwaysAllowTool(approval.id) },
                                            onAlwaysAllowProvider = { chatViewModel.alwaysAllowToolProvider(approval.id) },
                                            inline = true
                                        )
                                    }
                                }
                            }
                            inputRequests.forEach { request ->
                                key(request.id) { GitHubPendingPrompt { McpInputDialog(request, inline = true, respond = chatViewModel::respondMcpInput) } }
                            }
                            delegationRecoveryRequests.filter { it.chatId == chatRoom.id }.forEach { request ->
                                key(request.id) {
                                    GitHubPendingPrompt {
                                        DelegationRecoveryDialog(
                                            request,
                                            { profileUid -> chatViewModel.respondDelegationRecovery(request.id, profileUid) },
                                            { chatViewModel.respondDelegationRecovery(request.id, null) },
                                            inline = true
                                        )
                                    }
                                }
                            }
                            freeToolConsent?.let { request ->
                                GitHubPendingPrompt { FreeToolConsentDialog(request, chatViewModel::confirmFreeToolConsent, chatViewModel::dismissFreeToolConsent, inline = true) }
                            }
                        }
                    }
                    chatToolConfig.research?.takeIf { showResearchPanels }?.let { selection ->
                        val profileUid = activePlatformUids.firstOrNull().orEmpty()
                        val target = selection.applyTo(featureSettings.delegationFor(profileUid)).deepResearch.maxPages
                        androidx.compose.material3.AssistChip(
                            onClick = chatViewModel::openChatModelDialog,
                            label = { Text("Research · ${selection.depth}% · target $target") },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    nextMessageProfileUid?.let { uid ->
                        TextButton(onClick = { showProfileManagement = true }) {
                            Text(stringResource(R.string.next_message_label, enabledPlatformLookup[uid]?.name ?: uid))
                        }
                    }
                    ChatInputBox(
                        modifier = Modifier.fillMaxWidth(),
                        inputState = chatViewModel.question,
                        workflows = chatToolConfig.github?.let { github ->
                            featureSettings.githubProfileOverrides[github.mainProfileUid]?.quickWorkflows
                                ?: dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow.defaults
                        }.orEmpty(),
                        chatEnabled = canUseChat,
                        sendButtonEnabled = selectedAttachments.none { it.status != ChatAttachmentDraft.Status.Ready },
                        isPreparingSend = sendPreparation.isNotEmpty(),
                        isRunning = !isIdle,
                        queuedPromptCount = queuedPromptCount,
                        selectedAttachments = selectedAttachments,
                        onFileSelected = { filePath -> chatViewModel.addSelectedFile(filePath) },
                        onFileRemoved = { filePath -> chatViewModel.removeSelectedFile(filePath) },
                        onFileRetry = chatViewModel::retrySelectedFile,
                        onCancelButtonClick = chatViewModel::cancelActiveRuns,
                        onComposerFocusChanged = dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints::composerFocusChanged
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
                        }
                    }
                    if (chatViewModel.enabledPlatformsInChat.size > 1 || chatRoom.conversationMode == ConversationMode.COMBINED) {
                        val dockTurn = bottomProfileTurn
                        val dockMessage = groupedMessages.userMessages.getOrNull(dockTurn)
                        val dockKey = dockMessage?.let { chatMessagePairKey(it, dockTurn) }
                        val replies = groupedMessages.assistantMessages.getOrNull(dockTurn).orEmpty()
                        val combined = chatRoom.conversationMode == ConversationMode.COMBINED
                        val frozenProfiles = if (combined) {
                            rememberCombinedResponseProfiles(
                                replies,
                                chatViewModel.enabledPlatformsInChat,
                                enabledPlatformLookup.mapValues { it.value.name },
                                activePlatformUids.toSet() - disabledPlatformUids,
                                dockTurn == lastMessageIndex && !isIdle,
                                agentRunsById
                            )
                        } else {
                            emptyList()
                        }
                        ChatProfileDock(
                            profileUids = if (combined) activePlatformUids else chatViewModel.enabledPlatformsInChat,
                            names = enabledPlatformLookup.mapValues { it.value.name } + frozenProfiles.associate { it.uid to it.name },
                            selectedUid = if (combined) inspectedCombinedProfileUid.takeIf { inspectedCombinedTurn == dockKey } else chatViewModel.enabledPlatformsInChat.getOrNull(indexStates.getOrElse(dockTurn) { 0 }),
                            pausedUids = disabledPlatformUids,
                            generatingUids = if (combined) frozenProfiles.filter { it.status == CombinedResponseStatus.GENERATING }.mapTo(hashSetOf()) { it.uid } else chatViewModel.enabledPlatformsInChat.filterIndexed { index, _ -> dockTurn == lastMessageIndex && loadingStates.getOrNull(index) == ChatViewModel.LoadingState.Loading }.toSet(),
                            combined = combined,
                            combinedWaiting = combined && frozenProfiles.any { it.status != CombinedResponseStatus.COMPLETED },
                            combinedGenerating = combined && dockTurn >= 0 && combinedAnswerStatus(replies.firstOrNull { it.isCombinedSynthesis() }, frozenProfiles, agentRunsById) == CombinedResponseStatus.GENERATING,
                            onSelect = { uid ->
                                isHoldingEntryCenter = false
                                isFollowingBottom = false
                                isModelTabPositionLocked = true
                                if (combined) {
                                    inspectedCombinedTurn = dockKey.takeIf { uid != null }
                                    inspectedCombinedProfileUid = uid
                                } else if (uid != null && dockTurn >= 0) {
                                    val index = chatViewModel.enabledPlatformsInChat.indexOf(uid)
                                    if (index >= 0) chatViewModel.updateChatPlatformIndex(dockTurn, index)
                                }
                            },
                            onPause = chatViewModel::togglePlatformDisabled,
                            failedUids = if (combined) frozenProfiles.filter { it.status == CombinedResponseStatus.FAILED }.mapTo(hashSetOf()) { it.uid } + combinedFailures.filter { it.userMessageId == dockMessage?.id }.map { it.profileUid } else replies.filter { response -> response.currentRunId?.let(agentRunsById::get)?.status in setOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING, AgentRunStatus.BLOCKED) }.mapNotNull { it.platformType }.toSet(),
                            responseLabels = frozenProfiles.associate { profile ->
                                profile.uid to when {
                                    profile.message?.content.isNullOrBlank() -> "No response"
                                    profile.status == CombinedResponseStatus.FAILED -> "Partial answer"
                                    replies.firstOrNull { it.isCombinedSynthesis() }?.effectiveCombinedSources()?.any { it.platformUid == profile.uid } == true -> "Usable contribution"
                                    else -> "Response received"
                                }
                            },
                            combinedLabel = replies.firstOrNull { it.isCombinedSynthesis() }?.effectiveTimeline()?.lastOrNull { it.combinedQuality != null }?.combinedQuality?.let { if (it.complete) "Completion checks passed" else "Partial answer" },
                            combinedFailed = combined && dockTurn >= 0 && combinedAnswerStatus(replies.firstOrNull { it.isCombinedSynthesis() }, frozenProfiles, agentRunsById) == CombinedResponseStatus.FAILED,
                            profileColorIdentities = if (combined) {
                                ProfileAttributionPalette.forSelection(
                                    (chatViewModel.enabledPlatformsInChat + frozenProfiles.map { it.uid }).distinct(),
                                    featureSettings.profileAttributionColors
                                )
                            } else {
                                emptyMap()
                            }
                        )
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
            val initialProfileUid = groupedMessages.assistantMessages.lastOrNull()
                ?.getOrNull(indexStates.lastOrNull() ?: 0)?.platformType
                ?.takeIf { it in activePlatformUids && it !in disabledPlatformUids }
                ?: activePlatformUids.firstOrNull { it !in disabledPlatformUids }.orEmpty()
            var currentProfileUid by remember(initialProfileUid) { mutableStateOf(initialProfileUid) }
            val combinedSaveState by chatViewModel.combinedSettingsSaveState.collectAsStateWithLifecycle()
            val combinedConversationIdentity by chatViewModel.combinedConversationIdentity.collectAsStateWithLifecycle()
            ChatModelDialog(
                conversationMode = chatRoom.conversationMode,
                conversationIdentity = combinedConversationIdentity,
                chatId = chatRoom.id,
                combinedSettings = featureSettings.conversationCombined[chatRoom.id] ?: dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(),
                combinedSaveState = combinedSaveState,
                onConfirmCombined = chatViewModel::saveCombinedChatSettings,
                onSelectedProfileChanged = { currentProfileUid = it },
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
                researchSelection = chatToolConfig.research ?: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(depth = featureSettings.delegationFor(currentProfileUid).strategy),
                researchUnlockReason = dev.chungjungsoo.gptmobile.data.model.researchReadinessReason(chatToolConfig.research ?: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(), featureSettings, currentProfileUid, appAllPlatforms.firstOrNull { it.uid == currentProfileUid }?.let { !it.disableAllTools && !it.disableRemoteTools } == true),
                academicGateways = academicGateways,
                onResearchChanged = chatViewModel::setConversationResearch,
                onAcademicConsent = { chatViewModel.grantAcademicResearch(currentProfileUid, chatToolConfig.research?.scope != dev.chungjungsoo.gptmobile.data.model.ResearchContentScope.WEB_ONLY) },
                onSaveResearchDefault = { chatViewModel.saveResearchDepthDefault(currentProfileUid, chatToolConfig.research?.depth ?: featureSettings.delegationFor(currentProfileUid).strategy) },
                onDelegationChanged = chatViewModel::setConversationDelegation,
                initialReasoning = chatToolConfig.reasoning ?: appAllPlatforms.firstOrNull { it.uid in activePlatformUids }?.reasoning ?: false,
                onReasoningChanged = chatViewModel::setConversationReasoning,
                localAssist = featureSettings.localAssist,
                onLocalAssistChanged = chatViewModel::setLocalAssist,
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
                onDismissRequest = {
                    if (chatRoom.conversationMode == ConversationMode.COMBINED) chatViewModel.abortCombinedChatSettingsSave() else chatViewModel.closeChatModelDialog()
                },
                onConfirmRequest = { models, creativity ->
                    chatViewModel.updateChatPlatformModels(models)
                    chatViewModel.updateChatCreativity(creativity)
                    chatViewModel.closeChatModelDialog()
                }
            )
        }

        if (showOutline) {
            AlertDialog(
                onDismissRequest = { showOutline = false },
                title = { Text(stringResource(R.string.outline_title)) },
                text = {
                    LazyColumn {
                        if (hasOlderHistory) item { TextButton(onClick = chatViewModel::loadOlderMessages) { Text("Load earlier turns") } }
                        itemsIndexed(groupedMessages.userMessages, key = { index, message -> chatMessagePairKey(message, index) }) { index, message ->
                            TextButton(onClick = {
                                isFollowingBottom = false
                                isHoldingEntryCenter = false
                                entryPositioned = true
                                revealedArchivedTurns = groupedMessages.userMessages.size
                                pendingJumpKey = chatMessagePairKey(message, index)
                                showOutline = false
                            }) { Text(stringResource(R.string.outline_turn, index + 1, message.content.take(100)), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showOutline = false }) { Text(stringResource(R.string.close)) } }
            )
        }
        if (showProfileManagement) {
            ChatProfileManagementDialog(
                profiles = appAllPlatforms.filter { it.uid in activePlatformUids },
                pausedUids = disabledPlatformUids,
                onPause = chatViewModel::togglePlatformDisabled,
                onRemove = { chatViewModel.setPlatformMembership(it, false) },
                onDismiss = { showProfileManagement = false },
                nextMessageUid = nextMessageProfileUid,
                onNextMessage = if (chatRoom.conversationMode != ConversationMode.COMBINED) chatViewModel::selectNextMessageProfile else null
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
internal fun ChatMessagePair(
    reservedTurnHeight: Dp = 0.dp,
    awaitingGitHubTurn: Boolean = false,
    replyRevision: Long,
    replyVisible: Boolean,
    onAnswerDisplayed: (MessageV2) -> Unit,
    currentResearchTools: () -> dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig,
    githubAssistantReferences: GitHubAssistantReferenceContext? = null,
    messageIndex: Int,
    message: MessageV2,
    assistantMessages: List<MessageV2>,
    agentRunsById: Map<String, AgentRun>,
    providerWaitDeadlines: Map<String, dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline>,
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
    onEditQuestion: (MessageV2) -> Unit,
    onEditAssistant: (Int, Int) -> Unit,
    onCopyText: (String) -> Unit,
    onPlatformClick: (Int, Int) -> Unit,
    onPlatformLongPress: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onRetry: (Int, Int) -> Unit,
    reasoningRecoveries: Map<String, ReasoningRecoveryUiState> = emptyMap(),
    onRecheckReasoning: (Int, Int) -> Unit = { _, _ -> },
    onProfileSettings: (String) -> Unit = {},
    onManageBilling: (String) -> Unit = {},
    onSwitchToAuto: (String) -> Unit = {},
    onRetryCombinedMerge: (Int) -> Unit = {},
    onReviseCombinedAnswer: (Int, dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerRevisionTarget) -> Unit = { _, _ -> },
    onTryAnotherCombinedWriter: (Int) -> Unit = {},
    onReviewCombinedPrimary: () -> Unit = {},
    onReviewCombinedFailures: () -> Unit = {},
    onRegenerateWithSelectedPrimary: (Int) -> Unit = {},
    onCheckCombinedQueuedJob: (Int) -> Unit = {},
    onCheckQueuedRun: (String) -> Unit = {},
    onUseCombinedReply: (Int, String) -> Unit = { _, _ -> },
    onInspectContext: ((String) -> Unit)? = null,
    onFavoriteClick: (Int, Int) -> Unit,
    onFavoriteLongPress: () -> Unit,
    onShowPreviousRevision: (Int, Int) -> Unit,
    onShowNextRevision: (Int, Int) -> Unit,
    onContinueClick: () -> Unit = {},
    onActionClick: (String) -> Unit = {},
    loadCombinedBlocks: suspend (dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, Int, Int) -> List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock>,
    resolveCombinedMessage: suspend (MessageV2) -> MessageV2,
    loadCombinedSourceBlocks: suspend (dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse, Int, Int) -> List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock> = { _, _, _, _ -> emptyList() }
) {
    val documentScope = rememberCoroutineScope()
    val combinedSynthesisIndex = assistantMessages.indexOfFirst { it.isCombinedSynthesis() }
    val participatingUids = activePlatformUids - disabledPlatformUids
    val combinedProfiles = if (combinedMode) {
        rememberCombinedResponseProfiles(
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
    val combinedIdentities = remember(combinedProfiles, debugSettings.profileAttributionColors) {
        if (!combinedMode) {
            emptyMap()
        } else {
            ProfileAttributionPalette.forSelection(
                (combinedProfiles.map { it.uid } + assistantMessages.flatMap { it.effectiveCombinedSources() }.map { it.platformUid }).distinct(),
                debugSettings.profileAttributionColors
            )
        }
    }
    val attributionSurface = MaterialTheme.colorScheme.surface
    val attributionColors = remember(combinedIdentities, attributionSurface) {
        combinedIdentities.mapValues { (_, identity) ->
            ProfileAttributionPalette.foreground(identity, attributionSurface)
        }
    }
    LaunchedEffect(combinedIdentities, attributionColors, attributionSurface) {
        if (combinedMode && dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.enabled == true) {
            combinedIdentities.forEach { (uid, identity) ->
                val color = attributionColors.getValue(uid)
                if (CombinedAttributionDiagnostics.changed("palette:$uid", "$identity|$color|$attributionSurface")) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("CombinedPalette", "DISPLAY_COLOUR · profile=$uid · marker=${identity.marker} · hue=${identity.hue} · foreground=$color · background=$attributionSurface · contrast=${ProfileAttributionPalette.contrast(color, attributionSurface)}")
            }
        }
    }
    val hasCombinedTabs = combinedMode && (combinedSynthesisIndex >= 0 || combinedProfiles.size > 1)
    val selectedProfile = combinedProfiles.firstOrNull { it.uid == selectedCombinedProfileUid }.takeIf { hasCombinedTabs }
    val isSourceResponse = selectedProfile != null
    val isCombinedConversation = hasCombinedTabs && !isSourceResponse
    // Keep profile-coloured prose visible in chat; verbose match diagnostics are opt-in and debug-only.
    val combinedStatus = combinedAnswerStatus(assistantMessages.getOrNull(combinedSynthesisIndex), combinedProfiles, agentRunsById)
    val targetAssistantIndex = assistantMessages.indexOfFirst { targetMessageId > 0 && it.id == targetMessageId }
    val displayPlatformIndex = when {
        selectedProfile != null -> selectedProfile.assistantIndex
        isCombinedConversation -> combinedSynthesisIndex.takeIf { it >= 0 } ?: combinedProfiles.firstOrNull()?.assistantIndex ?: 0
        else -> platformIndexState
    }
    val synthesisMessage = assistantMessages.getOrNull(combinedSynthesisIndex)
    val sourceManifest = synthesisMessage?.effectiveCombinedManifest()
    val selectedSource = synthesisMessage?.effectiveCombinedSources()?.firstOrNull { it.platformUid == selectedProfile?.uid }
    val selectedSourceLoader = remember(sourceManifest, loadCombinedSourceBlocks) { combinedInspectionLoader(sourceManifest, loadCombinedSourceBlocks) }
    val selectedSourceReader = remember(selectedSource, selectedSourceLoader) {
        selectedSource?.takeIf { it.originalDocument != null }?.let {
            CombinedOriginalSourceReader(it, selectedSourceLoader, CombinedOperationOwner(messageId = synthesisMessage?.id, runId = sourceManifest?.runId, revisionId = sourceManifest?.revisionId, digest = it.originalDocument?.digest, profileUid = it.platformUid))
        }
    }
    DisposableEffect(selectedSourceReader) {
        selectedSourceReader?.beginTabTransition()
        onDispose { selectedSourceReader?.endTabTransition() }
    }
    val selectedAssistantMessage = if (selectedProfile != null) selectedProfile.message else assistantMessages.getOrNull(displayPlatformIndex)
    val responseInsetPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val synthesisStarted = isCombinedConversation && combinedSynthesisIndex >= 0
    val selectedRunId = selectedAssistantMessage?.effectiveRunId()
    val agentRun = selectedRunId?.let(agentRunsById::get)
    val waitingForProvider = agentRun?.status == AgentRunStatus.WAITING_PROVIDER_LIMIT
    val waitingForApproval = agentRun?.status == AgentRunStatus.WAITING_FOR_APPROVAL
    val providerRetryAt = selectedRunId?.let(providerWaitDeadlines::get)?.takeIf { it.runId == selectedRunId && it.fence == agentRun?.ownershipFence }?.effectiveRetryAt
    val effectiveAssistantContent = if (selectedSourceReader != null) "" else selectedAssistantMessage?.effectiveContent().orEmpty()
    val rawAssistantContent = remember(effectiveAssistantContent) {
        dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(effectiveAssistantContent)
    }
    val assistantContent = when {
        selectedSourceReader != null -> ""
        rawAssistantContent.isBlank() && selectedAssistantMessage?.currentRunId?.let(agentRunsById::get)?.status == AgentRunStatus.WAITING_REMOTE_JOB -> "Waiting for the saved remote job. Check its status without submitting another generation."
        rawAssistantContent.isBlank() && waitingForApproval -> stringResource(R.string.github_approval_wait_saved)
        rawAssistantContent.isBlank() && isCombinedConversation && combinedStatus == CombinedResponseStatus.PAUSED && !waitingForProvider -> stringResource(R.string.provider_wait_saved)
        isCombinedConversation && !synthesisStarted && combinedStatus == CombinedResponseStatus.FAILED -> stringResource(R.string.combined_all_profiles_failed)
        isCombinedConversation && !synthesisStarted -> ""
        isSourceResponse && selectedProfile.status == CombinedResponseStatus.FAILED && selectedAssistantMessage?.content.isNullOrBlank() -> stringResource(R.string.combined_profile_no_response)
        isCombinedConversation -> combinedVisibleAnswer(rawAssistantContent, debugMode = false)
        else -> if (LocalChatProgressInHeader.current) rawAssistantContent.trimStart('\n', '\r') else rawAssistantContent
    }
    val assistantThoughts = if (isCombinedConversation && !synthesisStarted) {
        ""
    } else {
        selectedAssistantMessage?.effectiveThoughts().orEmpty()
    }
    val assistantTimeline = if (isCombinedConversation && !synthesisStarted) {
        emptyList()
    } else {
        selectedAssistantMessage?.effectiveTimeline().orEmpty().let { timeline ->
            if (isCombinedConversation && rawAssistantContent != assistantContent) {
                combinedVisibleTimeline(timeline, assistantContent)
            } else {
                timeline
            }
        }
    }
    val githubTask = message.content.takeIf { LocalChatProgressInHeader.current && !hasCombinedTabs }
    val savedFailure = visibleResponseFailure(assistantTimeline.lastOrNull { it.runError != null }?.runError, agentRun?.terminalError, agentRun?.status)
    val nativeDecisions = LocalGitHubNativeDecisions.current
    val approvalCardOrReceipt = LocalGitHubApprovalPending.current || nativeDecisions.any { it.sourceMessageId == message.id }
    val awaitingGitHubDelivery = awaitingGitHubTurn || githubTask != null && gitHubApprovalRetryBlock(savedFailure, approvalCardOrReceipt) != null
    val readAssistantActionText: suspend () -> String = {
        when {
            selectedSourceReader != null -> selectedSourceReader.readAll()
            githubTask != null && !combinedMode && selectedAssistantMessage?.effectiveCombinedManifest() == null ->
                gitHubResponsePresentation(assistantContent, assistantTimeline).text
            else -> selectedAssistantMessage?.let { resolveCombinedMessage(it).effectiveContent() } ?: assistantContent
        }
    }
    val readLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val readLifecycleState by readLifecycle.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val retainedFailure = remember(selectedAssistantMessage, agentRun?.terminalError) { selectedAssistantMessage?.let { dev.chungjungsoo.gptmobile.data.agent.CombinedRecoveryFailure.retained(it, agentRun?.terminalError) } }
    val needsAnswerCorrection = isCombinedConversation && retainedFailure?.category == dev.chungjungsoo.gptmobile.data.agent.CombinedRecoveryCategory.REQUIREMENTS_UNMET
    val revisionTarget = remember(selectedAssistantMessage, agentRun, needsAnswerCorrection) {
        if (needsAnswerCorrection && selectedAssistantMessage != null && agentRun != null) runCatching { dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerRevisionTarget.capture(selectedAssistantMessage, agentRun) }.getOrNull() else null
    }
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
    val referenceReceipts = remember(githubAssistantReferences != null, provenanceToolEvents) {
        if (githubAssistantReferences == null) {
            emptyList()
        } else {
            provenanceToolEvents.takeLast(128).filter { it.arguments.length <= 8_192 && it.result.orEmpty().length <= 32_768 }.map { event ->
                GitHubReferenceReceipt(event.toolName, event.arguments, event.result)
            }
        }
    }
    val receiptScopedGitHubReferences = if (githubAssistantReferences == null) null else rememberGitHubReceiptReferences(githubAssistantReferences, referenceReceipts)
    val canShowPreviousRevision = !isSourceResponse &&
        (
            selectedAssistantMessage?.let { assistantMessage ->
                assistantMessage.revisions.isNotEmpty() &&
                    assistantMessage.activeRevisionIndex < assistantMessage.revisions.lastIndex
            } ?: false
            )
    val canShowNextRevision = !isSourceResponse &&
        (
            selectedAssistantMessage?.let { assistantMessage ->
                assistantMessage.revisions.isNotEmpty() &&
                    assistantMessage.activeRevisionIndex != ACTIVE_REVISION_LATEST
            } ?: false
            )
    val selectedPlatformUid = selectedProfile?.uid ?: selectedAssistantMessage?.platformType ?: enabledPlatformsInChat.getOrElse(displayPlatformIndex) { "" }
    val assistantManifest = selectedAssistantMessage?.effectiveCombinedManifest()
    val assistantMessageId = selectedAssistantMessage?.id
    val assistantRevisionIndex = selectedAssistantMessage?.activeRevisionIndex
    val assistantTiming = remember(assistantMessageId, selectedRunId, assistantManifest?.revisionId, assistantRevisionIndex, selectedPlatformUid, assistantTimeline.size, assistantManifest?.blockCount) {
        Modifier.diagnosticFrameTiming(
            CombinedOperationOwner(
                screen = "Chat",
                messageId = assistantMessageId,
                runId = selectedRunId,
                revisionId = assistantManifest?.revisionId ?: assistantRevisionIndex?.let { "selected:$it" },
                profileUid = selectedPlatformUid.takeIf(String::isNotBlank),
                uiContext = UiOperationContext("Assistant", itemCount = assistantTimeline.size, blockCount = assistantManifest?.blockCount)
            )
        )
    }
    val isCurrentPlatformLoading = when {
        isCombinedConversation -> combinedStatus == CombinedResponseStatus.GENERATING
        isSourceResponse -> selectedProfile.status == CombinedResponseStatus.GENERATING
        else -> isActiveMessage && loadingStates.getOrElse(displayPlatformIndex) { ChatViewModel.LoadingState.Idle } == ChatViewModel.LoadingState.Loading
    }
    val isResponseLoading = if (hasCombinedTabs) isCurrentPlatformLoading else shouldShowAssistantResponseProgress(isCurrentPlatformLoading, activeAgentRun != null, agentRun?.status)
    val answerCanBeAcknowledged = githubTask == null || !awaitingGitHubDelivery && !isResponseLoading
    LaunchedEffect(replyRevision, replyVisible, selectedAssistantMessage, rawAssistantContent, readLifecycleState, answerCanBeAcknowledged) {
        if (answerCanBeAcknowledged && replyVisible && !isSourceResponse && readLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            androidx.compose.runtime.withFrameNanos { }
            selectedAssistantMessage?.let { onAnswerDisplayed(it.copy(content = rawAssistantContent)) }
        }
    }
    val responseFailure = savedFailure.takeUnless { awaitingGitHubDelivery }
    val responseFailureRecord = responseFailure?.let { error ->
        assistantTimeline.lastOrNull { it.runError == error }?.profileFailure
            ?: dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(error)
    }
    val gatewayAdmission = gatewayAdmissionPresentation(responseFailureRecord)
    val repairGatewayProfile = debugSettings.githubProfileOverrides[selectedPlatformUid]?.engineeringGatewayProfileUid
        ?.let(enabledPlatformLookup::get)
    val approvalRetryBlock = gitHubApprovalRetryBlock(responseFailure, approvalCardOrReceipt)
    val reviewRetryBlock = approvalRetryBlock ?: dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.reviewRetryBlock(responseFailure)
    val responseRecoveryKind = responseFailure?.let { profileRecoveryKind(dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(it).category, it) } ?: ProfileRecoveryKind.NONE
    val gitHubChat = githubTask != null || currentResearchTools().github != null
    val commitDecision = remember(gitHubChat, message.content, nativeDecisions) { if (gitHubChat) gitHubCommitDecisionBubble(message.content, nativeDecisions) else null }
    val visiblePrompt = remember(gitHubChat, message.content) {
        if (gitHubChat && message.content.startsWith("GitHub workspace: ")) {
            val prompt = dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity.request(message.content)
            val preset = dev.chungjungsoo.gptmobile.data.github.GitHubWorkflowPreset.fromRequest(prompt)
            if (preset == null) prompt else prompt.removeSuffix("\n[github-workflow-preset:${preset.id}]").trimEnd()
        } else {
            message.content
        }
    }
    StableChatResponseViewport(contentKey = if (isCombinedConversation) "combined" else selectedPlatformUid, reservedHeight = reservedTurnHeight) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged {
                    if (targetAssistantIndex >= 0) onTargetResponseOffset(it.height + responseInsetPx)
                }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                if (commitDecision != null) {
                    GitHubDecisionBubble(commitDecision, Modifier.widthIn(max = maximumUserChatBubbleWidth))
                } else {
                    UserChatBubble(
                        modifier = Modifier.widthIn(max = maximumUserChatBubbleWidth),
                        text = visiblePrompt,
                        timestamp = (message.createdAt * 1000L).takeIf { debugSettings.messageTimestamps },
                        files = message.attachments.map { it.filePathForDisplay },
                        canEdit = canUseChat && isIdle,
                        onCopyClick = { onCopyText(visiblePrompt) },
                        onSelectClick = { onSelectText(visiblePrompt) },
                        onEditClick = { onEditQuestion(message) }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .then(assistantTiming)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = if (LocalChatProgressInHeader.current) 0.dp else 12.dp, bottom = 12.dp)
        ) {
            OpponentResponseContainer(
                isFavorite = selectedAssistantMessage?.isFavorite ?: false,
                modifier = Modifier
                    .fillMaxWidth()
                    .responseProfileSwipe(
                        count = if (hasCombinedTabs) combinedProfiles.size + 1 else enabledPlatformsInChat.size,
                        selected = if (hasCombinedTabs) combinedProfiles.indexOfFirst { it.uid == selectedCombinedProfileUid } + 1 else displayPlatformIndex,
                        select = { next -> if (hasCombinedTabs) onCombinedProfileClick(combinedProfiles.getOrNull(next - 1)?.uid) else onPlatformClick(messageIndex, next) }
                    )
            ) {
                if (!hasCombinedTabs && !LocalChatProgressInHeader.current) {
                    GPTMobileIcon(loading = shouldShowReplyLoadingIndicator(isActiveMessage, loadingStates))
                }
                if (isCombinedConversation && combinedSynthesisIndex < 0) {
                    val unavailable = assistantMessages.flatMap { it.timeline }.lastOrNull { it.combinedTurn?.deliveryStatus == "MERGE_UNAVAILABLE" }?.combinedTurn
                    if (unavailable != null) {
                        val anyUsableReply = combinedProfiles.any { it.message?.content?.isNotBlank() == true }
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (anyUsableReply) "Choose a primary for this answer" else "No usable replies yet", style = MaterialTheme.typography.titleSmall)
                            Text(unavailable.deliveryReason ?: if (anyUsableReply) "The selected primary cannot complete this answer. Your available replies are saved." else "The original request and saved drafts are available. Retry a profile or review the selection.", style = MaterialTheme.typography.bodySmall)
                            if (!anyUsableReply) TextButton(onClick = onReviewCombinedFailures) { Text("Review profile replies") }
                            TextButton(onClick = onReviewCombinedPrimary) { Text(if (anyUsableReply) "Review primary" else "Review profiles") }
                            TextButton(onClick = { onRegenerateWithSelectedPrimary(messageIndex) }, enabled = isIdle && canUseChat) { Text("Regenerate with selected primary") }
                        }
                    }
                }
                if (hasCombinedTabs && combinedStatus != CombinedResponseStatus.GENERATING) {
                    val retained = combinedProfiles.count { it.message?.effectiveContent()?.isNotBlank() == true }
                    if (combinedStatus == CombinedResponseStatus.FAILED || needsAnswerCorrection || isSourceResponse) {
                        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (needsAnswerCorrection) "Answer needs correction" else stringResource(if (isSourceResponse) R.string.audit_saved_reply else R.string.audit_combining_failed), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.audit_replies_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                retainedFailure?.let { failure ->
                                    Text(
                                        if (needsAnswerCorrection) {
                                            "The answer finished, but does not meet all of your request. A revision starts only when you ask for one."
                                        } else if (failure.remoteJobStatus == "PENDING") {
                                            "The local wait ended; the remote job is still pending. Its receipt is saved. Use Check queued job to inspect it without submitting again."
                                        } else {
                                            "Final delivery: ${failure.category.name.lowercase().replace('_', ' ')}"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                                    if (combinedSynthesisIndex >= 0 && (combinedStatus == CombinedResponseStatus.FAILED || needsAnswerCorrection)) {
                                        if (needsAnswerCorrection) {
                                            TextButton(onClick = { revisionTarget?.let { onReviseCombinedAnswer(messageIndex, it) } }, enabled = isIdle && canUseChat && revisionTarget != null) { Text("Create revised answer") }
                                        } else {
                                            TextButton(onClick = { onRetryCombinedMerge(messageIndex) }, enabled = isIdle && canUseChat) { Text(stringResource(R.string.audit_retry_combining)) }
                                        }
                                    }
                                    if (combinedSynthesisIndex >= 0 && combinedStatus == CombinedResponseStatus.FAILED && !needsAnswerCorrection) TextButton(onClick = { onTryAnotherCombinedWriter(messageIndex) }, enabled = isIdle && canUseChat) { Text("Try another writer") }
                                    if (agentRunsById.values.any { it.userMessageId == message.id && it.terminalError?.contains("QUEUE_JOB_PENDING") == true }) TextButton(onClick = { onCheckCombinedQueuedJob(messageIndex) }, enabled = isIdle && canUseChat) { Text("Check queued job") }
                                    if (!isSourceResponse) {
                                        androidx.compose.material3.FilledTonalButton(onClick = {
                                            val useful = combinedProfiles.firstOrNull { profile ->
                                                CombinedContributionUsability.assess(null, profile.message?.effectiveContent().orEmpty(), allowPartial = true, request = message.content, demo = profile.name.contains("PorchLM", true)).admitted
                                            } ?: combinedProfiles.firstOrNull()
                                            useful?.let { onCombinedProfileClick(it.uid) }
                                        }) { Text(stringResource(R.string.audit_view_replies)) }
                                    }
                                    if (isSourceResponse && combinedSynthesisIndex >= 0 && (assistantContent.isNotBlank() || selectedSourceReader != null)) TextButton(onClick = { selectedProfile?.let { onUseCombinedReply(messageIndex, it.uid) } }, enabled = isIdle && canUseChat) { Text("Use reply") }
                                }
                            }
                        }
                    }
                }
                if (hasCombinedTabs && combinedStatus == CombinedResponseStatus.COMPLETED && agentRunsById.values.any { it.userMessageId == message.id && it.terminalError?.contains("QUEUE_JOB_PENDING") == true }) {
                    TextButton(onClick = { onCheckCombinedQueuedJob(messageIndex) }, enabled = isIdle && canUseChat) { Text("Check earlier queued job") }
                }
                if ((!hasCombinedTabs || combinedStatus != CombinedResponseStatus.COMPLETED) && agentRunsById.values.any { it.userMessageId == message.id && it.status == AgentRunStatus.WAITING_REMOTE_JOB }) {
                    TextButton(onClick = { onCheckCombinedQueuedJob(messageIndex) }, enabled = isIdle && canUseChat) { Text("Check queued job") }
                }
                if (hasCombinedTabs && selectedProfile == null) {
                    if (selectedAssistantMessage?.hasHistoricalRevisionSelected() == true && selectedAssistantMessage.effectiveCombinedSources().isEmpty()) {
                        Text(
                            stringResource(R.string.combined_archived_sources_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }
                if (waitingForProvider) {
                    val formatter = android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current)
                    val label = providerRetryAt?.let { stringResource(R.string.provider_wait_retry_at, formatter.format(java.util.Date(it))) }
                        ?: stringResource(R.string.provider_wait_saved)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalGitHubAssistantReferences provides receiptScopedGitHubReferences) {
                    OpponentChatBubble(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (!hasCombinedTabs && selectedPlatformUid in disabledPlatformUids) 0.5f else 1f)
                            .padding(horizontal = 2.dp)
                            .widthIn(max = maximumOpponentChatBubbleWidth),
                        canEdit = canUseChat && isIdle && !isSourceResponse,
                        canRetry = !waitingForProvider &&
                            responseRecoveryKind == ProfileRecoveryKind.NONE &&
                            if (isCombinedConversation) {
                                canUseChat &&
                                    isIdle &&
                                    !isCurrentPlatformLoading &&
                                    activeAgentRun == null &&
                                    selectedAssistantMessage != null &&
                                    (!needsAnswerCorrection || revisionTarget != null) &&
                                    selectedPlatformUid in participatingUids &&
                                    enabledPlatformLookup[selectedPlatformUid]?.enabled == true
                            } else {
                                reviewRetryBlock == null &&
                                    !isSourceResponse &&
                                    canUseChat &&
                                    (isActiveMessage || agentRun?.status in setOf(AgentRunStatus.FAILED, AgentRunStatus.INTERRUPTED, AgentRunStatus.CANCELED, AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING, AgentRunStatus.BLOCKED)) &&
                                    !isCurrentPlatformLoading &&
                                    selectedPlatformUid in participatingUids
                            },
                        retryLabel = if (needsAnswerCorrection) {
                            "Create revised answer"
                        } else if (isCombinedConversation) {
                            stringResource(if (combinedStatus == CombinedResponseStatus.FAILED) R.string.combined_retry else R.string.combined_regenerate)
                        } else {
                            stringResource(R.string.audit_generate_again)
                        },
                        isLoading = isResponseLoading,
                        isError = agentRun?.status == AgentRunStatus.FAILED && isAssistantErrorMessage(assistantContent),
                        isFavorite = selectedAssistantMessage?.isFavorite ?: false,
                        debugMode = debugMode,
                        showExecutionTrace = !isCombinedConversation,
                        githubTask = githubTask,
                        awaitingGitHubDelivery = awaitingGitHubDelivery,
                        responseIcon = if (githubTask != null) {
                            { GPTMobileIcon(loading = isResponseLoading, github = true) }
                        } else {
                            null
                        },
                        profileColorIdentity = selectedProfile?.uid?.let(combinedIdentities::get).takeIf { isSourceResponse },
                        invocations = remember(invocationDiagnostics, selectedRunId) { invocationDiagnostics.filter { it.parentRunId == selectedRunId } },
                        debugSettings = debugSettings,
                        debugMemorySources = debugMemorySources,
                        showReasoning = showReasoning,
                        text = assistantContent,
                        combinedManifest = selectedAssistantMessage?.effectiveCombinedManifest(),
                        originalSource = selectedSourceReader,
                        loadCombinedBlocks = loadCombinedBlocks,
                        timestamp = selectedAssistantMessage?.let { it.createdAt * 1000L }?.takeIf { debugSettings.messageTimestamps },
                        thoughts = assistantThoughts,
                        timeline = assistantTimeline,
                        attachments = selectedAssistantMessage?.attachments.orEmpty().map { it.filePathForDisplay },
                        agentRun = agentRun,
                        conversationId = selectedAssistantMessage?.chatId,
                        showSourceBar = !isCombinedConversation,
                        combinedWording = if (hasCombinedTabs && selectedProfile == null) {
                            CombinedWordingPresentation(
                                assistantMessages.getOrNull(combinedSynthesisIndex)?.effectiveCombinedSources().orEmpty(),
                                attributionColors,
                                assistantMessages.getOrNull(combinedSynthesisIndex)?.effectiveTimeline()?.lastOrNull { it.combinedQuality != null }?.combinedQuality?.passages.orEmpty(),
                                assistantMessages.getOrNull(combinedSynthesisIndex)?.effectiveContent().orEmpty(),
                                profileMarkers = combinedIdentities.mapValues { it.value.marker.toString() },
                                loadSourceBlocks = remember(selectedAssistantMessage?.effectiveCombinedManifest(), loadCombinedSourceBlocks) { combinedInspectionLoader(selectedAssistantMessage?.effectiveCombinedManifest(), loadCombinedSourceBlocks) }
                            )
                        } else {
                            null
                        },
                        // Shared contribution controls render inside the mapped Markdown tree.
                        articleCards = { segment ->
                            if (debugSettings.researchFeature.articleCards) {
                                selectedAssistantMessage?.let { articleMessage ->
                                    dev.chungjungsoo.gptmobile.presentation.ui.research.ResearchCitationCards(articleMessage, segment, selectedPlatformUid.orEmpty(), debugSettings.researchFeature.compactCards, currentResearchTools, isStreaming = isCurrentPlatformLoading)
                                }
                            }
                        },
                        generationTiming = responseGenerationTiming(
                            agentRun,
                            if (isCombinedConversation) combinedProfiles.mapNotNull { it.message?.effectiveRunId()?.let(agentRunsById::get) } else emptyList()
                        ),
                        runNotices = selectedRunId?.let(runNoticesById::get).orEmpty(),
                        toolEvents = toolEvents,
                        locationToolEvents = provenanceToolEvents,
                        sourceToolEvents = provenanceToolEvents,
                        sourceProfilesByRun = remember(agentRunsById) { agentRunsById.mapValues { it.value.profileUid } },
                        contentIdentity = "${selectedAssistantMessage?.id ?: "turn:$messageIndex"}:$selectedPlatformUid:${selectedRunId.orEmpty()}:${selectedAssistantMessage?.activeRevisionIndex}:${selectedSource?.originalDocument?.digest.orEmpty()}",
                        revisionIndexLabel = selectedAssistantMessage?.takeIf { !isSourceResponse && it.revisions.isNotEmpty() }?.let { assistantMessage ->
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
                        onCopyClick = {
                            documentScope.launch { onCopyText(readAssistantActionText()) }
                        },
                        onSelectClick = {
                            documentScope.launch { onSelectText(readAssistantActionText()) }
                        },
                        onRetryClick = {
                            if (needsAnswerCorrection) revisionTarget?.let { onReviseCombinedAnswer(messageIndex, it) } else onRetry(messageIndex, displayPlatformIndex)
                        },
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
                responseFailure?.takeUnless { approvalRetryBlock != null }?.let { error ->
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            if (gatewayAdmission != null) {
                                gatewayAdmission.title
                            } else if (agentRun?.status == AgentRunStatus.PARTIAL) {
                                "Work remaining"
                            } else if (agentRun?.status == AgentRunStatus.EVIDENCE_MISSING) {
                                "Evidence needed"
                            } else if (agentRun?.status == AgentRunStatus.BLOCKED) {
                                "Work blocked"
                            } else {
                                stringResource(R.string.audit_response_interrupted)
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(error, style = MaterialTheme.typography.bodySmall)
                        gatewayAdmission?.let { admission ->
                            Text(admission.guidance, style = MaterialTheme.typography.bodySmall)
                            repairGatewayProfile?.let { gateway ->
                                TextButton(onClick = { onProfileSettings(gateway.uid) }, enabled = isIdle && canUseChat) {
                                    Text("Gateway connection settings · ${gateway.name}")
                                }
                            }
                            if (admission.canRetryAfterRepair && !hasCombinedTabs) {
                                TextButton(onClick = { onRetry(messageIndex, displayPlatformIndex) }, enabled = isIdle && canUseChat && reviewRetryBlock == null) {
                                    Text("Recheck gateway and retry")
                                }
                            }
                        }
                        if (error.contains("QUEUE_") && selectedRunId != null) TextButton(onClick = { onCheckQueuedRun(selectedRunId) }) { Text(stringResource(R.string.audit_check_status)) }
                        if (!hasCombinedTabs) {
                            reviewRetryBlock?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (gatewayAdmission == null && responseRecoveryKind == ProfileRecoveryKind.NONE && approvalRetryBlock == null) {
                                TextButton(onClick = { onRetry(messageIndex, displayPlatformIndex) }, enabled = isIdle && canUseChat && reviewRetryBlock == null) { Text(if (agentRun?.status in setOf(AgentRunStatus.PARTIAL, AgentRunStatus.EVIDENCE_MISSING) && githubTask != null) "Resume saved task" else stringResource(R.string.audit_generate_again)) }
                            } else if (gatewayAdmission == null && responseRecoveryKind != ProfileRecoveryKind.NONE) {
                                ProfileRecoveryActions(
                                    kind = responseRecoveryKind,
                                    profileName = enabledPlatformLookup[selectedPlatformUid]?.name ?: "Saved profile",
                                    state = reasoningRecoveries[selectedRunId],
                                    canManageBilling = profileBillingUrl(enabledPlatformLookup[selectedPlatformUid]) != null,
                                    onSettings = { onProfileSettings(selectedPlatformUid) },
                                    onBilling = { onManageBilling(selectedPlatformUid) },
                                    onRecheck = { onRecheckReasoning(messageIndex, displayPlatformIndex) },
                                    onSwitchToAuto = { onSwitchToAuto(selectedPlatformUid) },
                                    onRetry = { onRetry(messageIndex, displayPlatformIndex) },
                                    enabled = isIdle && canUseChat
                                )
                            }
                        }
                    }
                }
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
    isScrollingAway: Boolean,
    followIntentIsExplicit: Boolean = false
): Boolean = isFollowing &&
    !isUserDragging &&
    // GitHub's nested-scroll owner clears explicit follow intent on upward input.
    // The last direction may still describe that old gesture when our animation starts.
    (followIntentIsExplicit || !(isScrollInProgress && isScrollingAway))

@Composable
internal fun ChatBottomAutoScroller(
    listState: LazyListState,
    isEnabled: Boolean,
    animate: Boolean = true,
    githubMotion: Boolean = false
) {
    LaunchedEffect(listState, isEnabled, animate, githubMotion) {
        if (!isEnabled) return@LaunchedEffect

        // Observe measured growth, not token count. User gestures cancel the animation.
        snapshotFlow {
            val info = listState.layoutInfo
            // The final item is a fixed-height bottom anchor. Follow growth in
            // measured message rows and composer/keyboard viewport changes too,
            // without reacting to their scroll offsets or individual text tokens.
            Triple(
                info.totalItemsCount,
                info.visibleItemsInfo.map { it.index to it.size },
                Triple(info.viewportSize.height, info.beforeContentPadding, info.afterContentPadding)
            )
        }.distinctUntilChanged().conflate().collect { (totalItems, _, _) ->
            // Coalesce on the UI frame clock so layout, animation and Compose
            // idling all observe the pending scroll. A wall-clock delay can leave
            // the initial/growing viewport idle before following has started.
            androidx.compose.runtime.withFrameNanos { }
            if (totalItems > 0 && listState.canScrollForward) {
                val nearBottom = listState.gitHubDistanceToBottom() < listState.layoutInfo.viewportSize.height
                listState.animateToGitHubBottom(animate, durationMillis = if (githubMotion || !nearBottom) GITHUB_BOTTOM_FOLLOW_MILLIS else 240)
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
    animateToGitHubBottom()
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
    onDisablePlatformClick: () -> Unit = {},
    onOutlineClick: () -> Unit = {}
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
                modifier = Modifier.size(48.dp)
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
                },
                onOutlineClick = {
                    onOutlineClick()
                    isDropDownMenuExpanded = false
                }
            )
        },
        expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
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
    onDisablePlatformClick: () -> Unit = {},
    onOutlineClick: () -> Unit = {}
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
        DropdownMenuItem(
            text = { Text(stringResource(R.string.outline_open)) },
            onClick = {
                onOutlineClick()
                onDismissRequest()
            },
            enabled = isMenuItemEnabled
        )
        /* Manage profiles in current conversation */
        DropdownMenuItem(
            colors = androidx.compose.material3.MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.primary, leadingIconColor = MaterialTheme.colorScheme.primary, trailingIconColor = MaterialTheme.colorScheme.primary, disabledTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledLeadingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), disabledTrailingIconColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
            enabled = isMenuItemEnabled,
            text = { Text(text = stringResource(R.string.manage_chat_profiles)) },
            onClick = {
                onDisablePlatformClick()
                onDismissRequest()
            }
        )
    }
}

private suspend fun exportChat(context: Context, chatViewModel: ChatViewModel, format: ChatExportFormat, reducedMemory: Boolean = false, resume: Boolean = false, fullHistory: Boolean = false) {
    try {
        if (format == ChatExportFormat.DEBUG) {
            Toast.makeText(context, "Preparing detailed conversation diagnostics…", Toast.LENGTH_SHORT).show()
        }
        val file = if (format == ChatExportFormat.DEBUG) {
            withContext(Dispatchers.IO) {
                val output = context.getExternalFilesDir(null) ?: File(context.cacheDir, "diagnostics").apply { mkdirs() }
                chatViewModel.exportDebugFile(output, reducedMemory, resume, fullHistory)
            }
        } else {
            val (fileName, fileContent) = chatViewModel.exportChat(format)
            if (fileContent.isBlank()) {
                Toast.makeText(context, "No AI responses to export yet.", Toast.LENGTH_SHORT).show()
                return
            }
            withContext(Dispatchers.IO) {
                val output = context.getExternalFilesDir(null) ?: File(context.cacheDir, "diagnostics").apply { mkdirs() }
                val destination = File(output, fileName)
                val staging = File(destination.parentFile, ".${destination.name}.partial")
                try {
                    staging.writeText(fileContent)
                    check(staging.renameTo(destination)) { "Unable to publish the completed export" }
                    destination
                } finally {
                    staging.delete()
                }
            }
        }
        if (format == ChatExportFormat.DEBUG) {
            chatViewModel.diagnosticExportNotice.value?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
        val uri = withContext(Dispatchers.IO) {
            check(file.isFile && file.length() > 0L) { "Export is missing or empty." }
            val provided = getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.contentResolver.openInputStream(provided)?.use { stream ->
                check(stream.read() != -1) { "Export provider returned an empty file." }
            } ?: error("Export provider cannot open the file.")
            provided
        }
        context.startActivity(chatExportChooser(uri, file.name, format.mimeType))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticExportBusy) {
        Toast.makeText(context, "Another diagnostic export is running. Wait for it to finish, then retry.", Toast.LENGTH_LONG).show()
    } catch (_: dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkDeferred) {
        Toast.makeText(context, "Not enough memory to export yet. Stop generation or reopen the chat, then try again.", Toast.LENGTH_LONG).show()
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
    onFileRetry: (String) -> Unit = {},
    onComposerFocusChanged: (Boolean) -> Unit = {},
    placeholder: String? = null,
    attachmentsEnabled: Boolean = true,
    workflows: List<dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow> = emptyList(),
    isPreparingSend: Boolean = false,
    onSendButtonClick: () -> Unit = {}
) {
    val localStyle = LocalTextStyle.current
    val inputTokens = appOverlayTokens(opacity = CHAT_INPUT_OPACITY)
    val inputShape = MaterialTheme.shapes.large
    val mergedStyle = localStyle.merge(TextStyle(color = inputTokens.foreground))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatInputLineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 20)
    val hasQuestionText = inputState.text.isNotEmpty()
    val inputFocus = remember(inputState) { androidx.compose.ui.focus.FocusRequester() }
    val composerContainerFocus = remember(inputState) { androidx.compose.ui.focus.FocusRequester() }
    val composerFocusManager = LocalFocusManager.current
    val composerKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val reportComposerFocus by androidx.compose.runtime.rememberUpdatedState(onComposerFocusChanged)
    var composerFocused by remember(inputState) { mutableStateOf(false) }
    var restingFocusEnabled by remember(inputState) { mutableStateOf(false) }
    var restingFocused by remember(inputState) { mutableStateOf(false) }
    fun releaseComposerInput(disposing: Boolean = false) {
        // A tab can remove only the composer while the parent chat stays mounted.
        // Release this editor's input session without dismissing another field's focus.
        if (composerFocused) {
            composerKeyboard?.hide()
            if (disposing) {
                composerFocusManager.clearFocus(force = true)
                composerFocused = false
            } else {
                // Clearing the Compose owner can immediately refocus its first editor
                // in keyboard mode. Keep focus on this noneditable container instead.
                inputFocus.freeFocus()
                restingFocusEnabled = true
                if (composerContainerFocus.requestFocus()) {
                    // Send callbacks must observe release before dispatch, even if a
                    // focus observer is refreshed after this successful transfer.
                    composerFocused = false
                } else {
                    restingFocusEnabled = false
                }
            }
        }
        reportComposerFocus(composerFocused)
    }
    DisposableEffect(inputState, composerFocusManager, composerKeyboard) {
        onDispose { releaseComposerInput(disposing = true) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { releaseComposerInput() }
    LaunchedEffect(chatEnabled) {
        if (!chatEnabled) releaseComposerInput()
    }
    LaunchedEffect(isRunning) {
        // A run may start through a quick action or a restored request. Release
        // the previous editor session once; a new draft may still be typed while it runs.
        if (isRunning) releaseComposerInput()
    }
    val haptic = dev.chungjungsoo.gptmobile.presentation.common.rememberAppHaptic()
    var composerNotice by remember { mutableStateOf("") }
    val sendAction = {
        if (chatEnabled && sendButtonEnabled && !isPreparingSend && (inputState.text.isNotEmpty() || selectedAttachments.isNotEmpty())) {
            haptic(dev.chungjungsoo.gptmobile.presentation.common.AppHaptic.CONFIRMATION)
            composerNotice = "Sending message"
            releaseComposerInput()
            onSendButtonClick()
        }
    }
    var attachmentMenu by remember { mutableStateOf(false) }
    LaunchedEffect(composerNotice) {
        if (composerNotice.isNotEmpty()) {
            kotlinx.coroutines.delay(2000)
            composerNotice = ""
        }
    }
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
            .focusRequester(composerContainerFocus)
            .focusProperties { canFocus = restingFocusEnabled }
            .onFocusChanged {
                // This is a temporary destination, never an extra keyboard tab stop.
                if (restingFocused && !it.isFocused) restingFocusEnabled = false
                restingFocused = it.isFocused
            }
            .focusTarget()
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true }.glassBackdrop(inputShape, opacity = CHAT_INPUT_OPACITY, force = true).testTag("chat_input_glass"),
            shape = inputShape,
            color = if (hasGlassBackdrop(force = true)) Color.Transparent else inputTokens.background,
            contentColor = inputTokens.foreground,
            tonalElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            shadowElevation = 0.dp
        ) {
            Column {
                ChatMemoryStatus()
                val submissionNotice = if (isPreparingSend) stringResource(R.string.preparing_message) else composerNotice
                if (submissionNotice.isNotEmpty()) Text(submissionNotice, style = MaterialTheme.typography.labelSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (selectedAttachments.isNotEmpty()) {
                    FileThumbnailRow(
                        selectedAttachments = selectedAttachments,
                        onFileRemoved = onFileRemoved,
                        onFileRetry = onFileRetry
                    )
                }
                key(inputState) {
                    BasicTextField(
                        state = inputState,
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).semantics { contentDescription = "Message input" }.onPreviewKeyEvent {
                            if (it.isCtrlPressed && it.key == androidx.compose.ui.input.key.Key.Enter) {
                                if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) sendAction()
                                true
                            } else {
                                false
                            }
                        }.onFocusChanged {
                            composerFocused = it.isFocused
                            reportComposerFocus(it.isFocused)
                        },
                        enabled = chatEnabled,
                        textStyle = mergedStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = chatInputLineLimits,
                        decorator = { innerTextField ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (attachmentsEnabled) {
                                    Box {
                                        IconButton(enabled = chatEnabled, onClick = { attachmentMenu = true }) {
                                            Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.attach_file))
                                        }
                                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                                            listOf(R.string.attach_images to "image/*", R.string.attach_documents to "application/pdf", R.string.attach_any_file to "*/*").forEach { (label, mime) ->
                                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                                    attachmentMenu = false
                                                    filePickerLauncher.launch(mime)
                                                })
                                            }
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                ) {
                                    if (inputState.text.isEmpty()) {
                                        Text(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            text = placeholder ?: if (chatEnabled) stringResource(R.string.write_a_message) else stringResource(R.string.some_platforms_disabled)
                                        )
                                    }
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        innerTextField()
                                    }
                                }
                                if (isRunning) {
                                    IconButton(
                                        onClick = onCancelButtonClick,
                                        modifier = Modifier.testTag("chat_stop")
                                    ) {
                                        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.cancel_active_runs))
                                    }
                                }
                                if (!hasQuestionText && selectedAttachments.isEmpty() && workflows.isNotEmpty()) {
                                    GitHubWorkflowButton(workflows, enabled = chatEnabled && sendButtonEnabled && !isPreparingSend) { workflow ->
                                        inputState.edit { replace(0, length, workflow.prompt) }
                                        sendAction()
                                    }
                                } else {
                                    FilledIconButton(
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                                        enabled = chatEnabled && sendButtonEnabled && !isPreparingSend && (hasQuestionText || selectedAttachments.isNotEmpty()),
                                        onClick = sendAction,
                                        modifier = Modifier.testTag("chat_send")
                                    ) {
                                        if (isPreparingSend) {
                                            val preparingDescription = stringResource(R.string.preparing_message)
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp).semantics { contentDescription = preparingDescription },
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowUpward,
                                                contentDescription = stringResource(if (isRunning) R.string.send_follow_up else R.string.send)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun FileThumbnailRow(
    selectedAttachments: List<ChatAttachmentDraft>,
    onFileRemoved: (String) -> Unit,
    onFileRetry: (String) -> Unit = {}
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
                onRemove = { onFileRemoved(attachment.sourceFilePath) },
                onRetry = { onFileRetry(attachment.sourceFilePath) }
            )
        }
    }
}

@Composable
internal fun FileThumbnail(
    attachment: ChatAttachmentDraft,
    onRemove: () -> Unit,
    onRetry: () -> Unit = {}
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

        attachment.attachment?.sizeBytes?.let { size ->
            Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, size), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            stringResource(
                when (attachment.status) {
                    ChatAttachmentDraft.Status.Preparing -> R.string.attachment_preparing
                    ChatAttachmentDraft.Status.Ready -> R.string.attachment_ready
                    ChatAttachmentDraft.Status.Failed -> R.string.attachment_failed
                }
            ),
            style = MaterialTheme.typography.labelSmall
        )
        if (attachment.status == ChatAttachmentDraft.Status.Failed) TextButton(onClick = onRetry) { Text(stringResource(R.string.attachment_retry)) }
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

internal const val CHAT_INPUT_OPACITY = 0.85f
internal const val CHAT_LATEST_OPACITY = 0.50f

@Composable
private fun GitHubBottomIndicator(
    listState: LazyListState,
    following: Boolean,
    generating: Boolean,
    animate: Boolean,
    onFollow: () -> Unit
) {
    if (!following) {
        IconButton(onClick = onFollow, modifier = Modifier.testTag("chat_jump_to_bottom")) {
            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom_icon), tint = MaterialTheme.colorScheme.primary)
        }
        return
    }
    var reachedBottom by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount > 0 && !listState.canScrollForward }.first { it }
        reachedBottom = true
    }
    if (!reachedBottom || !generating) return
    val pulse = if (animate) rememberInfiniteTransition(label = "GitHub generation") else null
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier.size(width = 48.dp, height = 48.dp)
            .semantics { contentDescription = "Generating response" }
            .testTag("github_bottom_generation"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val opacity = pulse?.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(index * 120)
                ),
                label = "Dot ${index + 1}"
            )
            Spacer(
                Modifier.size(5.dp).graphicsLayer { alpha = opacity?.value ?: 1f }
                    .background(color, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}

@Composable
fun ScrollToBottomButton(isGenerating: Boolean = false, onClick: () -> Unit) {
    val tokens = appOverlayTokens(opacity = CHAT_LATEST_OPACITY)
    val shape = androidx.compose.foundation.shape.CircleShape
    // Keep the glass at exactly 50%; animate neither the backdrop nor the icon's alpha.
    // Generation remains distinguishable through the themed outline.
    Box(Modifier.size(48.dp).testTag("chat_jump_to_bottom").clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(26.dp).glassBackdrop(shape, opacity = CHAT_LATEST_OPACITY, force = true).testTag("chat_latest_glass"),
            shape = shape,
            color = if (hasGlassBackdrop(force = true)) Color.Transparent else tokens.background,
            contentColor = tokens.foreground,
            border = androidx.compose.foundation.BorderStroke(
                if (isGenerating) 2.dp else 1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (isGenerating) 0.8f else 0.3f)
            ),
            // A shadow inside this translucent plate would darken its 50% tint.
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom_icon), modifier = Modifier.size(18.dp))
            }
        }
    }
}
