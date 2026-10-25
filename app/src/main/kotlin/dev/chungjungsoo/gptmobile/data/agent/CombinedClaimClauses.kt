package dev.chungjungsoo.gptmobile.data.agent

import java.text.BreakIterator
import java.util.Locale

/** Exact UTF-16 ranges for independently stated clauses; quantities never migrate between clauses. */
internal object CombinedClaimClauses {
    data class Clause(val start: Int, val end: Int, val text: String, val comparisonText: String = text)

    // A coordinated proper name is not enough: “Portugal and Spain” is one object.
    // Split only when the next named subject has its own finite verb.
    private val subject = "[A-Z][\\p{L}’'-]+(?:\\s+(?:[A-Z][\\p{L}’'-]+|da|de|do|van|von)){0,4}"
    private val verb = "(?:was|were|is|are|has|had|became|brought|built|fell|led|made|took|won|[\\p{L}]{3,}ed)"
    private val boundary = Regex(";\\s*|(?:,\\s+|(?<=\\d)\\s+)(?:and|but|whereas|while)\\s+(?=$subject\\s+$verb\\b)|,\\s+(?:which|who|opening|establishing|proving|accelerating)\\s+")

    // Do not detach a positive-looking clause from a sentence-wide denial, hedge or condition.
    private val scoped = Regex("(?i)\\b(?:not|no|never|without|cannot|may|might|could|perhaps|possibly|allegedly|reportedly|unless|only|except)\\b|n[’']t\\b")

    // A dated title in an enumeration is a reference to an event, not an assertion
    // of every predicate in the source sentence. Keep its exact range for partial
    // provenance without inventing a verb or inheriting a neighbouring date.
    private val datedMention = Regex("(?:^|[,;]\\s*(?:and\\s+)?)(?:[Tt]he\\s+)?([A-Z][\\p{L}’'-]+(?:\\s+[\\p{L}][\\p{L}’'-]+){1,7})\\s*\\(([12][0-9]{3})\\)")
    private val namedWord = Regex("\\b[A-Z][\\p{L}’'-]+\\b")

    fun datedMentions(text: String): List<Clause> {
        if (scoped.containsMatchIn(text)) return emptyList()
        return datedMention.findAll(text).mapNotNull { match ->
            val title = match.groups[1] ?: return@mapNotNull null
            if (namedWord.findAll(title.value).count() < 2) return@mapNotNull null
            val start = title.range.first
            val end = match.range.last + 1
            Clause(start, end, text.substring(start, end))
        }.toList()
    }

    fun spans(text: String): List<Clause> = buildList {
        val sentences = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        var start = sentences.first()
        var end = sentences.next()
        while (end != BreakIterator.DONE) {
            var clauseStart = start
            val sentence = text.substring(start, end)
            val coordinated = if (scoped.containsMatchIn(sentence)) emptyList() else datedCoordinations(sentence)
            if (coordinated.isNotEmpty()) {
                addAll(coordinated.map { it.copy(start = start + it.start, end = start + it.end) })
                start = end
                end = sentences.next()
                continue
            }
            val separators = if (scoped.containsMatchIn(sentence)) emptySequence() else boundary.findAll(sentence)
            separators.forEach { separator ->
                val clauseEnd = start + separator.range.first
                addTrimmed(text, clauseStart, clauseEnd)
                clauseStart = start + separator.range.last + 1
            }
            addTrimmed(text, clauseStart, end)
            start = end
            end = sentences.next()
        }
    }

    // A narrow grammatical expansion, not a bag-of-dates comparison. Every value stays
    // with its named object; the shared subject/predicate is used only for comparison.
    // Returned ranges always point to the exact original text, never synthetic words.
    private val datedPredicate = Regex("(?i)^(.+?\\b(joined|entered|adopted|signed|ratified|founded|established)\\s+)")
    private val coordinatedSeparator = Regex(",\\s*(?:and\\s+)?|\\s+and\\s+")
    private val datedObject = Regex("(?i)^.+?\\b(?:in|by|during)\\s+[12][0-9]{3}[.!? ]*$")
    private val ownPredicate = Regex("(?i)^(?:joined|entered|adopted|signed|ratified|founded|established)\\b")
    private val explicitSubject = Regex("^$subject\\s+$verb\\b")
    private fun datedCoordinations(sentence: String): List<Clause> {
        val leading = sentence.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val prefix = datedPredicate.find(sentence.substring(leading)) ?: return emptyList()
        val offset = leading + prefix.range.last + 1
        val tail = sentence.substring(offset)
        val separators = coordinatedSeparator.findAll(tail).toList()
        if (separators.isEmpty()) return emptyList()
        val cuts = listOf(0) + separators.map { it.range.last + 1 }
        val ends = separators.map { it.range.first } + tail.length
        val parts = cuts.zip(ends).map { (from, to) -> tail.substring(from, to).trim() }
        if (parts.any { !datedObject.matches(it) || Regex("\\b[0-9]+\\b").findAll(it).count() != 1 }) return emptyList()
        val subject = prefix.groupValues[1].substringBeforeLast(prefix.groupValues[2], "").trim()
        if (subject.isBlank()) return emptyList()
        return cuts.zip(ends).mapIndexed { index, (from, to) ->
            val start = if (index == 0) leading else offset + from
            var end = offset + to
            while (end > start && !sentence[end - 1].isLetterOrDigit()) end--
            val text = sentence.substring(start, end)
            val comparison = if (index == 0) {
                text
            } else if (explicitSubject.containsMatchIn(text)) {
                text
            } else if (ownPredicate.containsMatchIn(text)) {
                "$subject $text"
            } else {
                prefix.groupValues[1] + text
            }
            Clause(start, end, text, comparison)
        }
    }

    private fun MutableList<Clause>.addTrimmed(text: String, from: Int, to: Int) {
        var start = from
        var end = to
        while (start < end && !text[start].isLetterOrDigit()) start++
        while (end > start && !text[end - 1].isLetterOrDigit()) end--
        if (end > start) add(Clause(start, end, text.substring(start, end)))
    }
}
