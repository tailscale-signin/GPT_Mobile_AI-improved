package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy
import java.io.IOException

internal class EngineeringApiException(val status: Int, val retryAtEpochMs: Long?, val category: String = "GITHUB_HTTP_ERROR") : IOException("GitHub API unavailable (HTTP $status; $category)")

internal fun engineeringApiFailure(status: Int, retryAfter: String?, resetSeconds: String?, remaining: String?, sso: String?, message: String, now: Long): EngineeringApiException {
    val category = dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.classify(status, message, remaining, retryAfter, sso)
    val deadline = engineeringRetryDeadline(status, retryAfter, resetSeconds, remaining, now)
        ?: if (dev.chungjungsoo.gptmobile.data.github.GitHubFailurePolicy.isRateLimited(category)) ProviderRetryPolicy.deadlineMillis(now, 60_000) else null
    return EngineeringApiException(status, deadline, category)
}

/** Normal quota headers accompany non-quota errors too; they are not a retry instruction. */
internal fun engineeringRetryDeadline(status: Int, retryAfter: String?, resetSeconds: String?, remaining: String?, now: Long): Long? {
    ProviderRetryPolicy.retryAfterMillis(retryAfter.orEmpty(), now)?.let {
        return ProviderRetryPolicy.deadlineMillis(now, it)
    }
    if (status != 429 && !(status == 403 && remaining?.trim() == "0")) return null
    val seconds = resetSeconds?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val reset = seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    return reset.coerceAtLeast(now)
}
