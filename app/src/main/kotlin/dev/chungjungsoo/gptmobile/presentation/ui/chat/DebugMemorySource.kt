package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Display-only data resolved from recalled IDs; never persisted in chat metadata. */
data class DebugMemorySource(val label: String, val value: String)

/** Highlight exact shared phrases, not guessed paraphrases or isolated common words. */
private val memoryWord = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")

internal fun memoryHighlightRanges(text: String, values: List<String>): List<IntRange> {
    val responseWords = memoryWord.findAll(text).toList()
    val byWord = responseWords.indices.groupBy { responseWords[it].value.lowercase() }
    val ranges = mutableListOf<IntRange>()
    for (value in values.distinct()) {
        val words = memoryWord.findAll(value).map { it.value.lowercase() }.toList()
        for (start in words.indices) {
            for (responseStart in byWord[words[start]].orEmpty()) {
                var count = 0
                while (start + count < words.size &&
                    responseStart + count < responseWords.size &&
                    words[start + count] == responseWords[responseStart + count].value.lowercase()
                ) {
                    count++
                }
                if (count < 2 && words.size != 1) continue
                val range = responseWords[responseStart].range.first..responseWords[responseStart + count - 1].range.last
                if (range.last - range.first >= 3) ranges += range
            }
        }
    }
    val merged = mutableListOf<IntRange>()
    for (range in ranges.sortedBy { it.first }) {
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}
