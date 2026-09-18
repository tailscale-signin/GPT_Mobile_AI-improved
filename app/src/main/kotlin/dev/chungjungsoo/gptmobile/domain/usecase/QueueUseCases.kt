package dev.chungjungsoo.gptmobile.domain.usecase

import dev.chungjungsoo.gptmobile.domain.model.QueueItem
import dev.chungjungsoo.gptmobile.domain.model.QueuePriority
import dev.chungjungsoo.gptmobile.domain.model.QueueStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for adding a message to the queue
 */
@Singleton
class AddToQueueUseCase @Inject constructor(
    private val queueRepository: QueueRepository
) {
    suspend fun execute(
        conversationId: String,
        messageContent: String,
        modelId: String,
        priority: QueuePriority = QueuePriority.NORMAL
    ): Result<QueueItem> {
        return queueRepository.addQueueItem(
            conversationId = conversationId,
            messageContent = messageContent,
            modelId = modelId,
            priority = priority
        )
    }
}

/**
 * Use case for getting all queue items
 */
@Singleton
class GetQueueItemsUseCase @Inject constructor(
    private val queueRepository: QueueRepository
) {
    suspend fun execute(): Result<List<QueueItem>> {
        return queueRepository.getQueueItems()
    }
}

/**
 * Use case for getting queue statistics
 */
@Singleton
class GetQueueStatsUseCase @Inject constructor(
    private val queueRepository: QueueRepository
) {
    suspend fun execute(): Result<dev.chungjungsoo.gptmobile.domain.model.QueueStats> {
        return queueRepository.getQueueStats()
    }
}

/**
 * Use case for processing the next item in the queue
 */
@Singleton
class ProcessNextQueueItemUseCase @Inject constructor(
    private val queueRepository: QueueRepository,
    private val aiService: AIService
) {
    suspend fun execute(): Result<QueueItem> {
        // Get next pending item (ordered by priority and creation time)
        val items = queueRepository.getQueueItems().getOrNull() ?: return Result.failure(Exception("No queue"))
        
        val nextItem = items.find { it.status == QueueStatus.PENDING }
            ?: return Result.success(QueueItem(
                id = "cancelled",
                conversationId = "",
                messageContent = "No pending items",
                modelId = ""
            ))

        // Update status to processing
        val updatedItem = nextItem.copy(status = QueueStatus.PROCESSING)
        queueRepository.updateQueueItem(updatedItem).getOrNull()

        // Process the AI request
        return aiService.processRequest(
            conversationId = nextItem.conversationId,
            messageContent = nextItem.messageContent,
            modelId = nextItem.modelId
        ).map { result ->
            if (result.isSuccess) {
                updatedItem.copy(status = QueueStatus.COMPLETED)
            } else {
                updatedItem.copy(
                    status = QueueStatus.ERROR,
                    errorMessage = result.exceptionOrNull()?.message,
                    retryCount = nextItem.retryCount + 1
                )
            }
        }
    }
}

/**
 * Use case for retrying a failed queue item
 */
@Singleton
class RetryQueueItemUseCase @Inject constructor(
    private val queueRepository: QueueRepository,
    private val aiService: AIService
) {
    suspend fun execute(queueItemId: String): Result<QueueItem> {
        val items = queueRepository.getQueueItems().getOrNull() ?: return Result.failure(Exception("No queue"))
        
        val item = items.find { it.id == queueItemId }
            ?: return Result.failure(Exception("Item not found"))

        if (!item.canRetry()) {
            return Result.failure(Exception("Max retries exceeded"))
        }

        // Update status to processing
        val updatedItem = item.copy(status = QueueStatus.PROCESSING)
        queueRepository.updateQueueItem(updatedItem).getOrNull()

        // Process the AI request
        return aiService.processRequest(
            conversationId = item.conversationId,
            messageContent = item.messageContent,
            modelId = item.modelId
        ).map { result ->
            if (result.isSuccess) {
                updatedItem.copy(status = QueueStatus.COMPLETED)
            } else {
                updatedItem.copy(
                    status = QueueStatus.ERROR,
                    errorMessage = result.exceptionOrNull()?.message,
                    retryCount = item.retryCount + 1
                )
            }
        }
    }
}

/**
 * Use case for cancelling a queue item
 */
@Singleton
class CancelQueueItemUseCase @Inject constructor(
    private val queueRepository: QueueRepository
) {
    suspend fun execute(queueItemId: String): Result<Unit> {
        return queueRepository.cancelQueueItem(queueItemId)
    }
}

/**
 * Use case for clearing the entire queue
 */
@Singleton
class ClearQueueUseCase @Inject constructor(
    private val queueRepository: QueueRepository
) {
    suspend fun execute(): Result<Unit> {
        return queueRepository.clearQueue()
    }
}
