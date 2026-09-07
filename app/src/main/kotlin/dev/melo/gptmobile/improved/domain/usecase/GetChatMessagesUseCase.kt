package dev.melo.gptmobile.improved.domain.usecase

import dev.melo.gptmobile.improved.data.model.Message
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetChatMessagesUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(chatRoomId: String): Flow<List<Message>> {
        return chatRepository.getMessages(chatRoomId)
    }
}
