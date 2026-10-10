package dev.chungjungsoo.gptmobile.data.benchmark

/**
 * Mobile reference lexical tokenizer v1: Unicode letter/digit runs are split every four
 * code points; each non-whitespace punctuation/symbol is one token. This is a shared
 * comparison unit, not a model/native tokenizer or an estimate of provider billing.
 * Offsets allow exclusion of the first delivered chunk from the decode denominator.
 */
internal object ReferenceTextTokenizer {
    const val VERSION = "reference-lexical4-v1"
    fun starts(text: String): List<Int> {
        val starts = mutableListOf<Int>()
        var index = 0
        var runLength = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            when {
                Character.isWhitespace(point) -> runLength = 0
                Character.isLetterOrDigit(point) -> {
                    if (runLength % 4 == 0) starts += index
                    runLength++
                }
                else -> {
                    starts += index
                    runLength = 0
                }
            }
            index += Character.charCount(point)
        }
        return starts
    }
}
