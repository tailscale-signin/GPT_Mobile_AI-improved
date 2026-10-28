package dev.chungjungsoo.gptmobile.data.research.feature

import java.text.Normalizer

/** Normalization is reversible to exact source ranges; no fuzzy/paraphrase acceptance. */
internal class ExaminationQuoteLocator(private val source: String) {
    data class Located(val start: Int, val end: Int, val quote: String)
    private data class Indexed(val text: String, val starts: IntArray, val ends: IntArray)
    private val indexed by lazy { normalize(source) }

    fun locate(quote: String, givenStart: Int? = null): Located {
        require(quote.length in 1..2000) { "QUOTE_SIZE: quotation must contain 1..2000 UTF-16 characters" }
        if (givenStart != null && givenStart >= 0 && givenStart <= source.length - quote.length && source.regionMatches(givenStart, quote, 0, quote.length) && boundary(givenStart, givenStart + quote.length)) {
            return Located(givenStart, givenStart + quote.length, quote)
        }
        val exact = source.indexOf(quote)
        if (exact >= 0 && source.indexOf(quote, exact + 1) < 0 && boundary(exact, exact + quote.length)) return Located(exact, exact + quote.length, quote)
        val needle = normalize(quote).text.trim()
        require(needle.isNotBlank()) { "QUOTE_EMPTY: quotation contains only whitespace" }
        val matches = mutableListOf<Pair<Int, Int>>()
        var cursor = indexed.text.indexOf(needle)
        while (cursor >= 0) {
            val start = indexed.starts[cursor]
            val end = indexed.ends[cursor + needle.length - 1]
            // Do not accept a fragment from inside a composed Unicode cluster.
            val whole = normalize(source.substring(start, end)).text.trim() == needle
            if (whole && end - start <= 2000) {
                if (givenStart == start) return Located(start, end, source.substring(start, end))
                matches += start to end
                if (matches.size > 1 && givenStart == null) break
            }
            cursor = indexed.text.indexOf(needle, cursor + 1)
        }
        require(matches.size == 1) { if (matches.isEmpty()) "QUOTE_ABSENT: no exact or normalization-equivalent source passage" else "QUOTE_AMBIGUOUS: multiple source passages; provide the exact source start or a longer quotation" }
        val (start, end) = matches.single()
        return Located(start, end, source.substring(start, end))
    }

    private fun boundary(start: Int, end: Int): Boolean =
        !(start > 0 && start < source.length && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) &&
            !(end > 0 && end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate())

    private fun normalize(text: String): Indexed {
        val output = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var index = 0
        while (index < text.length) {
            val start = index
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            if (Character.isWhitespace(code) || Character.isSpaceChar(code)) {
                while (index < text.length) {
                    val next = text.codePointAt(index)
                    if (!Character.isWhitespace(next) && !Character.isSpaceChar(next)) break
                    index += Character.charCount(next)
                }
                output.append(' ')
                starts += start
                ends += index
                continue
            }
            while (index < text.length && Character.getType(text.codePointAt(index)) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) {
                index += Character.charCount(text.codePointAt(index))
            }
            val canonical = Normalizer.normalize(text.substring(start, index), Normalizer.Form.NFC)
            canonical.forEach { char ->
                output.append(
                    when (char) {
                        '‘', '’', 'ʼ' -> '\''
                        '“', '”' -> '"'
                        '‐', '‑', '–', '—' -> '-'
                        else -> char
                    }
                )
                starts += start
                ends += index
            }
        }
        return Indexed(output.toString(), starts.toIntArray(), ends.toIntArray())
    }
}
