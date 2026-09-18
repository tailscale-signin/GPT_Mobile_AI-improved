package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.domain.model.QueueItem
import dev.chungjungsoo.gptmobile.domain.model.QueueStats
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for queue data
 */
interface QueueRepository {
    /**
     * Get all queue items
     */
    suspend fun getQueueItems(): Result<List<QueueItem>>

    /**
     * Add a new item to the queue
     */
    suspend fun addQueueItem(
        conversationId: String,
        messageContent: String,
        modelId: String,
        priority: dev.chungjungsoo.gptmobile.domain.model.QueuePriority
    ): Result<QueueItem>

    /**
     * Update an existing queue item
     */
    suspend fun updateQueueItem(item: QueueItem): Result<Unit>

    /**
     * Cancel a queue item
     */
    suspend fun cancelQueueItem(queueItemId: String): Result<Unit>

    /**
     * Clear the entire queue
     */
    suspend fun clearQueue(): Result<Unit>

    /**
     * Get queue statistics
     */
    suspend fun getQueueStats(): Result<QueueStats>

    /**
     * Observe queue items as a flow
     */
    fun observeQueueItems(): Flow<List<QueueItem>>
}
