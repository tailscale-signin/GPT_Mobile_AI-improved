package dev.chungjungsoo.gptmobile.domain.model

/**
 * Message queue item representing a pending AI request
 */
data class QueueItem(
    val id: String,
    val conversationId: String,
    val messageContent: String,
    val modelId: String,
    val priority: QueuePriority = QueuePriority.NORMAL,
    val status: QueueStatus = QueueStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val completionTokenCount: Int? = null
) {
    /**
     * Get display status for UI
     */
    fun getStatusDisplayName(): String {
        return when (status) {
            QueueStatus.PENDING -> "Queued"
            QueueStatus.PROCESSING -> "Processing..."
            QueueStatus.COMPLETED -> "Completed"
            QueueStatus.ERROR -> "Failed"
            QueueStatus.CANCELLED -> "Cancelled"
        }
    }

    /**
     * Check if item can be retried
     */
    fun canRetry(): Boolean {
        return status == QueueStatus.ERROR && retryCount < MAX_RETRIES
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}

/**
 * Priority levels for queue items
 */
enum class QueuePriority(val weight: Int) {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    URGENT(4)
}

/**
 * Status of a queue item
 */
enum class QueueStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    ERROR,
    CANCELLED
}

/**
 * Queue statistics for display
 */
data class QueueStats(
    val total: Int = 0,
    val pending: Int = 0,
    val processing: Int = 0,
    val completed: Int = 0,
    val error: Int = 0,
    val avgProcessingTimeMs: Long = 0
) {
    fun getProgressPercentage(): Double {
        if (total == 0) return 0.0
        return ((completed.toDouble() / total.toDouble()) * 100).coerceIn(0.0, 100.0)
    }
}
