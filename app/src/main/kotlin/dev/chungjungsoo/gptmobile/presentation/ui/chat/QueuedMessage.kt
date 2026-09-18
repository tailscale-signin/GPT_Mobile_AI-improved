package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Immutable
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import java.util.UUID

@Immutable
data class QuotedMessageDraft(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val text: String
)

@Immutable
data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachments: List<ChatAttachmentDraft> = emptyList(),
    val quote: QuotedMessageDraft? = null
)
