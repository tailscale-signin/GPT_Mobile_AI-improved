package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Old saved routes open their original conversation without merging profiles or nesting headers. */
@HiltViewModel
class MultiChatWorkspaceViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    val chatId: Int = checkNotNull(savedStateHandle["rootChatId"])
}

@Composable
internal fun MultiChatWorkspaceScreen(
    onBack: () -> Unit,
    onOpenConversation: (Int, Boolean) -> Unit,
    onReviewEvidenceAccess: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    viewModel: MultiChatWorkspaceViewModel = hiltViewModel()
) {
    androidx.compose.runtime.LaunchedEffect(viewModel.chatId) {
        onOpenConversation(viewModel.chatId, false)
    }
}
