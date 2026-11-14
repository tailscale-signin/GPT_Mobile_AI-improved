package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel

/** Reopening a voice-origin conversation retains its voice presentation without changing routing. */
@Composable
internal fun VoiceOrTextChatScreen(
    chatViewModel: ChatViewModel,
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    onReviewEvidenceAccess: () -> Unit,
    onProfileSettings: (String) -> Unit,
    onOpenConversation: (Int, Boolean) -> Unit,
    bottomInteractionContent: (@Composable () -> Unit)?,
    presentation: VoiceRoomPresentationViewModel = hiltViewModel()
) {
    val rooms by presentation.rooms.collectAsStateWithLifecycle()
    val room by chatViewModel.chatRoom.collectAsStateWithLifecycle()
    if (rooms == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (VoiceRoomIdentity.persistent(room.id, room.createdAt, room.isTemporary) in rooms.orEmpty()) {
        VoiceChatScreen(
            onEnd = onBackAction,
            model = chatViewModel,
            initialChatView = true,
            onNavigateToLocalModels = onNavigateToLocalModels,
            onReviewEvidenceAccess = onReviewEvidenceAccess,
            onProfileSettings = onProfileSettings,
            onOpenConversation = onOpenConversation,
            bottomInteractionContent = bottomInteractionContent
        )
    } else {
        ChatScreen(
            chatViewModel = chatViewModel,
            onBackAction = onBackAction,
            onNavigateToLocalModels = onNavigateToLocalModels,
            onReviewEvidenceAccess = onReviewEvidenceAccess,
            onProfileSettings = onProfileSettings,
            onOpenConversation = onOpenConversation,
            bottomInteractionContent = bottomInteractionContent
        )
    }
}
