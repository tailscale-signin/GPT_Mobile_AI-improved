package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy
import io.ktor.http.Headers
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed observations from this HTTP response, independent of later rate-manager state. */
internal object GitHubRateLimitResponseEvidence {
    fun fromResponse(status: Int, code: String, headers: Headers, observedAtMillis: Long): JsonObject? {
        if (status !in setOf(403, 429) || !GitHubFailurePolicy.isRateLimited(code)) return null
        val observedAt = observedAtMillis.takeIf { it >= 0 }
        val remaining = headers["X-RateLimit-Remaining"]?.toLongOrNull()?.takeIf { it in 0L..16_000_000L }
        val retryAfterDelay = observedAt?.let { now ->
            headers["Retry-After"]?.let { ProviderRetryPolicy.retryAfterMillis(it, now) }
        }?.takeIf { it > 0 }
        val resetDelay = if (observedAt != null && remaining == 0L) {
            headers["X-RateLimit-Reset"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
                val deadline = if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
                ProviderRetryPolicy.remainingMillis(deadline, observedAt)
            }?.takeIf { it > 0 }
        } else {
            null
        }
        val delay = listOfNotNull(resetDelay, retryAfterDelay).maxOrNull()
        return buildJsonObject {
            put("code", code)
            put("http_status", status)
            headers["X-RateLimit-Resource"]?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("github_cooldown_resource", GitHubCooldownActive.resource(it.lowercase(Locale.ROOT)).name)
            }
            observedAt?.let { put("github_cooldown_observed_at_ms", it) }
            remaining?.let { put("github_cooldown_remaining_requests", it) }
            if (delay != null && observedAt != null) {
                put(
                    "github_cooldown_basis",
                    when {
                        resetDelay != null && retryAfterDelay != null -> GitHubCooldownActive.Basis.RESOURCE_RESET_AND_RETRY_AFTER
                        resetDelay != null -> GitHubCooldownActive.Basis.RESOURCE_RESET
                        else -> GitHubCooldownActive.Basis.RESOURCE_RETRY_AFTER
                    }.name
                )
                put("github_cooldown_delay_ms", delay)
                put("github_cooldown_deadline_ms", ProviderRetryPolicy.deadlineMillis(observedAt, delay))
                delay.takeIf { it <= 86_400_000L }?.let { put("retry_after_ms", it) }
            }
        }
    }
}
