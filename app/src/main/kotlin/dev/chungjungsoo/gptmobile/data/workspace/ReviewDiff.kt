package dev.chungjungsoo.gptmobile.data.workspace

/** Exact text ranges separated by unique unchanged lines. Repeated lines may share a larger hunk. */
object ReviewDiff {
    data class Hunk(val oldStart: Int, val newStart: Int, val old: String, val new: String, val offset: Int) {
        fun reject(after: String): String {
            require(after.startsWith(new, offset))
            return after.replaceRange(offset, offset + new.length, old)
        }

        fun render(): String = buildString {
            append("@@ -").append(oldStart + 1).append(" +").append(newStart + 1).append(" @@\n")
            for ((marker, text) in listOf("-" to old, "+" to new)) {
                for (line in pieces(text)) {
                    append(marker).append(line)
                    if (!line.endsWith('\n')) append("\n\\ No newline at end of file\n")
                }
            }
        }
    }

    private fun pieces(text: String): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline + 1
            add(text.substring(start, end))
            start = end
        }
    }

    fun hunks(before: String, after: String): List<Hunk> {
        if (before == after) return emptyList()
        val old = pieces(before)
        val new = pieces(after)
        val counts = old.groupingBy { it }.eachCount()
        val positions = mutableMapOf<String, Int>()
        new.forEachIndexed { index, line -> positions[line] = if (line in positions) -1 else index }
        val offsets = IntArray(new.size + 1)
        new.forEachIndexed { i, line -> offsets[i + 1] = offsets[i] + line.length }
        val result = mutableListOf<Hunk>()
        var left = 0
        var right = 0
        fun gap(oldEnd: Int, newEnd: Int) {
            while (left < oldEnd && right < newEnd && old[left] == new[right]) {
                left++
                right++
            }
            var a = oldEnd
            var b = newEnd
            while (a > left && b > right && old[a - 1] == new[b - 1]) {
                a--
                b--
            }
            if (a != left || b != right) result += Hunk(left, right, old.subList(left, a).joinToString(""), after.substring(offsets[right], offsets[b]), offsets[right])
            left = oldEnd + 1
            right = newEnd + 1
        }
        old.forEachIndexed { index, line ->
            val match = positions[line] ?: -1
            if (counts[line] == 1 && match >= right) gap(index, match)
        }
        gap(old.size, new.size)
        return result
    }

    fun render(path: String, before: String, after: String): String {
        if (before == after) return "No changes"
        return "--- a/$path\n+++ b/$path\n" + hunks(before, after).joinToString("") { it.render() }
    }
}
