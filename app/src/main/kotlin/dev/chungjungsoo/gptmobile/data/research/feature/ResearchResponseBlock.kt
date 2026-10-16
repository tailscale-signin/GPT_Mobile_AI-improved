package dev.chungjungsoo.gptmobile.data.research.feature

internal data class ResearchResponseBlock(val text: String, val citationText: String = "")

/** Insert cards after a cited paragraph while retaining whole fenced blocks and source text. */
internal fun researchResponseBlocks(text: String, splitParagraphs: Boolean = false): List<ResearchResponseBlock> {
    if (text.length > 1_000_000) return listOf(ResearchResponseBlock(text))
    val blocks = mutableListOf<ResearchResponseBlock>()
    val pending = StringBuilder()
    var fence: String? = null
    val shown = mutableSetOf<String>()
    fun flush() {
        if (pending.isEmpty()) return
        val value = pending.toString()
        val keys = articleCitationOccurrences(value).map { it.key }.toSet()
        val newKeys = keys - shown
        blocks += ResearchResponseBlock(value, if (newKeys.isEmpty()) "" else value)
        shown += keys
        pending.clear()
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
        if (pending.isNotEmpty()) pending.append('\n')
        pending.append(line)
        if (line.isBlank() && fence == null && (splitParagraphs || articleCitationOccurrences(pending.toString()).isNotEmpty())) flush()
    }
    flush()
    return blocks
}
