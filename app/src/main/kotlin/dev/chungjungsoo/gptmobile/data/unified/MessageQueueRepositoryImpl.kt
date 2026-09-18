package dev.chungjungsoo.gptmobile.data.unified

import dev.chungjungsoo.gptmobile.domain.unified.AIService
import dev.chungjungsoo.gptmobile.domain.unified.MessageQueueRepository
import dev.chungjungsoo.gptmobile.domain.unified.MessageQueueStatus
import dev.chungjungsoo.gptmobile.domain.unified.QueueConfig
import dev.chungjungsoo.gptmobile.domain.unified.QueuedMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class MessageQueueRepositoryImpl @Inject constructor(
    private val aiService: AIService
) : MessageQueueRepository {

    private val config = QueueConfig()
    private val _queue = MutableStateFlow<List<QueuedMessage>>(emptyList())
    override val queueState: StateFlow<List<QueuedMessage>> = _queue.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isProcessing = false

    override suspend fun enqueue(message: QueuedMessage): String {
        mutex.withLock {
            val currentList = _queue.value.toMutableList()
            if (currentList.size >= config.maxQueueSize) {
                // Remove oldest completed or lowest priority if full
                val indexToRemove = currentList.indexOfFirst { it.status is MessageQueueStatus.Completed || it.status is MessageQueueStatus.Cancelled }
                if (indexToRemove != -1) {
                    currentList.removeAt(indexToRemove)
                }
            }
            currentList.add(message)
            _queue.value = currentList.sortedWith(
                compareByDescending<QueuedMessage> { it.priority }
                    .thenBy { it.createdAt }
            )
        }
        triggerProcessing()
        return message.id
    }

    override suspend fun dequeue(): QueuedMessage? = mutex.withLock {
        val next = _queue.value.firstOrNull { it.status is MessageQueueStatus.Pending }
        if (next != null) {
            _queue.update { list ->
                list.map { if (it.id == next.id) it.copy(status = MessageQueueStatus.Processing) else it }
            }
        }
        next
    }

    override suspend fun processNext(): Boolean {
        val nextMessage = dequeue() ?: return false
        return try {
            val result = aiService.executeMessage(nextMessage)
            mutex.withLock {
                _queue.update { list ->
                    list.map { msg ->
                        if (msg.id == nextMessage.id) {
                            if (result.isSuccess) {
                                val responseText = result.getOrNull().orEmpty()
                                msg.copy(
                                    status = MessageQueueStatus.Completed(responseText),
                                    response = responseText
                                )
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                                val nextRetry = msg.retryCount + 1
                                if (nextRetry < msg.maxRetries) {
                                    msg.copy(
                                        status = MessageQueueStatus.Pending,
                                        retryCount = nextRetry,
                                        error = err
                                    )
                                } else {
                                    msg.copy(
                                        status = MessageQueueStatus.Error(err, nextRetry),
                                        error = err
                                    )
                                }
                            }
                        } else {
                            msg
                        }
                    }
                }
            }
            result.isSuccess
        } catch (e: Exception) {
            mutex.withLock {
                _queue.update { list ->
                    list.map { msg ->
                        if (msg.id == nextMessage.id) {
                            msg.copy(
                                status = MessageQueueStatus.Error(e.message ?: "Failed", msg.retryCount + 1),
                                error = e.message
                            )
                        } else {
                            msg
                        }
                    }
                }
            }
            false
        }
    }

    override suspend fun removeMessage(id: String): Boolean = mutex.withLock {
        val prevSize = _queue.value.size
        _queue.update { list -> list.filterNot { it.id == id } }
        _queue.value.size < prevSize
    }

    override suspend fun clearCompleted(): Int = mutex.withLock {
        val completedCount = _queue.value.count { it.status is MessageQueueStatus.Completed || it.status is MessageQueueStatus.Cancelled }
        _queue.update { list -> list.filterNot { it.status is MessageQueueStatus.Completed || it.status is MessageQueueStatus.Cancelled } }
        completedCount
    }

    override suspend fun setPriority(id: String, priority: Int): Boolean = mutex.withLock {
        var found = false
        _queue.update { list ->
            list.map {
                if (it.id == id) {
                    found = true
                    it.copy(priority = priority.coerceIn(1, 5))
                } else {
                    it
                }
            }.sortedWith(compareByDescending<QueuedMessage> { it.priority }.thenBy { it.createdAt })
        }
        found
    }

    override suspend fun retryMessage(id: String): Boolean = mutex.withLock {
        var retried = false
        _queue.update { list ->
            list.map {
                if (it.id == id) {
                    retried = true
                    it.copy(
                        status = MessageQueueStatus.Pending,
                        retryCount = 0,
                        error = null
                    )
                } else {
                    it
                }
            }
        }
        if (retried) triggerProcessing()
        retried
    }

    override suspend fun cancelMessage(id: String): Boolean = mutex.withLock {
        var cancelled = false
        _queue.update { list ->
            list.map {
                if (it.id == id && it.status is MessageQueueStatus.Pending) {
                    cancelled = true
                    it.copy(status = MessageQueueStatus.Cancelled)
                } else {
                    it
                }
            }
        }
        cancelled
    }

    private fun triggerProcessing() {
        scope.launch {
            mutex.withLock {
                if (isProcessing) return@launch
                isProcessing = true
            }
            try {
                while (true) {
                    val hasMore = processNext()
                    if (!hasMore) {
                        val hasPending = mutex.withLock {
                            _queue.value.any { it.status is MessageQueueStatus.Pending }
                        }
                        if (!hasPending) break
                        delay(config.retryDelayMs)
                    }
                }
            } finally {
                mutex.withLock {
                    isProcessing = false
                }
            }
        }
    }
}
