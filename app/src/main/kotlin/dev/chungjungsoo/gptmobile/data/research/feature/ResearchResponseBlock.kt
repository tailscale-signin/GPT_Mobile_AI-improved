package dev.chungjungsoo.gptmobile.data.research.feature

internal data class ResearchResponseBlock(val text: String, val citationText: String = "")

// Fast linear-time precheck: actual citation normalization still happens exactly
// once per flushed block, not once for every blank line in the growing answer.
private val articleCitationHint = Regex("https?://|\\[S[0-9a-f]|\\[(?:study|article):|(?i:doi:|pmid:|pmcid:|arxiv:)")

/** Insert cards after a cited paragraph while retaining whole fenced blocks and source text. */
internal fun researchResponseBlocks(text: String, splitParagraphs: Boolean = false): List<ResearchResponseBlock> {
    if (text.length > 1_000_000) return listOf(ResearchResponseBlock(text))
    val blocks = mutableListOf<ResearchResponseBlock>()
    val pending = StringBuilder()
    var fence: String? = null
    var pendingHasCitationHint = false
    val shown = mutableSetOf<String>()
    fun flush() {
        if (pending.isEmpty()) return
        val value = pending.toString()
        val keys = articleCitationOccurrences(value).map { it.key }.toSet()
        val newKeys = keys - shown
        blocks += ResearchResponseBlock(value, if (newKeys.isEmpty()) "" else value)
        shown += keys
        pending.clear()
        pendingHasCitationHint = false
    }
    text.splitToSequence('\n').forEach { line ->
        val delimiter = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
        if (delimiter != null) {
            if (fence == null) {
                fence = delimiter
            } else if (delimiter.first() == fence!!.first() && delimiter.length >= fence!!.length) {
                fence = null
            }
        }
        if (fence == null && !pendingHasCitationHint && articleCitationHint.containsMatchIn(line)) pendingHasCitationHint = true
        if (pending.isNotEmpty()) pending.append('\n')
        pending.append(line)
        if (line.isBlank() && fence == null && (splitParagraphs || pendingHasCitationHint)) flush()
    }
    flush()
    return blocks
}
