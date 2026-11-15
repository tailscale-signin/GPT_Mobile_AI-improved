package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy
import io.ktor.http.Headers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tracks the latest GitHub rate-limit headers and exposes a compact snapshot to
 * diagnostics and agent tools. Scheduling stays cancellation-aware in callers.
 */
class GitHubRateLimitManager {
    data class Snapshot(
        val resource: String? = null,
        val limit: Long? = null,
        val remaining: Long? = null,
        val used: Long? = null,
        val resetEpochSeconds: Long? = null,
        val retryAfterSeconds: Long? = null,
        val observedAtMillis: Long = System.currentTimeMillis()
    )

    @Volatile
    private var latest: Snapshot = Snapshot()
    private val resources = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    private val secondaryUntil = java.util.concurrent.atomic.AtomicLong()
    fun cooldown(seconds: Long, nowMillis: Long = System.currentTimeMillis()) {
        secondaryUntil.updateAndGet { maxOf(it, ProviderRetryPolicy.deadlineMillis(nowMillis, secondsToMillis(seconds))) }
    }

    fun record(headers: Headers, status: Int = 200, nowMillis: Long = System.currentTimeMillis()) {
        val state = Snapshot(
            resource = headers["X-RateLimit-Resource"],
            limit = headers["X-RateLimit-Limit"]?.toLongOrNull(),
            remaining = headers["X-RateLimit-Remaining"]?.toLongOrNull(),
            used = headers["X-RateLimit-Used"]?.toLongOrNull(),
            resetEpochSeconds = headers["X-RateLimit-Reset"]?.toLongOrNull(),
            retryAfterSeconds = ProviderRetryPolicy.retryAfterMillis(headers["Retry-After"].orEmpty(), nowMillis)?.let(::ceilingSeconds),
            observedAtMillis = nowMillis
        )
        if (state.retryAfterSeconds != null) {
            cooldown(state.retryAfterSeconds, nowMillis)
        } else if (status == 429) {
            cooldown(60, nowMillis)
        }
        latest = state
        if (state.remaining != null || state.retryAfterSeconds != null) resources[state.resource ?: "core"] = state
    }

    fun requireAvailable(resource: String = "core", nowEpochSeconds: Long = System.currentTimeMillis() / 1000) {
        val nowMillis = secondsToMillis(nowEpochSeconds)
        val secondaryDeadline = secondaryUntil.get()
        val secondary = ceilingSeconds(ProviderRetryPolicy.remainingMillis(secondaryDeadline, nowMillis))
        val state = resources[resource]
        val retryAfterDelay = state?.let { retryAfterRemaining(it, nowEpochSeconds) } ?: 0
        val resetDelay = if (state?.remaining == 0L) ((state.resetEpochSeconds ?: nowEpochSeconds) - nowEpochSeconds).coerceAtLeast(0) else 0
        val delay = maxOf(retryAfterDelay, resetDelay)
        val resourceDeadline = ProviderRetryPolicy.deadlineMillis(nowMillis, secondsToMillis(delay))
        // The current request must satisfy both gates. A shorter shared
        // cooldown must not advertise a retry before this bucket resets.
        // Rounded second delays can tie while their absolute deadlines differ.
        if (secondary > 0L && secondaryDeadline >= resourceDeadline) {
            throw GitHubCooldownActive(
                GitHubCooldownActive.resource(resource),
                GitHubCooldownActive.Basis.SECONDARY_DEADLINE,
                secondsToMillis(secondary),
                secondaryDeadline
            )
        }
        if (delay != 0L) {
            val delayMillis = secondsToMillis(delay)
            throw GitHubCooldownActive(
                GitHubCooldownActive.resource(resource),
                when {
                    retryAfterDelay > 0 && resetDelay > 0 -> GitHubCooldownActive.Basis.RESOURCE_RESET_AND_RETRY_AFTER
                    retryAfterDelay > 0 -> GitHubCooldownActive.Basis.RESOURCE_RETRY_AFTER
                    else -> GitHubCooldownActive.Basis.RESOURCE_RESET
                },
                delayMillis,
                resourceDeadline,
                state?.observedAtMillis,
                state?.remaining
            )
        }
    }

    fun snapshot(): Snapshot = latest

    fun shouldBackOff(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val state = latest
        if (secondaryUntil.get() > secondsToMillis(nowEpochSeconds)) return true
        if (retryAfterRemaining(state, nowEpochSeconds) > 0L) return true
        return state.remaining == 0L && (state.resetEpochSeconds ?: 0L) > nowEpochSeconds
    }

    fun retryDelayMillis(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Long {
        val state = latest
        val secondary = ProviderRetryPolicy.remainingMillis(secondaryUntil.get(), secondsToMillis(nowEpochSeconds))
        val retryAfter = secondsToMillis(retryAfterRemaining(state, nowEpochSeconds))
        val reset = if (state.remaining == 0L) {
            ProviderRetryPolicy.remainingMillis(secondsToMillis(state.resetEpochSeconds ?: nowEpochSeconds), secondsToMillis(nowEpochSeconds))
        } else {
            0L
        }
        return maxOf(secondary, retryAfter, reset)
    }

    private fun retryAfterRemaining(state: Snapshot, nowEpochSeconds: Long): Long {
        val retryAfter = state.retryAfterSeconds ?: return 0
        val deadline = ProviderRetryPolicy.deadlineMillis(state.observedAtMillis, secondsToMillis(retryAfter))
        return ceilingSeconds(ProviderRetryPolicy.remainingMillis(deadline, secondsToMillis(nowEpochSeconds)))
    }

    private fun secondsToMillis(seconds: Long): Long = seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
    private fun ceilingSeconds(millis: Long): Long = millis / 1000 + if (millis % 1000 == 0L) 0 else 1

    fun toJson(): JsonObject = buildJsonObject {
        val state = latest
        state.resource?.let { put("resource", it) }
        state.limit?.let { put("limit", it) }
        state.remaining?.let { put("remaining", it) }
        state.used?.let { put("used", it) }
        state.resetEpochSeconds?.let { put("reset_epoch_seconds", it) }
        state.retryAfterSeconds?.let { put("retry_after_seconds", it) }
        put("backoff_recommended", JsonPrimitive(shouldBackOff()))
        put("secondary_until_ms", secondaryUntil.get())
        put(
            "resources",
            JsonObject(
                resources.mapValues { (_, value) ->
                    buildJsonObject {
                        value.remaining?.let { put("remaining", it) }
                        value.resetEpochSeconds?.let { put("reset_epoch_seconds", it) }
                    }
                }
            )
        )
    }
}

/** Minimal cached admission facts for callers without a complete resource snapshot. */
internal class GitHubRateLimitAdmissionException(val code: String, val retryAfterMillis: Long) : IllegalStateException("$code: GitHub is cooling down. Wait for the recorded deadline before resuming saved work.")
