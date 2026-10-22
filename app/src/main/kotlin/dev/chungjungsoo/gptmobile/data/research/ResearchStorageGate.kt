package dev.chungjungsoo.gptmobile.data.research

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Concurrent task I/O; only deletion/GC needs exclusive access. No disk I/O holds the state mutex. */
internal class ResearchStorageGate {
    private val state = Mutex()
    private var readers = 0
    private var writer = false
    private var waitingWriters = 0
    private var changed = CompletableDeferred<Unit>()

    private fun signal() {
        changed.complete(Unit)
        changed = CompletableDeferred()
    }

    suspend fun <T> withReadLock(block: suspend () -> T): T {
        while (true) {
            val wait = state.withLock {
                if (!writer && waitingWriters == 0) {
                    readers++
                    null
                } else {
                    changed
                }
            }
            if (wait == null) break
            wait.await()
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                state.withLock {
                    readers--
                    signal()
                }
            }
        }
    }

    suspend fun <T> withLock(block: suspend () -> T): T {
        var admitted = false
        state.withLock { waitingWriters++ }
        try {
            while (true) {
                val wait = state.withLock {
                    if (!writer && readers == 0) {
                        writer = true
                        waitingWriters--
                        admitted = true
                        null
                    } else {
                        changed
                    }
                }
                if (wait == null) break
                wait.await()
            }
            return block()
        } finally {
            withContext(NonCancellable) {
                state.withLock {
                    if (admitted) writer = false else waitingWriters--
                    signal()
                }
            }
        }
    }
}
