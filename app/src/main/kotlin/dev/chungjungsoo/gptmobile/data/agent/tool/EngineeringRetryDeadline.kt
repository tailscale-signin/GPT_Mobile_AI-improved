package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy

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
