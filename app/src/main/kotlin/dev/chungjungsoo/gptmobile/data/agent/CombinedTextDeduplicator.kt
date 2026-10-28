package dev.chungjungsoo.gptmobile.data.agent

/** Conservative cleanup of restarted prose; Markdown code and structured blocks retain their bytes. */
internal object CombinedTextDeduplicator {
    private val paragraphBreak = Regex("\\n[ \\t]*\\n+")
    private val heading = Regex("^#{1,6}\\s+.+$")
    private val fenceStart = Regex("^ {0,3}(`{3,}|~{3,}).*$")
    private val structuredLine = Regex("^(?: {4}|\\t|\\s*(?:>|[-*+]\\s|[0-9]+[.)]\\s))")

    fun removeRepeatedParagraphs(text: String): String {
        if (text.isBlank()) return text
        val seenParagraphs = hashSetOf<String>()
        val seenHeadings = hashSetOf<String>()
        val output = mutableListOf<String>()

        fun prose(value: String) {
            for (block in paragraphBreak.split(value)) {
                if (block.isBlank()) continue
                // Repeated steps, quotes, table rows and indented code can be meaningful.
                if (block.lines().any { structuredLine.containsMatchIn(it) || '|' in it }) {
                    output += block.trim('\n', '\r')
                    continue
                }
                val trimmed = block.trim()
                // Case and Unicode spelling can change identifiers and claims. Do not fold them.
                val key = trimmed.replace(Regex("\\s+"), " ")
                if (heading.matches(trimmed)) {
                    if (seenHeadings.add(key)) output += trimmed
                } else if (key.length < 24 || seenParagraphs.add(key)) {
                    output += trimmed
                }
            }
        }

        var proseStart = 0
        var fenceOffset: Int? = null
        var marker = ' '
        var markerCount = 0
        var offset = 0
        for (line in text.splitToSequence('\n')) {
            val currentFence = fenceOffset
            if (currentFence == null) {
                val opening = fenceStart.matchEntire(line.removeSuffix("\r"))
                if (opening != null) {
                    prose(text.substring(proseStart, offset))
                    fenceOffset = offset
                    marker = opening.groupValues[1].first()
                    markerCount = opening.groupValues[1].length
                }
            } else {
                val trimmed = line.removeSuffix("\r").dropWhile { it == ' ' }
                val indentation = line.removeSuffix("\r").length - trimmed.length
                val count = trimmed.takeWhile { it == marker }.length
                if (indentation <= 3 && count >= markerCount && trimmed.drop(count).isBlank()) {
                    output += text.substring(currentFence, offset + line.length)
                    fenceOffset = null
                    proseStart = minOf(text.length, offset + line.length + 1)
                }
            }
            offset += line.length + 1
        }
        val unclosed = fenceOffset
        if (unclosed != null) {
            output += text.substring(unclosed)
        } else {
            prose(text.substring(proseStart))
        }
        return output.joinToString("\n\n")
    }
}
