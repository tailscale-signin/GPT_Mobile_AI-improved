package dev.chungjungsoo.gptmobile.data.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object ProviderRetryPolicy {
    fun retryAfterMillis(value: String, now: Long): Long? {
        value.trim().toLongOrNull()?.let { seconds ->
            return seconds.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000)
        }
        return runCatching { (ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }.getOrNull()
    }
}
