package dev.chungjungsoo.gptmobile.data.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object ProviderRetryPolicy {
    fun retryAfterMillis(value: String, now: Long): Long? {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' }) {
            val seconds = trimmed.toLongOrNull() ?: Long.MAX_VALUE
            return seconds.coerceAtMost(Long.MAX_VALUE / 1000).times(1000)
        }
        return runCatching {
            val deadline = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            remainingMillis(deadline, now)
        }.getOrNull()
    }

    /** Saturate untrusted deadlines instead of overflowing into an immediate retry. */
    fun deadlineMillis(now: Long, delayMillis: Long): Long {
        val delay = delayMillis.coerceAtLeast(0)
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }

    fun remainingMillis(deadline: Long, now: Long): Long = when {
        deadline <= now -> 0
        now < 0 && deadline > Long.MAX_VALUE + now -> Long.MAX_VALUE
        else -> deadline - now
    }
}
