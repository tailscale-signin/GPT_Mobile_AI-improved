package dev.melo.gptmobile.improved.presentation.ui.chat

import android.net.Uri

data class ChatAttachmentDraft(
    val sourceFilePath: String,
    val preparedFilePath: String? = null,
    val sourceUri: Uri? = null,
    val status: Status = Status.Ready,
    val notice: String? = null,
    val errorMessage: String? = null
) {
    enum class Status {
        Ready,
        Preparing,
        Failed
    }
}
