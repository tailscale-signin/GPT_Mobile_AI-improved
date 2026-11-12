package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.common.AppGlassSurface
import dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalGitHubApprovalPending
import dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalGitHubNativeDecisions

internal data class GitHubConversationApprovalState(
    val chatId: Int,
    val taskId: String? = null,
    val awaitingApproval: Boolean = false,
    val available: Boolean = false,
    val message: String? = null
)

/** Installed only by the ordinary chat route; GitHub workspaces keep their existing host. */
@Composable
internal fun GitHubConversationApprovalHost(
    chatId: Int,
    availableConnectionUids: Set<String>,
    requestedTaskId: String?,
    onApprovalPendingChanged: (Boolean) -> Unit,
    onReviewEvidenceAccess: () -> Unit,
    model: GitHubReviewViewModel = hiltViewModel(key = "conversation_approval"),
    content: @Composable (@Composable () -> Unit) -> Unit
) {
    val observed by model.conversationApproval.collectAsStateWithLifecycle()
    val receipts by model.decisionReceipts.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.chatId == chatId }
    val pending = state?.awaitingApproval == true
    LaunchedEffect(chatId, availableConnectionUids, requestedTaskId) {
        model.observeConversationApproval(chatId, availableConnectionUids, requestedTaskId)
    }
    DisposableEffect(chatId, pending) {
        onApprovalPendingChanged(pending)
        onDispose { onApprovalPendingChanged(false) }
    }
    CompositionLocalProvider(
        LocalGitHubApprovalPending provides pending,
        LocalGitHubNativeDecisions provides receipts.filter { it.chatId == chatId }
    ) {
        content { GitHubConversationApprovalContent(model, state, onReviewEvidenceAccess) }
    }
}

@Composable
internal fun GitHubConversationApprovalContent(
    model: GitHubReviewViewModel,
    state: GitHubConversationApprovalState?,
    onReviewEvidenceAccess: () -> Unit
) {
    val pending by model.pendingEngineeringTask.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    if (state == null) return
    if (state.awaitingApproval && state.available && state.taskId == pending) {
        GitHubChatApprovalCard(model) {}
    } else if (state.awaitingApproval || state.message != null) {
        AppGlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("github_saved_approval")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Saved GitHub approval", style = MaterialTheme.typography.titleSmall)
                Text(state.message ?: "Review the exact patch saved by this conversation.", style = MaterialTheme.typography.bodySmall)
                if (state.awaitingApproval && state.available && state.taskId != null) {
                    TextButton(enabled = !busy, onClick = { model.reopenConversationApproval(state.taskId) }) { Text("Review approval") }
                } else if (state.awaitingApproval && !state.available) {
                    TextButton(onClick = onReviewEvidenceAccess) { Text("Review GitHub connection") }
                }
            }
        }
    }
}
