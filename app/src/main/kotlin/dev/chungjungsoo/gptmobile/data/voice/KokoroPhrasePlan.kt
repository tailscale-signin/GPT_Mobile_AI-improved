package dev.chungjungsoo.gptmobile.data.voice

/** Replans only unspoken projected words; pronunciation and source mappings stay unchanged. */
internal class KokoroPhrasePlan(phrases: List<KokoroPhrase>) {
    private val pending = ArrayDeque(phrases)

    fun next(wordLimit: Int): KokoroPhrase? {
        val phrase = pending.removeFirstOrNull() ?: return null
        val words = Regex("\\S+").findAll(phrase.speech).toList()
        val limit = wordLimit.coerceIn(4, 24)
        if (words.size <= limit) return phrase
        require(phrase.wordRanges.isEmpty() || phrase.wordRanges.size == words.size)
        val boundary = words[limit].range.first
        pending.addFirst(KokoroPhrase(phrase.speech.substring(boundary), phrase.wordRanges.drop(limit)))
        return KokoroPhrase(phrase.speech.substring(0, boundary).trimEnd(), phrase.wordRanges.take(limit))
    }
}
