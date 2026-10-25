package dev.chungjungsoo.gptmobile.data.agent

/** Synthetic framing is presentation only; stored/exported bytes never include it. */
internal data class CombinedMarkdownFrame(val start: Int, val end: Int, val openingFence: String = "", val closingFence: String = "", val tableHeader: String = "")

internal object CombinedMarkdownPaging {
    private data class Fence(val marker: Char, val count: Int, val opening: String)

    fun frames(text: String): Sequence<CombinedMarkdownFrame> = sequence {
        var fence: Fence? = null
        var table: String? = null
        var previousLine: String? = null
        var lineStart = 0
        for ((start, page) in CombinedPayload.pages(text)) {
            val end = start + page.length
            val openingFence = fence?.let { it.opening + "\n" }.orEmpty()
            val tableHeader = if (fence == null && table != null && !text.startsWith("\n", start) && !text.startsWith("\r\n", start)) table.orEmpty() else ""
            while (lineStart < end) {
                val newline = text.indexOf('\n', lineStart)
                if (newline >= end || newline < 0 && end < text.length) break
                val lineEnd = if (newline < 0) text.length else newline
                val line = text.substring(lineStart, lineEnd).removeSuffix("\r")
                val trimmed = line.dropWhile { it == ' ' }.takeIf { line.length - it.length <= 3 }.orEmpty()
                val marker = trimmed.firstOrNull()
                val markerCount = if (marker in setOf('`', '~')) trimmed.takeWhile { it == marker }.length else 0
                val currentFence = fence
                if (currentFence != null) {
                    if (marker == currentFence.marker && markerCount >= currentFence.count && trimmed.drop(markerCount).isBlank()) fence = null
                    table = null
                } else if (markerCount >= 3) {
                    fence = Fence(requireNotNull(marker), markerCount, trimmed)
                    table = null
                } else if (separatorColumns(line) != null && previousLine?.let(::columns) == separatorColumns(line)) {
                    table = previousLine + "\n" + line + "\n"
                } else if (line.isBlank() || columns(line) == null) {
                    table = null
                }
                previousLine = if (fence == null) line else null
                lineStart = if (newline < 0) text.length else newline + 1
            }
            val closing = fence?.let { "\n" + it.marker.toString().repeat(it.count) }.orEmpty()
            yield(CombinedMarkdownFrame(start, end, openingFence, closing, tableHeader))
        }
    }

    private fun cells(line: String): List<String>? {
        var escaped = false
        var codeTicks = 0
        var index = 0
        var start = 0
        val cells = mutableListOf<String>()
        while (index < line.length) {
            val char = line[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '`') {
                var tickEnd = index + 1
                while (tickEnd < line.length && line[tickEnd] == '`') tickEnd++
                val count = tickEnd - index
                if (codeTicks == 0) {
                    codeTicks = count
                } else if (codeTicks == count) {
                    codeTicks = 0
                }
                index += count - 1
            } else if (char == '|' && codeTicks == 0) {
                cells += line.substring(start, index).trim()
                start = index + 1
            }
            index++
        }
        if (cells.isEmpty()) return null
        cells += line.substring(start).trim()
        if (cells.firstOrNull().isNullOrEmpty()) cells.removeAt(0)
        if (cells.lastOrNull().isNullOrEmpty() && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
        return cells.takeIf { it.isNotEmpty() }
    }

    private fun columns(line: String): Int? = cells(line)?.size
    private fun separatorColumns(line: String): Int? = cells(line)?.takeIf { row -> row.all { it.matches(Regex(":?-+:?")) } }?.size
}
