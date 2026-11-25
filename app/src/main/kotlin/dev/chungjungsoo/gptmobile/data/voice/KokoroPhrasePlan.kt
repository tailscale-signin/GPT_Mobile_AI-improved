package dev.chungjungsoo.gptmobile.data.voice

internal class KokoroGroupTooLong : IllegalArgumentException("KOKORO_GROUP_TOO_LONG")

/** Word targets are soft. Native byte bounds and source-group boundaries are hard. */
internal class KokoroPhrasePlan(private val phrases: List<KokoroPhrase>) {
    private var index = 0
    private var start = 0
    private var words = emptyList<MatchResult>()

    fun next(wordLimit: Int, finishSlack: Int = 3): KokoroPhrase? {
        val phrase = phrases.getOrNull(index) ?: return null
        if (start == 0) {
            words = word.findAll(phrase.speech).toList()
            require(words.isNotEmpty() && (phrase.groups.isEmpty() || phrase.groups.size == words.size))
        }
        val first = words[start].range.first
        val target = (start + wordLimit.coerceIn(4, 24)).coerceAtMost(words.size)
        var maximum = start
        while (maximum < words.size && words[maximum].range.last - first < 128) maximum++
        fun permits(end: Int) = end == words.size || phrase.groups.isEmpty() || phrase.groups[end - 1] != phrase.groups[end]
        val terminal = maximum == words.size && (words.size <= target || words.size - start <= minOf(24, wordLimit + finishSlack) && phrase.speech.last() in ".!?")
        val end = if (terminal) {
            words.size
        } else {
            val candidates = minOf(target, maximum) downTo start + 1
            candidates.firstOrNull { permits(it) && it - start >= 3 && words[it - 1].value.last() in ",;:" }
                ?: candidates.firstOrNull { permits(it) && words[it - 1].value !in weak } ?: candidates.firstOrNull(::permits)
                ?: (target + 1..maximum).firstOrNull(::permits) ?: throw KokoroGroupTooLong()
        }
        val result = KokoroPhrase(
            phrase.speech.substring(first, words[end - 1].range.last + 1),
            if (phrase.wordRanges.isEmpty()) emptyList() else phrase.wordRanges.subList(start, end),
            if (phrase.groups.isEmpty()) emptyList() else phrase.groups.subList(start, end)
        )
        start = end
        if (end == words.size) {
            index++
            start = 0
        }
        return result
    }

    private companion object {
        val word = Regex("\\S+")
        val weak = setOf("a", "an", "the", "of", "for", "in", "at", "to", "on", "and", "or", "with", "by")
    }
}

/** Replan only an unheard input rejected by the native tokenizer. Each retry strictly shrinks it. */
internal fun KokoroPhrase.splitAtGroup(): Pair<KokoroPhrase, KokoroPhrase>? {
    val words = Regex("\\S+").findAll(speech).toList()
    val cut = (1 until words.size).filter { groups.isEmpty() || groups[it - 1] != groups[it] }
        .minByOrNull { kotlin.math.abs(words.size - 2 * it) } ?: return null
    fun slice(from: Int, to: Int) = KokoroPhrase(
        speech.substring(words[from].range.first, words[to - 1].range.last + 1),
        if (wordRanges.isEmpty()) emptyList() else wordRanges.subList(from, to),
        if (groups.isEmpty()) emptyList() else groups.subList(from, to)
    )
    return slice(0, cut) to slice(cut, words.size)
}
