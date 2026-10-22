package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Shared across profiles and tool rounds. An IP's other apps can still consume its quota. */
class FreeAiRequestLimiter(
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private class Bucket {
        val permit = Semaphore(1)
        val requests = ArrayDeque<Long>()
        var retryAt = 0L
    }

    private val buckets = java.util.concurrent.ConcurrentHashMap<Pair<FreeAiProvider, String>, Bucket>()
    private fun bucket(provider: FreeAiProvider, model: String = ""): Bucket = buckets.computeIfAbsent(provider to if (provider == FreeAiProvider.OVHCLOUD) model else "") { Bucket() }

    suspend fun <T> withRequest(provider: FreeAiProvider, request: suspend () -> T): T = withRequest(provider, "", request)

    suspend fun <T> withRequest(provider: FreeAiProvider, model: String, request: suspend () -> T): T =
        bucket(provider, model).permit.withPermit {
            // Short local spacing is scheduling, not an exhausted provider quota.
            // Keep the permit while waiting so parallel profiles cannot race the next slot.
            delay(spacingDelayMillis(provider))
            reserve(provider, model)
            request()
        }

    @Synchronized
    private fun spacingDelayMillis(provider: FreeAiProvider): Long {
        val previous = bucket(provider).requests.peekLast() ?: return 0L
        return if (provider == FreeAiProvider.LLM7) (previous + 1000 - nowMillis()).coerceIn(0, 1000) else 0L
    }

    @Synchronized
    internal fun reserve(provider: FreeAiProvider, model: String = "") {
        val bucket = bucket(provider, model)
        val now = nowMillis()
        while (bucket.requests.isNotEmpty() && bucket.requests.first <= now - HOUR_MS) bucket.requests.removeFirst()
        val minuteRequests = bucket.requests.filter { it > now - MINUTE_MS }
        var retryAt = bucket.retryAt
        if (provider == FreeAiProvider.LLM7 && bucket.requests.isNotEmpty()) {
            retryAt = maxOf(retryAt, bucket.requests.last + 1000)
        }
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            retryAt = maxOf(retryAt, minuteRequests.first() + MINUTE_MS)
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            retryAt = maxOf(retryAt, bucket.requests.first + HOUR_MS)
        }
        if (retryAt > now) throw FreeAiRateLimitException(provider, (retryAt - now + 999) / 1000)
        bucket.requests.addLast(now)
    }

    @Synchronized
    fun defer(provider: FreeAiProvider, retryAfter: String?, model: String = ""): Long {
        val now = nowMillis()
        val seconds = retryAfter?.toLongOrNull()?.coerceAtLeast(1)
        val date = runCatching { ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val wait = (seconds?.coerceAtMost(86400)?.times(1000) ?: date?.minus(now) ?: MINUTE_MS).coerceIn(1000, 86400000)
        val bucket = bucket(provider, model)
        bucket.retryAt = maxOf(bucket.retryAt, now + wait)
        return (bucket.retryAt - now + 999) / 1000
    }

    /** Return the known provider/local quota cooldown without reserving a request. */
    @Synchronized
    fun cooldownUntilMillis(provider: FreeAiProvider, model: String = ""): Long? {
        val now = nowMillis()
        val bucket = bucket(provider, model)
        while (bucket.requests.isNotEmpty() && bucket.requests.first <= now - HOUR_MS) bucket.requests.removeFirst()
        val minuteRequests = bucket.requests.filter { it > now - MINUTE_MS }
        var retryAt = bucket.retryAt
        if (provider == FreeAiProvider.LLM7 && bucket.requests.isNotEmpty()) {
            retryAt = maxOf(retryAt, bucket.requests.last + 1000)
        }
        if (provider.requestsPerMinute > 0 && minuteRequests.size >= provider.requestsPerMinute) {
            retryAt = maxOf(retryAt, minuteRequests.first() + MINUTE_MS)
        }
        if (provider.requestsPerHour > 0 && bucket.requests.size >= provider.requestsPerHour) {
            retryAt = maxOf(retryAt, bucket.requests.first + HOUR_MS)
        }
        return retryAt.takeIf { it > now }
    }

    companion object {
        val shared = FreeAiRequestLimiter()
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}

class FreeAiRateLimitException(provider: FreeAiProvider, retrySeconds: Long) :
    IllegalStateException(
        "${provider.displayName} is rate limited. Retry no earlier than ${java.time.Instant.ofEpochMilli(System.currentTimeMillis() + retrySeconds * 1000)} (in $retrySeconds seconds), or select another Free provider. This cooldown does not guarantee the provider's allowance has reset."
    )
