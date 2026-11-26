package dev.chungjungsoo.gptmobile.data.voice

/** Maps display characters to an indivisible spoken term for this reply only. */
internal class VoicePhraseBoundaries(private val text: String, streaming: Boolean = false) {
    private val terms = IntArray(text.length) { it }
    private val contextualStarts = mutableSetOf<Int>()
    private val unfinished: Int

    init {
        val words = word.findAll(text).toList()
        var pending = text.length
        var i = 0
        while (i < words.size) {
            val first = words[i]
            var last = i
            val next = words.getOrNull(i + 1)
            val value = first.value
            val title = title.matches(value)
            val abbreviated = abbreviation.matches(value)
            val numeric = number.matches(value)
            // A following partial unit is still undecided until its last character arrives.
            if (title || abbreviated) {
                // Initials can form a chain such as "J. R. Tolkien". A unit's
                // final period can arrive after the preceding letter in a new delta.
                while (last + 1 < words.size && abbreviation.matches(words[last + 1].value)) last++
                val continuation = words.getOrNull(last + 1)
                if (continuation != null && (nameStart(continuation.value) || abbreviated && number.matches(continuation.value))) {
                    last++
                } else if (streaming && continuation == null) {
                    pending = minOf(pending, first.range.first)
                }
            } else if (numeric && next != null && isUnit(next.value)) {
                last++
            } else if (streaming && numeric && (next == null || next.range.last == text.lastIndex && couldBeUnit(next.value))) {
                pending = minOf(pending, first.range.first)
            }
            if (value.equals("lead", true)) {
                if (next != null && metal.containsMatchIn(next.value)) {
                    last++
                } else if (streaming && (next == null || next.range.last == text.lastIndex)) {
                    pending = minOf(pending, first.range.first)
                }
            }
            if (value.equals("read", true)) {
                val tail = words.subList(i + 1, minOf(words.size, i + 5))
                val simpleCue = tail.indexOfFirst { token -> readingCue.containsMatchIn(token.value) }
                val lastTime = (1 until tail.size).firstOrNull { position ->
                    tail[position - 1].value.equals("last", ignoreCase = true) && period.matches(tail[position].value.trimEnd('.', ',', '!', '?'))
                }
                val cue = listOfNotNull(simpleCue.takeIf { it >= 0 }, lastTime).minOrNull() ?: -1
                val stop = tail.indexOfFirst { token -> token.value.last() in ".!?;:" }
                if (cue >= 0 && (stop < 0 || cue <= stop)) {
                    last = i + cue + 1
                } else if (streaming && tail.size < 4 && stop < 0 && value.last() !in ".!?") {
                    pending = minOf(pending, first.range.first)
                }
            }
            val start = first.range.first
            if (last > i) contextualStarts += start
            for (index in start..words[last].range.last) terms[index] = start
            i = last + 1
        }
        unfinished = pending
    }

    fun groupAt(offset: Int): Int = terms.getOrElse(offset) { offset }

    fun isContextual(offset: Int): Boolean = groupAt(offset) in contextualStarts

    /** End is an exclusive character offset including the original separator. */
    fun permits(end: Int): Boolean {
        if (end > unfinished) return false
        var left = end - 1
        var right = end
        while (left >= 0 && text[left].isWhitespace()) left--
        while (right < text.length && text[right].isWhitespace()) right++
        return left < 0 || right == text.length || terms[left] != terms[right]
    }

    companion object {
        const val PROTOCOL = "spoken-groups-v1"
        private val word = Regex("\\S+")
        private val title = Regex("""(?i)(?:dr|mr|mrs|ms|prof|sr|jr)\.""")
        // Keep examples and dotted initials with their following word for Kokoro's tokenizer.
        private val abbreviation = Regex("""(?:[A-Za-z]\.){2,}|[A-Z]\.|(?i:no|fig|vs|approx)\.""")
        private const val CLOSERS = "\"'”’»)]}"
        fun endsSentence(text: String, lastContent: Int): Boolean {
            var end = lastContent
            while (end >= 0 && text[end] in CLOSERS) end--
            return end >= 0 && text[end] in ".!?…。！？"
        }
        private val number = Regex("""[+−-]?(?:\d+(?:[.,:/-]\d+)*|\.\d+)(?:[eE][+-]?\d+)?""")
        private val units = setOf(
            "ms", "s", "min", "h", "hz", "khz", "mhz", "ghz", "b", "kb", "mb", "gb", "tb", "kg", "g", "mg", "µg",
            "m", "cm", "km", "mm", "%", "°c", "°f", "milliseconds", "seconds", "minutes", "hours", "percent",
            "mb/s", "kb/s", "gb/s", "b/s", "m/s", "km/h", "frames/s", "tokens/s", "meters", "metres", "kilometers", "kilometres", "degrees", "bytes", "frames", "tokens"
        )
        private val metal = Regex("""(?i)^(?:pipe|paint|poisoning|metal|battery|shielding)\b""")
        private val period = Regex("""(?i)(night|week|month|year)""")
        private val readingCue = Regex("""(?i)^(?:yesterday|earlier|previously|ago|every|daily|often|usually|tomorrow)\b""")
        private fun nameStart(value: String) = value.trimStart('“', '"', '(').firstOrNull()?.isLetter() == true
        private fun unitKey(value: String) = value.trimEnd('.', ',', ';', ':', '!', '?', ')').lowercase()
        private fun isUnit(value: String): Boolean {
            val key = unitKey(value)
            return key in units || key.isNotEmpty() && (key + "s") in units
        }
        private fun couldBeUnit(value: String): Boolean = units.any { it.startsWith(unitKey(value)) }
    }
}
