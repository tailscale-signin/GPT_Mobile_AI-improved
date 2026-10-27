package dev.chungjungsoo.gptmobile.data.agent

/**
 * Guards continuation attempts against empty, repeated, or non-advancing visible text.
 * Comparison ignores whitespace differences but never discards the previously saved answer.
 */
internal object CombinedContinuationProgress {
    enum class Result { ADVANCED, EMPTY, REPEATED, NO_NEW_SUFFIX }

    fun assess(previous: String, candidate: String): Result {
        val old = normalize(previous)
        val next = normalize(candidate)
        if (next.isEmpty()) return Result.EMPTY
        if (next == old || old.contains(next)) return Result.REPEATED
        if (next.startsWith(old) && next.length > old.length) return Result.ADVANCED
        val overlap = (minOf(old.length, next.length) downTo 1)
            .firstOrNull { old.takeLast(it) == next.take(it) } ?: 0
        return if (overlap == next.length) Result.NO_NEW_SUFFIX else Result.ADVANCED
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}
