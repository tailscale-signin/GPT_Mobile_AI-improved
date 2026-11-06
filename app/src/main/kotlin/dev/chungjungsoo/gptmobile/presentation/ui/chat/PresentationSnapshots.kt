package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Derived display state only: raw provider deltas must never pass through this coalescer. */
internal fun <T : Any> Flow<T>.presentationSnapshots(
    intervalMillis: Long = 40,
    streaming: (T) -> Boolean,
    useful: (T) -> Boolean
): Flow<T> = channelFlow {
    require(intervalMillis in 33..50)
    val lock = Mutex()
    var pending: T? = null
    var scheduled: Job? = null
    var firstUseful = false
    var previouslyStreaming = false
    try {
        collect { snapshot ->
            lock.withLock {
                val live = streaming(snapshot)
                if (live && !previouslyStreaming) firstUseful = false
                previouslyStreaming = live
                if (!live || !firstUseful && useful(snapshot)) {
                    scheduled?.cancel()
                    scheduled = null
                    pending = null
                    firstUseful = firstUseful || useful(snapshot)
                    send(snapshot)
                } else {
                    pending = snapshot
                    if (scheduled == null) {
                        scheduled = launch {
                            delay(intervalMillis)
                            lock.withLock {
                                pending?.let { send(it) }
                                pending = null
                                scheduled = null
                            }
                        }
                    }
                }
            }
        }
        lock.withLock {
            scheduled?.cancel()
            scheduled = null
            pending?.let { send(it) }
            pending = null
        }
    } finally {
        scheduled?.cancel()
    }
}.buffer(1)
