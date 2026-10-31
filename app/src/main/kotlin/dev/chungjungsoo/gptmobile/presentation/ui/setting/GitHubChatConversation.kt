package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen

private val GITHUB_TERMINAL_RUN_STATUSES = setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)

/** A real Chat destination supplies its own SavedStateHandle and lifecycle to the standard chat renderer. */
@Composable
internal fun GitHubChatConversation(
    chatId: Int,
    profileUid: String,
    context: GitHubConversationContext,
    onBack: () -> Unit,
    onOpenConversation: (Int) -> Unit,
    onReviewEvidenceAccess: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    registerExit: (((() -> Unit) -> Unit)?) -> Unit
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
                androidx.compose.runtime.CompositionLocalProvider(dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalChatProgressInHeader provides true) {
                    ChatScreen(
                        chatViewModel = model,
                        onBackAction = onBack,
                        onOpenConversation = { id, _ -> onOpenConversation(id) },
                        onReviewEvidenceAccess = onReviewEvidenceAccess,
                        onNavigateToLocalModels = onNavigateToLocalModels,
                        showTopBar = false,
                        allowResearchPanels = false
                    )
                }
            }
        }
    }
}

internal fun GitHubProgrammerState.progressDescription(): String {
    if (approvals.isNotEmpty()) return "Needs your approval"
    if (runs.any { it.status.startsWith("WAITING_") }) return "Waiting"
    val activeIds = runs.filter { it.status !in GITHUB_TERMINAL_RUN_STATUSES }.mapTo(hashSetOf()) { it.runId }
    if (activeIds.isEmpty()) return activityLabel()
    return dev.chungjungsoo.gptmobile.presentation.ui.chat.generationActivityLabel(
        events.filter { it.runId in activeIds },
        messages.lastOrNull { it.currentRunId in activeIds }?.effectiveTimeline().orEmpty()
    )
}

/** Pinned under the repository title on every workspace tab. */
@Composable
internal fun GitHubChatProgress(workspace: GitHubWorkspaceState, state: GitHubProgrammerState, engineeringJob: dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob? = null) {
    val activeRuns = state.runs.filter { it.status !in GITHUB_TERMINAL_RUN_STATUSES }
    val activeIds = activeRuns.mapTo(hashSetOf()) { it.runId }
    val active = workspace.busy || state.submitting || state.pending.any { !it.paused } || activeRuns.isNotEmpty()
    val currentTask = (activeRuns.maxByOrNull { it.createdAt } ?: state.runs.maxByOrNull { it.createdAt })?.userMessageId
    val taskRuns = state.runs.filter { it.userMessageId == currentTask }
    val fallbackStart = androidx.compose.runtime.saveable.rememberSaveable(state.chatId, active) { System.currentTimeMillis() }
    val started = if (active && activeRuns.isEmpty()) fallbackStart else taskRuns.minOfOrNull { it.createdAt * 1000 }
    val completed = if (active) null else taskRuns.mapNotNull { it.completedAt }.maxOrNull()?.times(1000)
    val waiting = state.approvals.isNotEmpty() || activeRuns.any { it.status.startsWith("WAITING_") }
    val timeline = state.messages.lastOrNull { it.currentRunId in activeIds }?.effectiveTimeline().orEmpty()
    dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatGenerationProgress(
        toolEvents = state.events.filter { it.runId in activeIds },
        timeline = timeline,
        modifier = Modifier.fillMaxWidth().testTag("github_chat_progress").padding(horizontal = 16.dp, vertical = 8.dp),
        active = active,
        textSizeIncrease = 3,
        startedAtMillis = started,
        completedAtMillis = completed,
        engineeringJob = engineeringJob,
        statusOverride = when {
            workspace.busy -> "Loading GitHub workspace"
            waiting || !active -> state.progressDescription()
            else -> null
        }
    )
}
