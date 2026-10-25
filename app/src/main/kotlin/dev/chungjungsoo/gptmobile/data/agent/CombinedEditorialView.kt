package dev.chungjungsoo.gptmobile.data.agent

/** A reversible view of contributor prose. All ranges address the unchanged UTF-16 body. */
internal object CombinedEditorialView {
    data class Span(val sourceStart: Int, val sourceEnd: Int, val viewStart: Int, val viewEnd: Int, val topic: String?)
    data class Omission(val start: Int, val end: Int, val reason: String, val duplicateStart: Int? = null, val duplicateEnd: Int? = null)
    data class View(val text: String, val spans: List<Span>, val omissions: List<Omission>)

    // Only an entire standalone control utterance qualifies. A correction containing
    // facts ("Actually, Portugal joined in 1986") is always retained as source data.
    private val selfEditing = Regex("(?i)^(?:wait,?\\s+)?(?:let me (?:rewrite|rephrase) (?:that|this)(?: more clearly)?|let me try again|(?:i should|i will) (?:rewrite|rephrase|revise) (?:that|this|the answer))[.!\\s]*$")
    private val heading = Regex("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")

    fun prepare(source: String): View {
        val result = StringBuilder()
        val spans = mutableListOf<Span>()
        val omissions = mutableListOf<Omission>()
        val headings = mutableListOf<Pair<Int, String>>()
        val seen = mutableMapOf<Pair<List<Pair<Int, String>>, String>, CombinedSourcePreservation.Block>()
        var fence: Char? = null
        var fenceLength = 0
        CombinedSourcePreservation.blocks(source).forEach { block ->
            var containsHeading = false
            block.text.lineSequence().forEach { line ->
                val trimmed = line.trimStart()
                val marker = trimmed.firstOrNull()
                val length = if (marker == '`' || marker == '~') trimmed.takeWhile { it == marker }.length else 0
                if (length >= 3) {
                    if (fence == null) {
                        fence = marker
                        fenceLength = length
                    } else if (marker == fence && length >= fenceLength && trimmed.drop(length).isBlank()) {
                        fence = null
                    }
                } else if (fence == null) {
                    heading.matchEntire(line)?.let { match ->
                        containsHeading = true
                        val level = match.groupValues[1].length
                        while (headings.lastOrNull()?.first?.let { it >= level } == true) headings.removeAt(headings.lastIndex)
                        headings += level to match.groupValues[2].trim()
                    }
                }
            }
            val key = headings.toList() to block.text
            val duplicate = seen[key].takeUnless { containsHeading }
            when {
                selfEditing.matches(block.text.trim()) -> omissions += Omission(block.start, block.end, "STANDALONE_SELF_EDITING")
                duplicate != null -> omissions += Omission(block.start, block.end, "EXACT_REPETITION", duplicate.start, duplicate.end)
                else -> {
                    seen.putIfAbsent(key, block)
                    if (result.isNotEmpty()) result.append("\n\n")
                    val start = result.length
                    result.append(block.text)
                    spans += Span(block.start, block.end, start, result.length, headings.takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.second })
                }
            }
        }
        return View(result.toString(), spans, omissions)
    }
}
