package dev.chungjungsoo.gptmobile.data.unified

import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.domain.unified.AIService
import dev.chungjungsoo.gptmobile.domain.unified.QueuedMessage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull

@Singleton
class AIServiceImpl @Inject constructor(
    private val chatRepository: ChatRepository,
    private val platformV2Dao: PlatformV2Dao
) : AIService {

    override suspend fun executeMessage(message: QueuedMessage): Result<String> {
        return runCatching {
            val platform = if (!message.platformUid.isNullOrBlank()) {
                platformV2Dao.getByUid(message.platformUid)
            } else {
                platformV2Dao.getAll().firstOrNull { it.enabled }
            } ?: throw IllegalStateException("No active platform configured for message execution.")

            val targetPlatform = if (message.modelId.isNotBlank()) {
                platform.copy(model = message.modelId)
            } else {
                platform
            }

            val userMsg = MessageV2(
                id = 0,
                chatId = message.chatId ?: 0,
                content = message.content,
                createdAt = message.createdAt / 1000
            )

            val fullResponse = StringBuilder()
            val runId = UUID.randomUUID().toString()

            val flow = chatRepository.completeChat(
                userMessages = listOf(userMsg),
                assistantMessages = emptyList(),
                platform = targetPlatform,
                runId = runId,
                chatToolConfig = null
            )

            flow.collect { state ->
                when (state) {
                    is ApiState.Success -> fullResponse.append(state.data)
                    is ApiState.Error -> throw RuntimeException(state.message)
                    else -> Unit
                }
            }

            fullResponse.toString()
        }
    }

    override fun executeMessageStream(message: QueuedMessage): Flow<String> = flow {
        val platform = if (!message.platformUid.isNullOrBlank()) {
            platformV2Dao.getByUid(message.platformUid)
        } else {
            platformV2Dao.getAll().firstOrNull { it.enabled }
        } ?: throw IllegalStateException("No active platform configured for message execution.")

        val targetPlatform = if (message.modelId.isNotBlank()) {
            platform.copy(model = message.modelId)
        } else {
            platform
        }

        val userMsg = MessageV2(
            id = 0,
            chatId = message.chatId ?: 0,
            content = message.content,
            createdAt = message.createdAt / 1000
        )

        val runId = UUID.randomUUID().toString()
        chatRepository.completeChat(
            userMessages = listOf(userMsg),
            assistantMessages = emptyList(),
            platform = targetPlatform,
            runId = runId,
            chatToolConfig = null
        ).collect { state ->
            when (state) {
                is ApiState.Success -> emit(state.data)
                is ApiState.Error -> throw RuntimeException(state.message)
                else -> Unit
            }
        }
    }
}
