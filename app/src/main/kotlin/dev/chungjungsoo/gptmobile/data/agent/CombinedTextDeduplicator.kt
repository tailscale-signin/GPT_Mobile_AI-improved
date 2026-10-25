package dev.chungjungsoo.gptmobile.data.agent

/**
 * Removes exact repeated answer blocks that can be emitted when a provider restarts
 * an answer instead of continuing its saved prefix. This is intentionally conservative:
 * only exact normalized paragraph duplicates and repeated standalone headings are removed.
 */
internal object CombinedTextDeduplicator {
    fun removeRepeatedParagraphs(text: String): String {
        if (text.isBlank()) return text

        val seenParagraphs = hashSetOf<String>()
        val seenHeadings = hashSetOf<String>()
        val output = mutableListOf<String>()
        val blocks = text.split(Regex("\\n[ \\t]*\\n+"))

        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.isEmpty()) continue

            val lines = trimmed.lines()
            val keptLines = lines.filter { line ->
                val heading = line.trim().takeIf { it.matches(Regex("^#{1,6}\\\\s+.+$")) }
                if (heading == null) {
                    true
                } else {
                    seenHeadings.add(heading.lowercase().replace(Regex("\\\\s+"), " "))
                }
            }
            if (keptLines.isEmpty()) continue

            val kept = keptLines.joinToString("\\n").trim()
            if (kept.isEmpty()) continue
            val key = kept.lowercase().replace(Regex("\\\\s+"), " ").trim()
            if (key.length >= 24 && !seenParagraphs.add(key)) continue
            output += kept
        }

        return output.joinToString("\\n\\n").trim()
    }
}
