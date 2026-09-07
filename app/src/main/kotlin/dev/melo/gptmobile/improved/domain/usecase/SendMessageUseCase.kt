package dev.melo.gptmobile.improved.domain.usecase

import dev.melo.gptmobile.improved.data.model.AgentRunStatus
import dev.melo.gptmobile.improved.data.model.Attachment
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(
        chatRoomId: String,
        content: String,
        attachments: List<Attachment> = emptyList(),
        enabledTools: List<String> = emptyList(),
        onStatusUpdate: (AgentRunStatus) -> Unit = {}
    ) {
        chatRepository.sendMessage(
            chatRoomId = chatRoomId,
            content = content,
            attachments = attachments,
            enabledTools = enabledTools,
            onStatusUpdate = onStatusUpdate
        )
    }
}
