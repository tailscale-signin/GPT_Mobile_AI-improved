package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.ArrayDeque
import kotlinx.coroutines.channels.Channel

/** Bounded callback-to-coroutine handoff. Terminal delivery has reserved capacity. */
internal class LocalEventBuffer(private val byteLimit: Int = 1024 * 1024, private val controlLimit: Int = 64) {
    private val events = ArrayDeque<LocalRuntimeEvent>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var bytes = 0
    private var controls = 0
    private var terminal = false

    @Synchronized
    fun offer(event: LocalRuntimeEvent): Boolean {
        if (terminal) return false
        if (event is LocalRuntimeEvent.PhaseChanged && event.nativeProgress != null) {
            // Progress is replaceable metadata; it cannot evict accepted text or a terminal receipt.
            val previous = events.peekLast() as? LocalRuntimeEvent.PhaseChanged
            if (previous?.nativeProgress?.operationId == event.nativeProgress.operationId && previous.phase == event.phase) {
                events.removeLast()
                controls--
            } else if (controls >= controlLimit) {
                val iterator = events.iterator()
                var removed = false
                while (iterator.hasNext()) {
                    val queued = iterator.next()
                    if (queued is LocalRuntimeEvent.PhaseChanged && queued.nativeProgress != null) {
                        iterator.remove()
                        controls--
                        removed = true
                        break
                    }
                }
                if (!removed) return true
            }
        }
        val text = text(event)
        val size = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val isTerminal = event is LocalRuntimeEvent.Done || event is LocalRuntimeEvent.Error
        if (!isTerminal && (bytes + size > byteLimit || (text == null && controls >= controlLimit))) {
            terminal = true
            events.add(LocalRuntimeEvent.Error("Local output buffer filled. Accepted text is preserved; the turn was stopped without replay."))
            changed.trySend(Unit)
            return false
        }
        val previous = events.peekLast()
        if (text != null && previous != null && previous::class == event::class && (text(previous)?.length ?: 0) + text.length <= 8192) {
            events.removeLast()
            val combined = text(previous).orEmpty() + text
            events.add(if (event is LocalRuntimeEvent.TextDelta) LocalRuntimeEvent.TextDelta(combined) else LocalRuntimeEvent.ThinkingDelta(combined))
        } else {
            events.add(event)
            if (text == null) controls++
        }
        bytes += size
        terminal = isTerminal
        changed.trySend(Unit)
        return true
    }

    suspend fun take(): LocalRuntimeEvent? {
        while (true) {
            synchronized(this) {
                val event = events.pollFirst()
                if (event != null) {
                    val text = text(event)
                    bytes -= text?.toByteArray(Charsets.UTF_8)?.size ?: 0
                    if (text == null) controls--
                    return event
                }
                if (terminal) return null
            }
            changed.receive()
        }
    }

    private fun text(event: LocalRuntimeEvent): String? = when (event) {
        is LocalRuntimeEvent.TextDelta -> event.text
        is LocalRuntimeEvent.ThinkingDelta -> event.text
        else -> null
    }
}

internal class NativeCancellationTimeoutException : IllegalStateException("Native cancellation was not acknowledged. The inference worker must be restarted; partial output is preserved.")

/** Keep UTF-16 surrogate pairs together before each chunk is encoded for IPC. */
internal fun String.unicodeChunks(limit: Int): Sequence<String> = sequence {
    require(limit >= 2)
    var start = 0
    while (start < length) {
        var end = minOf(start + limit, length)
        if (end < length && this@unicodeChunks[end - 1].isHighSurrogate() && this@unicodeChunks[end].isLowSurrogate()) end--
        yield(substring(start, end))
        start = end
    }
}

internal class NativeResourceCleanupException(cause: Throwable) : IllegalStateException("Native resource cleanup failed; the isolated worker must restart before reuse.", cause)
