package dev.chungjungsoo.gptmobile.util

/** Larger messages cost more to lay out; preserve all text while reducing snapshot frequency. */
internal fun adaptiveStreamIntervalMillis(configured: Long, characters: Int): Long = maxOf(
    configured.coerceAtLeast(1),
    when {
        characters >= 128 * 1024 -> 150L
        characters >= 32 * 1024 -> 66L
        else -> 1L
    }
)
