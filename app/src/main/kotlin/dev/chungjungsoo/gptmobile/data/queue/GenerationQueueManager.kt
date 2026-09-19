package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatAttachmentDraft
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Status of a message inside the generation queue.
 */
enum class QueuedMessageStatus {
    PENDING,
    GENERATING,
    COMPLETED,
    FAILED
}

/**
 * Processing state of the generation queue.
 */
enum class QueueProcessingState {
    IDLE,
    GENERATING,
    PAUSED,
    STOPPED,
    ERROR
}

/**
 * Draft representing a quoted message in the conversation.
 */
data class QuotedMessageDraft(
    val id: Int,
    val content: String,
    val senderName: String? = null
)

/**
 * Entity representing a queued message awaiting AI generation.
 */
data class GenerationQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachments: List<ChatAttachmentDraft> = emptyList(),
    val quotedMessage: QuotedMessageDraft? = null,
    val status: QueuedMessageStatus = QueuedMessageStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val responseReference: String? = null
)

/**
 * Service managing the generation message queue.
 * Limits queue size to [MAX_QUEUE_CAPACITY] (50 messages) and provides
 * FIFO enqueue, dequeue, cancellation, and reactive state flows.
 */
@Singleton
class GenerationQueueManager @Inject constructor() {

    companion object {
        const val MAX_QUEUE_CAPACITY = 50
    }

    private val _queue = MutableStateFlow<List<GenerationQueueItem>>(emptyList())
    val queue: StateFlow<List<GenerationQueueItem>> = _queue.asStateFlow()

    private val _processingState = MutableStateFlow(QueueProcessingState.IDLE)
    val processingState: StateFlow<QueueProcessingState> = _processingState.asStateFlow()

    private val _currentlyProcessingItem = MutableStateFlow<GenerationQueueItem?>(null)
    val currentlyProcessingItem: StateFlow<GenerationQueueItem?> = _currentlyProcessingItem.asStateFlow()

    private val _queueCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _queueCount.asStateFlow()
    val queueCount: StateFlow<Int> = _queueCount.asStateFlow()

    private fun updateCount() {
        _queueCount.value = _queue.value.count { it.status == QueuedMessageStatus.PENDING }
    }

    /**
     * Enqueue a new message from user input.
     * Drops the oldest message if the queue reaches [MAX_QUEUE_CAPACITY].
     *
     * @return The enqueued [GenerationQueueItem], and whether an older item was dropped.
     */
    @Synchronized
    fun enqueue(
        text: String,
        attachments: List<ChatAttachmentDraft> = emptyList(),
        quotedMessage: QuotedMessageDraft? = null
    ): Pair<GenerationQueueItem, Boolean> {
        var droppedOldest = false
        val newItem = GenerationQueueItem(
            text = text,
            attachments = attachments,
            quotedMessage = quotedMessage,
            status = QueuedMessageStatus.PENDING
        )

        _queue.update { current ->
            val updated = current.toMutableList()
            if (updated.size >= MAX_QUEUE_CAPACITY) {
                updated.removeFirstOrNull()
                droppedOldest = true
            }
            updated.add(newItem)
            updated
        }
        updateCount()
        return newItem to droppedOldest
    }

    /**
     * Dequeue the next pending message for processing.
     * Sets the item status to [QueuedMessageStatus.GENERATING] and marks it as active.
     */
    @Synchronized
    fun dequeue(): GenerationQueueItem? {
        var nextItem: GenerationQueueItem? = null
        _queue.update { current ->
            val index = current.indexOfFirst { it.status == QueuedMessageStatus.PENDING }
            if (index != -1) {
                val item = current[index].copy(status = QueuedMessageStatus.GENERATING)
                nextItem = item
                current.toMutableList().apply {
                    removeAt(index)
                }
            } else {
                current
            }
        }
        _currentlyProcessingItem.value = nextItem
        if (nextItem != null) {
            _processingState.value = QueueProcessingState.GENERATING
        } else {
            _processingState.value = QueueProcessingState.IDLE
        }
        updateCount()
        return nextItem
    }

    /**
     * Remove a specific message from the queue by its ID.
     */
    @Synchronized
    fun remove(id: String): Boolean {
        var removed = false
        _queue.update { current ->
            val filtered = current.filterNot { it.id == id }
            removed = filtered.size != current.size
            filtered
        }
        updateCount()
        return removed
    }

    /**
     * Complete the currently generating message.
     */
    @Synchronized
    fun markCurrentCompleted(responseReference: String? = null) {
        _currentlyProcessingItem.update { current ->
            current?.copy(
                status = QueuedMessageStatus.COMPLETED,
                responseReference = responseReference
            )
        }
        _currentlyProcessingItem.value = null
        if (_queue.value.none { it.status == QueuedMessageStatus.PENDING }) {
            _processingState.value = QueueProcessingState.IDLE
        }
        updateCount()
    }

    /**
     * Mark the currently generating message as failed.
     */
    @Synchronized
    fun markCurrentFailed() {
        _currentlyProcessingItem.update { current ->
            current?.copy(status = QueuedMessageStatus.FAILED)
        }
        _currentlyProcessingItem.value = null
        _processingState.value = QueueProcessingState.ERROR
        updateCount()
    }

    /**
     * Cancel the entire queue and in-progress generation.
     * Transitions state to [QueueProcessingState.STOPPED].
     */
    @Synchronized
    fun cancelAll() {
        _queue.value = emptyList()
        _currentlyProcessingItem.value = null
        _processingState.value = QueueProcessingState.STOPPED
        updateCount()
    }

    /**
     * Reset queue state to idle.
     */
    @Synchronized
    fun resetToIdle() {
        _queue.value = emptyList()
        _currentlyProcessingItem.value = null
        _processingState.value = QueueProcessingState.IDLE
        updateCount()
    }

    /**
     * Set processing state explicitly.
     */
    fun setProcessingState(state: QueueProcessingState) {
        _processingState.value = state
    }
}
