package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** One optional helper lane, separate from (and never a replacement for) native ownership. */
internal object LocalAssistQueue {
    private val lane = Mutex()

    suspend fun <T> run(waitMillis: Long, work: suspend () -> T): T? {
        // Only waiting is timed here. The producer owns its preparation/generation deadline.
        var acquired = false
        return try {
            val ready = withTimeoutOrNull(waitMillis.coerceAtLeast(1)) {
                lane.lock()
                acquired = true
                true
            } == true
            if (ready) work() else null
        } finally {
            if (acquired) lane.unlock()
        }
    }
}
