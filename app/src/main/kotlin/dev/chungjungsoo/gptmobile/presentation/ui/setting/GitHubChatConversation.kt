package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
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
    key(chatId, context.ref, context.commitSha) {
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
                ChatScreen(
                    chatViewModel = model,
                    onBackAction = onBack,
                    onOpenConversation = { id, _ -> onOpenConversation(id) },
                    onReviewEvidenceAccess = onReviewEvidenceAccess,
                    onNavigateToLocalModels = onNavigateToLocalModels,
                    showTopBar = false
                )
            }
        }
    }
}

internal fun GitHubProgrammerState.progressDescription(): String {
    val activeIds = runs.filter { it.status !in GITHUB_TERMINAL_RUN_STATUSES }.mapTo(hashSetOf()) { it.runId }
    val event = events.lastOrNull { it.runId in activeIds && it.status in setOf("RUNNING", "PENDING", "STARTED") }
    return event?.modelToolName?.ifBlank { event.toolName }?.substringBefore("__")?.replace('_', ' ')
        ?.let { "Working · $it" } ?: activityLabel()
}

/** Outside every tab's scroll container and above the tabs, including with the keyboard open. */
@Composable
internal fun GitHubChatProgress(workspace: GitHubWorkspaceState, state: GitHubProgrammerState) {
    val active = workspace.busy || state.submitting || state.pending.any { !it.paused } || state.runs.any { it.status !in GITHUB_TERMINAL_RUN_STATUSES }
    Column(Modifier.fillMaxWidth().testTag("github_chat_progress").padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (workspace.busy) "Loading GitHub workspace…" else state.progressDescription(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (active) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
