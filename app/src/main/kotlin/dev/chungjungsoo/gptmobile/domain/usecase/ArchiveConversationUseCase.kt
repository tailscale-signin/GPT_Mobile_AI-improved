package dev.chungjungsoo.gptmobile.domain.usecase

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import javax.inject.Inject

class ArchiveConversationUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun archiveChat(chatId: Int) {
        chatRepository.setChatArchived(chatId, isArchived = true)
    }

    suspend fun unarchiveChat(chatId: Int) {
        chatRepository.setChatArchived(chatId, isArchived = false)
    }

    suspend fun getArchivedChats(): List<ChatRoomV2> = chatRepository.fetchArchivedChatListV2()
}
