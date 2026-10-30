package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Only reads may be replayed. A lost write response must be reconciled by its existing receipt. */
internal object GitHubReadRetry {
    class HttpFailure(val status: Int, val retryAfter: String?) : IOException("GitHub HTTP $status: temporarily unavailable; read retries exhausted or deferred.")

    fun transientStatus(status: Int): Boolean = status in setOf(500, 502, 503, 504)

    fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long? = value?.trim()?.let {
        it.toLongOrNull()?.takeIf { seconds -> seconds >= 0 }?.let { seconds -> seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
            ?: runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
    }

    suspend fun <T> run(readOnly: Boolean = true, request: suspend () -> T): T {
        var retries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                if (!readOnly || failure is SSLException || retries >= 2 || failure is HttpFailure && !transientStatus(failure.status)) throw failure
                val serverDelay = (failure as? HttpFailure)?.let { retryAfterMillis(it.retryAfter) } ?: 0L
                // Leave long server cooldowns to the task's durable waiting state; never retry early.
                if (serverDelay > 30_000L) throw failure
                delay(maxOf(1000L shl retries, serverDelay))
                retries++
            }
        }
    }
}
