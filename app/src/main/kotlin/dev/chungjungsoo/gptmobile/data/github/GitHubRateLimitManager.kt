package dev.chungjungsoo.gptmobile.data.github

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
    fun cooldown(seconds: Long, nowMillis: Long = System.currentTimeMillis()) { secondaryUntil.updateAndGet { maxOf(it, nowMillis + seconds.coerceIn(1, 86400) * 1000) } }

    fun record(headers: Headers, status: Int = 200) {
        val state = Snapshot(
            resource = headers["X-RateLimit-Resource"],
            limit = headers["X-RateLimit-Limit"]?.toLongOrNull(),
            remaining = headers["X-RateLimit-Remaining"]?.toLongOrNull(),
            used = headers["X-RateLimit-Used"]?.toLongOrNull(),
            resetEpochSeconds = headers["X-RateLimit-Reset"]?.toLongOrNull(),
            retryAfterSeconds = headers["Retry-After"]?.toLongOrNull()
        )
        if (state.retryAfterSeconds != null) cooldown(state.retryAfterSeconds)
        else if (status == 429) cooldown(60)
        latest = state
        if (state.remaining != null || state.retryAfterSeconds != null) resources[state.resource ?: "core"] = state
    }

    fun requireAvailable(resource: String = "core", nowEpochSeconds: Long = System.currentTimeMillis() / 1000) {
        val secondary = ((secondaryUntil.get() + 999) / 1000 - nowEpochSeconds).coerceAtLeast(0)
        check(secondary == 0L) { "GitHub secondary rate limit: retry after $secondary seconds." }
        val state = resources[resource] ?: return
        val delay = maxOf(
            retryAfterRemaining(state, nowEpochSeconds),
            if (state.remaining == 0L) ((state.resetEpochSeconds ?: nowEpochSeconds) - nowEpochSeconds).coerceAtLeast(0) else 0
        )
        check(delay == 0L) { "GitHub is cooling down for $resource. Retry after $delay seconds; use results already collected." }
    }

    fun snapshot(): Snapshot = latest

    fun shouldBackOff(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        val state = latest
        if (secondaryUntil.get() > nowEpochSeconds * 1000) return true
        if (retryAfterRemaining(state, nowEpochSeconds) > 0L) return true
        return state.remaining == 0L && (state.resetEpochSeconds ?: 0L) > nowEpochSeconds
    }

    fun retryDelayMillis(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Long {
        val state = latest
        val secondary = (secondaryUntil.get() - nowEpochSeconds * 1000).coerceAtLeast(0)
        if (secondary > 0) return secondary
        retryAfterRemaining(state, nowEpochSeconds).takeIf { it > 0 }?.let { return it * 1000 }
        if (state.remaining != 0L) return 0L
        return ((state.resetEpochSeconds ?: nowEpochSeconds) - nowEpochSeconds)
            .coerceAtLeast(0L) * 1000
    }

    private fun retryAfterRemaining(state: Snapshot, nowEpochSeconds: Long): Long {
        val retryAfter = state.retryAfterSeconds ?: return 0
        return ((state.observedAtMillis / 1000 + retryAfter) - nowEpochSeconds).coerceAtLeast(0)
    }

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
        put("resources", JsonObject(resources.mapValues { (_, value) -> buildJsonObject { value.remaining?.let { put("remaining", it) }; value.resetEpochSeconds?.let { put("reset_epoch_seconds", it) } } }))
    }
}
