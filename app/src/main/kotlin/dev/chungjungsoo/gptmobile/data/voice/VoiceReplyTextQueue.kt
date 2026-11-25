package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Single-reader reply input. Final text flushes the SDK even when the feeder is already waiting. */
internal class VoiceReplyTextQueue(private val capacity: Int) {
    private val pending = ArrayDeque<String>()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var closed = false

    fun offer(text: String): Boolean = lock.withLock {
        if (closed || pending.size >= capacity) return false
        pending.addLast(text)
        changed.signal()
        true
    }

    fun next(): String? = lock.withLock {
        while (pending.isEmpty() && !closed) changed.await()
        pending.removeFirstOrNull()
    }

    fun isEmpty(): Boolean = lock.withLock { pending.isEmpty() }

    fun finish() = lock.withLock {
        closed = true
        changed.signal()
    }

    fun cancel() = lock.withLock {
        pending.clear()
        closed = true
        changed.signal()
    }
}
