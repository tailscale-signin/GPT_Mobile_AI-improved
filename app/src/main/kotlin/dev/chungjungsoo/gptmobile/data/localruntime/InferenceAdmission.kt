package dev.chungjungsoo.gptmobile.data.localruntime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Benchmarks exclude other inference; ordinary chats may still use multiple providers. */
internal object InferenceAdmission {
    private val mutex = Mutex()
    private var readers = 0
    private var writer = false
    private var waitingWriters = 0
    private var changed = CompletableDeferred<Unit>()
    private class Owner : AbstractCoroutineContextElement(Owner) {
        companion object Key : CoroutineContext.Key<Owner>
    }

    private fun signal() {
        changed.complete(Unit)
        changed = CompletableDeferred()
    }

    suspend fun <T> shared(block: suspend () -> T): T {
        if (coroutineContext[Owner] != null) return block()
        var acquired = false
        try {
            withTimeout(60_000) {
                while (true) {
                    val wait = mutex.withLock {
                        if (!writer && waitingWriters == 0) {
                            readers++
                            acquired = true
                            null
                        } else {
                            changed
                        }
                    } ?: break
                    wait.await()
                }
            }
            return withContext(Owner()) { block() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (acquired) readers--
                    signal()
                }
            }
        }
    }

    suspend fun <T> tryShared(block: suspend () -> T): T? {
        if (coroutineContext[Owner] != null) return block()
        if (!mutex.tryLock()) return null
        val acquired = try {
            if (writer || waitingWriters > 0) {
                false
            } else {
                readers++
                true
            }
        } finally {
            mutex.unlock()
        }
        if (!acquired) return null
        try {
            return withContext(Owner()) { block() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    readers--
                    signal()
                }
            }
        }
    }

    suspend fun <T> benchmark(block: suspend () -> T): T {
        check(coroutineContext[Owner] == null) { "Cannot start a benchmark inside active inference." }
        mutex.withLock {
            waitingWriters++
            signal()
        }
        var acquired = false
        try {
            withTimeout(60_000) {
                while (true) {
                    val wait = mutex.withLock {
                        if (!writer && readers == 0) {
                            writer = true
                            acquired = true
                            waitingWriters--
                            null
                        } else {
                            changed
                        }
                    } ?: break
                    wait.await()
                }
            }
            return withContext(Owner()) { block() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (acquired) writer = false else waitingWriters--
                    signal()
                }
            }
        }
    }

    fun <T> sharedFlow(upstream: Flow<T>): Flow<T> = channelFlow { shared { upstream.collect { send(it) } } }
}
