package dev.chungjungsoo.gptmobile.data.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object ProviderRetryPolicy {
    private val resetDurationPart = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)")

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

    fun resetDelayMillis(value: String, now: Long): Long? {
        val raw = value.trim()
        val epoch = raw.toLongOrNull()
        val deadline = when {
            epoch != null && epoch >= 1_000_000_000_000L -> epoch
            epoch != null && epoch >= 1_000_000_000L -> epoch * 1000
            else -> runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
        } ?: return relativeResetMillis(raw)
        return remainingMillis(deadline, now).takeIf { it > 0 }
    }

    private fun relativeResetMillis(value: String): Long? {
        if (value.length !in 2..96) return null
        var end = 0
        var millis = 0.0
        for (part in resetDurationPart.findAll(value)) {
            if (part.range.first != end) return null
            end = part.range.last + 1
            val unit = when (part.groupValues[2]) {
                "ms" -> 1L
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                else -> 86_400_000L
            }
            millis += (part.groupValues[1].toDoubleOrNull() ?: return null) * unit
        }
        return if (end == value.length && millis > 0) kotlin.math.ceil(millis).toLong() else null
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
