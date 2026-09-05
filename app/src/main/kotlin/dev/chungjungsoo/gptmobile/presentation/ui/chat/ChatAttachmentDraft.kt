package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Immutable
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment

@Immutable
data class ChatAttachmentDraft(
    val attachment: ChatAttachment,
    val isUploading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)
