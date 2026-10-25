package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
data class CombinedTableCellAddress(val row: Int, val column: Int, val header: String, val startUtf16: Int, val endUtf16: Int)

/** Version two extraction preserves row context and adjacent qualifications without changing legacy IDs. */
internal object CombinedSourceClaims {
    data class Unit(val text: String, val start: Int, val end: Int, val cells: List<CombinedTableCellAddress> = emptyList())
    private data class Line(val text: String, val start: Int)
    private data class Cell(val text: String, val start: Int, val end: Int)
    private val separator = Regex("^:?-{3,}:?$")
    private val qualification = Regex("(?i)^(?:however|but|although|yet|these (?:findings|results|effects)|this (?:finding|result|effect|evidence)|evidence (?:is|remains)|(?:the )?(?:study|studies|research|benefits?|effects?) (?:is|are|was|were|remain|remains)|(?:it|this) (?:is|does) not|not (?:a|an)|only)\\b")
    private val courtesy = Regex("(?i)^(?:(?:hi|hello|hey)(?: there| again)?|welcome(?: back)?|good (?:morning|afternoon|evening)|(?:thank you|thanks)(?: very much)?|you(?:'|’)re welcome|(?:how|what) can I (?:help|assist).*|different term(?: please)?|(?:please )?(?:clarify|try again)|(?:improve|attention|benefits?))[!,.? ]*$")

    fun material(text: String): Boolean {
        val normalized = CombinedContentCoverage.normalized(text)
        return !courtesy.matches(normalized) && normalized.split(' ').count { it.isNotBlank() } >= 3
    }

    /** Structural labels supply context; they are not additional factual propositions. */
    fun structuralHeading(text: String): Boolean {
        val line = text.trim()
        if (line.startsWith('#')) return true
        val label = Regex("^(?:[-*+] |[0-9]+[.)] )?(?:\\*\\*(.+?)\\*\\*|__(.+?)__):?$").matchEntire(line)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: return false
        if (label.any(Char::isDigit) || Regex("[.!?]$").containsMatchIn(label.trim())) return false
        if (Regex("(?i)\\b(?:is|are|was|were|has|have|had|may|might|could|can|cannot|must|should|reduces?|improves?|increases?|causes?|shows?|suggests?|prevents?|no|not|without|unproven|unsafe|ineffective)\\b").containsMatchIn(label)) return false
        return true
    }

    /** Exact row/cell offsets refer to the original body, never to a reserialized table. */
    fun units(text: String, partial: Boolean = false, excludeStructural: Boolean = false): List<Unit> {
        var offset = 0
        val lines = text.split('\n').map { line -> Line(line, offset).also { offset += line.length + 1 } }
        val result = mutableListOf<Unit>()
        var index = 0
        var tableRow = 0
        val prose = StringBuilder()
        var proseStart = 0
        fun flush() {
            if (prose.isBlank()) {
                prose.setLength(0)
                return
            }
            val raw = prose.toString()
            val spans = CombinedTextSpanIndex(raw)
            var cursor = 0
            val sentences = CombinedContentCoverage.sentences(raw)
            var position = 0
            while (position < sentences.size) {
                val sentence = sentences[position++]
                val first = spans.find(sentence, cursor) ?: continue
                var end = first.endUtf16
                var content = sentence
                var next = sentences.getOrNull(position)?.takeIf { qualification.containsMatchIn(it.trim()) }
                var qualificationSpan = next?.let { spans.find(it, end) }
                while (qualificationSpan != null) {
                    content += " $next"
                    end = qualificationSpan.endUtf16
                    position++
                    if (!excludeStructural) break
                    next = sentences.getOrNull(position)?.takeIf { qualification.containsMatchIn(it.trim()) }
                    qualificationSpan = next?.let { spans.find(it, end) }
                }
                cursor = end
                if (material(content)) result += Unit(content, proseStart + first.startUtf16, proseStart + end)
            }
            prose.setLength(0)
        }
        while (index < lines.size) {
            val line = lines[index]
            val headers = cells(line.text)
            val divider = lines.getOrNull(index + 1)?.let { cells(it.text) }.orEmpty()
            if (headers.size >= 2 && divider.size == headers.size && divider.all { separator.matches(it.text.trim()) }) {
                flush()
                index += 2
                while (index < lines.size) {
                    val row = lines[index]
                    val values = cells(row.text)
                    if (values.size != headers.size || values.all { separator.matches(it.text.trim()) }) break
                    val addresses = values.mapIndexed { column, cell -> CombinedTableCellAddress(tableRow, column, headers[column].text, row.start + cell.start, row.start + cell.end) }
                    val proposition = values.mapIndexed { column, cell -> "${headers[column].text}: ${cell.text}" }.joinToString("; ")
                    if (material(proposition)) result += Unit(proposition, row.start, row.start + row.text.length, addresses)
                    tableRow++
                    index++
                }
            } else {
                if (line.text.isBlank() || line.text.trimStart().startsWith('#') || excludeStructural && structuralHeading(line.text)) {
                    flush()
                } else {
                    if (excludeStructural && Regex("^\\s*(?:[-*+] |[0-9]+[.)] )").containsMatchIn(line.text)) flush()
                    if (prose.isEmpty()) proseStart = line.start else prose.append('\n')
                    prose.append(line.text)
                }
                index++
            }
        }
        flush()
        if (partial) {
            val last = result.lastOrNull()
            // A clipped final phrase has no supported ending. Earlier complete sentences/rows survive.
            if (last != null && text.substring(last.end).isBlank()) {
                val completeSentence = Regex("[.!?][\"'’”)\\]]*$").containsMatchIn(last.text.trim())
                val closedTableRow = last.cells.isNotEmpty() && text.substring(last.start, last.end).trimEnd().endsWith('|')
                if (!completeSentence && !closedTableRow) result.removeAt(result.lastIndex)
            }
        }
        return result
    }

    fun tableRows(text: String): Int = units(text).count { it.cells.isNotEmpty() }

    private fun cells(line: String): List<Cell> {
        if (!line.contains('|')) return emptyList()
        val boundaries = mutableListOf<Int>()
        var escaped = false
        var ticks = 0
        var at = 0
        while (at < line.length) {
            val c = line[at]
            if (escaped) {
                escaped = false
                at++
                continue
            }
            if (c == '\\') {
                escaped = true
                at++
                continue
            }
            if (c == '`') {
                val start = at
                while (at < line.length && line[at] == '`') at++
                val count = at - start
                if (ticks == 0) {
                    ticks = count
                } else if (ticks == count) {
                    ticks = 0
                }
                continue
            }
            if (c == '|' && ticks == 0) boundaries += at
            at++
        }
        if (boundaries.isEmpty()) return emptyList()
        val cuts = listOf(-1) + boundaries + line.length
        return cuts.zipWithNext().mapNotNull { (left, right) ->
            var start = left + 1
            var end = right
            while (start < end && line[start].isWhitespace()) start++
            while (end > start && line[end - 1].isWhitespace()) end--
            if (start == end && (left == -1 || right == line.length)) null else Cell(line.substring(start, end), start, end)
        }
    }

    fun span(text: String, unit: Unit): CombinedCoverageLink = CombinedCoverageLink(unit.start, unit.end, researchHash(text.substring(unit.start, unit.end)))
}
