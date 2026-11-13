package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubNativeApprovalProgress
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen

/** A real Chat destination supplies its own SavedStateHandle and lifecycle to the standard chat renderer. */
@Composable
internal fun GitHubChatConversation(
    chatId: Int,
    headerHeight: Dp,
    profileUid: String,
    context: GitHubConversationContext,
    onBack: () -> Unit,
    onOpenConversation: (Int) -> Unit,
    onReviewEvidenceAccess: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    registerExit: (((() -> Unit) -> Unit)?) -> Unit,
    recoveryRequest: dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubRecoveryRequest? = null,
    onRecoveryHandled: () -> Unit = {},
    exportRequested: Boolean = false,
    onExportHandled: () -> Unit = {},
    onProfileSettings: (String) -> Unit = {},
    approvalPending: Boolean = false,
    nativeDecisions: List<dev.chungjungsoo.gptmobile.data.github.GitHubNativeDecisionReceipt> = emptyList(),
    bottomInteractionContent: (@Composable () -> Unit)? = null
) {
    key(chatId) {
        val controller = rememberNavController()
        NavHost(controller, startDestination = "conversation/$chatId/$profileUid", modifier = Modifier.fillMaxSize()) {
            composable(
                "conversation/{chatRoomId}/{enabledPlatforms}",
                arguments = listOf(
                    navArgument("chatRoomId") { type = NavType.IntType },
                    navArgument("enabledPlatforms") { type = NavType.StringType },
                    navArgument("conversationMode") { defaultValue = "STANDARD" }
                )
            ) {
                val model: dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
                val room by model.chatRoom.collectAsStateWithLifecycle()
                val grouped by model.groupedMessages.collectAsStateWithLifecycle()
                val savedRuns by model.agentRunsById.collectAsStateWithLifecycle()
                androidx.compose.runtime.DisposableEffect(model, approvalPending) {
                    model.bindGitHubApprovalPending(approvalPending)
                    onDispose { model.bindGitHubApprovalPending(false) }
                }
                androidx.compose.runtime.LaunchedEffect(model, room.id, recoveryRequest, grouped, savedRuns) {
                    val request = recoveryRequest?.takeIf { it.chatId == chatId && it.chatId == room.id } ?: return@LaunchedEffect
                    val run = savedRuns[request.runId] ?: return@LaunchedEffect
                    if (grouped.userMessages.none { it.id == run.userMessageId }) return@LaunchedEffect
                    model.bindGitHubContext(context)
                    model.recoverGitHubRun(request.runId, request.action)
                    onRecoveryHandled()
                }
                androidx.compose.runtime.LaunchedEffect(model, room.id, context) {
                    if (room.id == chatId) model.bindGitHubContext(context)
                }
                androidx.compose.runtime.DisposableEffect(model) {
                    registerExit { done ->
                        model.checkpointComposer()
                        done()
                    }
                    onDispose {
                        model.checkpointComposer()
                        registerExit(null)
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalChatProgressInHeader provides true,
                    dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalGitHubApprovalPending provides approvalPending,
                    dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalGitHubNativeDecisions provides nativeDecisions
                ) {
                    val typography = MaterialTheme.typography
                    fun larger(style: androidx.compose.ui.text.TextStyle) = style.copy(fontSize = (style.fontSize.value + 2).sp, lineHeight = (style.lineHeight.value + 2).sp)
                    MaterialTheme(
                        typography = typography.copy(
                            bodySmall = larger(typography.bodySmall), bodyMedium = larger(typography.bodyMedium), bodyLarge = larger(typography.bodyLarge),
                            titleSmall = larger(typography.titleSmall), titleMedium = larger(typography.titleMedium), titleLarge = larger(typography.titleLarge),
                            labelSmall = larger(typography.labelSmall), labelMedium = larger(typography.labelMedium), labelLarge = larger(typography.labelLarge),
                            headlineSmall = larger(typography.headlineSmall), headlineMedium = larger(typography.headlineMedium), headlineLarge = larger(typography.headlineLarge),
                            displaySmall = larger(typography.displaySmall), displayMedium = larger(typography.displayMedium), displayLarge = larger(typography.displayLarge)
                        )
                    ) {
                        ChatScreen(
                            chatViewModel = model,
                            onBackAction = onBack,
                            onOpenConversation = { id, _ -> onOpenConversation(id) },
                            onReviewEvidenceAccess = onReviewEvidenceAccess,
                            onProfileSettings = onProfileSettings,
                            onNavigateToLocalModels = onNavigateToLocalModels,
                            showTopBar = false,
                            embeddedHeaderHeight = headerHeight,
                            allowResearchPanels = false,
                            exportRequestedForChatId = chatId.takeIf { exportRequested },
                            onExportHandled = onExportHandled,
                            bottomInteractionContent = bottomInteractionContent
                        )
                    }
                }
            }
        }
    }
}

/** Pinned under the repository title on every workspace tab. */
@Composable
internal fun GitHubChatProgress(
    workspace: GitHubWorkspaceState,
    state: GitHubProgrammerState,
    engineeringJob: dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob? = null,
    nativeProgress: GitHubNativeApprovalProgress? = null
) {
    val profile = state.profiles.firstOrNull { it.uid == state.context?.mainProfileUid }
    var profileNotice by androidx.compose.runtime.remember(state.chatId, profile?.uid) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.chatId, profile?.uid) {
        if (profile != null && !dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy.enabled(profile)) {
            profileNotice = "Chat is ready. Choose a local AI for autonomous work."
            kotlinx.coroutines.delay(4000)
            profileNotice = null
        }
    }
    val progress = androidx.compose.runtime.remember(state, nativeProgress) { state.progressProjection(nativeProgress) }
    val active = progress.active || engineeringJob?.terminal == false
    if (!active && engineeringJob == null) return
    val fallbackStart = androidx.compose.runtime.saveable.rememberSaveable(state.chatId, active) { System.currentTimeMillis() }
    val trackingAttempt = progress.taskRuns.isNotEmpty() && (progress.waiting || progress.taskRuns.any { it.status !in AgentRunStatus.terminal })
    val started = if (active && !trackingAttempt) fallbackStart else progress.taskRuns.minOfOrNull { it.createdAt * 1000 }
    val completed = if (active) null else progress.taskRuns.mapNotNull { it.completedAt }.maxOrNull()?.times(1000)
    dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatGenerationProgress(
        toolEvents = progress.toolEvents,
        timeline = progress.timeline,
        modifier = Modifier.fillMaxWidth().testTag("github_chat_progress").padding(vertical = 4.dp),
        active = active,
        textSizeIncrease = 0,
        startedAtMillis = started,
        completedAtMillis = completed,
        engineeringJob = engineeringJob,
        githubPresentation = true,
        statusOverride = when {
            progress.waiting -> progress.description
            progress.generating -> null
            profileNotice != null -> profileNotice
            workspace.busy -> "Refreshing the repository"
            !active -> progress.description
            else -> null
        }
    )
}
