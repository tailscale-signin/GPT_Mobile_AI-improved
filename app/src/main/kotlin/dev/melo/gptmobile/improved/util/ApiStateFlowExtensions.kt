package dev.melo.gptmobile.improved.util

import dev.melo.gptmobile.improved.data.database.entity.AssistantTimelineItem
import dev.melo.gptmobile.improved.data.database.entity.AssistantTimelineItemType
import dev.melo.gptmobile.improved.data.dto.ApiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

fun <T> MutableStateFlow<T>.updateIfChanged(function: (T) -> T) {
    while (true) {
        val prevValue = value
        val nextValue = function(prevValue)
        if (prevValue == nextValue || compareAndSet(prevValue, nextValue)) {
            return
        }
    }
}

sealed interface ApiStateFlowOutcome {
    data object Completed : ApiStateFlowOutcome
    data class Failed(val message: String) : ApiStateFlowOutcome
    data object Incomplete : ApiStateFlowOutcome
}

private const val STREAM_PUBLISH_INTERVAL_MILLIS = 33L

suspend fun Flow<ApiState>.collectApiStateUpdates(
    onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit,
    onNotice: (String, Boolean) -> Unit = { _, _ -> },
    nanoTimeProvider: () -> Long = System::nanoTime,
    publishIntervalMillis: Long = STREAM_PUBLISH_INTERVAL_MILLIS
): ApiStateFlowOutcome {
    val buffer = StreamingMessageBuffer(nanoTimeProvider, publishIntervalMillis)
    var isCompletedSuccessfully = false
    var terminalError: String? = null

    try {
        collect { chunk ->
            when (chunk) {
                is ApiState.Thinking -> {
                    buffer.appendThought(chunk.thinkingChunk)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.Success -> {
                    buffer.appendContent(chunk.textChunk)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.ToolCall -> {
                    buffer.appendTool(chunk.toolSequence)
                    buffer.publishIfDue(onUpdate)
                }

                is ApiState.Notice -> {
                    if (chunk.persistent) {
                        buffer.appendNotice(chunk.message)
                    }
                    onNotice(chunk.message, chunk.persistent)
                    if (chunk.persistent) {
                        buffer.publishNow(onUpdate)
                    }
                }

                ApiState.Done -> {
                    isCompletedSuccessfully = true
                }

                is ApiState.Error -> {
                    terminalError = chunk.message
                }

                else -> {}
            }
        }
    } finally {
        buffer.flush(onUpdate)
    }

    return when {
        terminalError != null -> ApiStateFlowOutcome.Failed(terminalError)
        isCompletedSuccessfully -> ApiStateFlowOutcome.Completed
        else -> ApiStateFlowOutcome.Incomplete
    }
}

private class StreamingMessageBuffer(
    private val nanoTimeProvider: () -> Long,
    private val publishIntervalMillis: Long
) {
    private val thoughts = StringBuilder()
    private val content = StringBuilder()
    private val timeline = mutableListOf<AssistantTimelineItem>()
    private var lastPublishedAtNanos = 0L
    private var publishedThoughtLength = 0
    private var publishedContentLength = 0
    private var timelineVersion = 0
    private var publishedTimelineVersion = 0

    fun appendThought(chunk: String) {
        if (chunk.isNotEmpty()) {
            thoughts.append(chunk)
            appendTimelineText(AssistantTimelineItemType.THINKING, chunk)
        }
    }

    fun appendContent(chunk: String) {
        if (chunk.isNotEmpty()) {
            content.append(chunk)
            appendTimelineText(AssistantTimelineItemType.TEXT_CHUNK, chunk)
        }
    }

    fun appendTool(toolSequence: Int) {
        timeline += AssistantTimelineItem(
            id = UUID.randomUUID().toString(),
            type = AssistantTimelineItemType.TOOL_CALL,
            content = "tool:$toolSequence"
        )
        timelineVersion += 1
    }

    fun appendNotice(message: String) {
        if (message.isBlank()) return
        timeline += AssistantTimelineItem(
            id = UUID.randomUUID().toString(),
            type = AssistantTimelineItemType.TEXT_CHUNK,
            content = message
        )
        timelineVersion += 1
    }

    suspend fun publishIfDue(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit
    ) {
        if (!hasPendingChanges()) return

        val now = nanoTimeProvider()
        if (lastPublishedAtNanos == 0L ||
            now - lastPublishedAtNanos >= publishIntervalMillis * 1_000_000
        ) {
            publish(onUpdate, now)
        }
    }

    suspend fun flush(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit
    ) {
        if (!hasPendingChanges()) return
        publish(onUpdate, nanoTimeProvider())
    }

    suspend fun publishNow(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit
    ) {
        if (!hasPendingChanges()) return
        publish(onUpdate, nanoTimeProvider())
    }

    private suspend fun publish(
        onUpdate: suspend (content: String, thoughts: String, timeline: List<AssistantTimelineItem>) -> Unit,
        publishedAtNanos: Long
    ) {
        onUpdate(content.toString(), thoughts.toString(), timeline.toList())
        publishedContentLength = content.length
        publishedThoughtLength = thoughts.length
        publishedTimelineVersion = timelineVersion
        lastPublishedAtNanos = publishedAtNanos
    }

    private fun appendTimelineText(type: AssistantTimelineItemType, chunk: String) {
        val last = timeline.lastOrNull()
        if (last?.type == type && last.toolName == null) {
            timeline[timeline.lastIndex] = last.copy(content = last.content + chunk)
        } else {
            timeline += AssistantTimelineItem(
                id = UUID.randomUUID().toString(),
                type = type,
                content = chunk
            )
        }
        timelineVersion += 1
    }

    private fun hasPendingChanges(): Boolean = content.length != publishedContentLength ||
        thoughts.length != publishedThoughtLength ||
        timelineVersion != publishedTimelineVersion
}
