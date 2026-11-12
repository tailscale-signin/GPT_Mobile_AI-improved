package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only reads may be replayed. A lost write response must be reconciled by its existing receipt. */
internal object GitHubReadRetry {
    /** One replayable HTTP read; nested or concurrent reads keep distinct recovery scopes. */
    internal class Scope : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Scope>

        var attempt: Int = 0
            private set

        fun nextAttempt() {
            attempt++
        }
    }

    class HttpFailure(val status: Int, val retryAfter: String?) : IOException("GitHub HTTP $status: temporarily unavailable; read retries exhausted or deferred.")

    fun transientStatus(status: Int): Boolean = status in setOf(500, 502, 503, 504)

    fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long? = value?.trim()?.let {
        it.toLongOrNull()?.takeIf { seconds -> seconds >= 0 }?.let { seconds -> seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
            ?: runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
    }

    suspend fun <T> run(readOnly: Boolean = true, request: suspend () -> T): T {
        val accounting = currentCoroutineContext()[GitHubDispatchAccounting]
        if (!readOnly || accounting == null) return retry(readOnly, request)
        val scope = Scope()
        var recovered = false
        var completed = false
        try {
            val result = withContext(scope) {
                retry(readOnly, request, scope::nextAttempt) { recovered = true }
            }
            completed = true
            return result
        } finally {
            accounting.completeReadRetry(scope, recovered && completed)
        }
    }

    private suspend fun <T> retry(readOnly: Boolean, request: suspend () -> T, onAttempt: () -> Unit = {}, onRecovered: () -> Unit = {}): T {
        var retries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                onAttempt()
                val result = request()
                currentCoroutineContext().ensureActive()
                if (retries > 0) onRecovered()
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                if (!readOnly || retries >= 2 || failure is HttpFailure && !transientStatus(failure.status) ||
                    networkFailureKind(failure) in setOf(NetworkFailureKind.TLS, NetworkFailureKind.CANCELLED)
                ) {
                    throw failure
                }
                val serverDelay = (failure as? HttpFailure)?.let { retryAfterMillis(it.retryAfter) } ?: 0L
                // Leave long server cooldowns to the task's durable waiting state; never retry early.
                if (serverDelay > 30_000L) throw failure
                delay(maxOf(1000L shl retries, serverDelay))
                retries++
            }
        }
    }
}
