package dev.chungjungsoo.gptmobile.data.agent

import java.text.BreakIterator
import java.util.Locale

/** Exact UTF-16 ranges for independently stated clauses; quantities never migrate between clauses. */
internal object CombinedClaimClauses {
    data class Clause(val start: Int, val end: Int, val text: String)

    // A coordinated proper name is not enough: “Portugal and Spain” is one object.
    // Split only when the next named subject has its own finite verb.
    private val subject = "[A-Z][\\p{L}’'-]+(?:\\s+(?:[A-Z][\\p{L}’'-]+|da|de|do|van|von)){0,4}"
    private val verb = "(?:was|were|is|are|has|had|became|brought|built|fell|led|made|took|won|[\\p{L}]{3,}ed)"
    private val boundary = Regex(";\\s*|(?:,\\s+|(?<=\\d)\\s+)(?:and|but|whereas|while)\\s+(?=$subject\\s+$verb\\b)|,\\s+(?:which|who|opening|establishing|proving|accelerating)\\s+")

    // Do not detach a positive-looking clause from a sentence-wide denial, hedge or condition.
    private val scoped = Regex("(?i)\\b(?:not|no|never|without|cannot|may|might|could|perhaps|possibly|allegedly|reportedly|unless|only|except)\\b|n[’']t\\b")

    fun spans(text: String): List<Clause> = buildList {
        val sentences = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        var start = sentences.first()
        var end = sentences.next()
        while (end != BreakIterator.DONE) {
            var clauseStart = start
            val sentence = text.substring(start, end)
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

    private fun MutableList<Clause>.addTrimmed(text: String, from: Int, to: Int) {
        var start = from
        var end = to
        while (start < end && !text[start].isLetterOrDigit()) start++
        while (end > start && !text[end - 1].isLetterOrDigit()) end--
        if (end > start) add(Clause(start, end, text.substring(start, end)))
    }
}
