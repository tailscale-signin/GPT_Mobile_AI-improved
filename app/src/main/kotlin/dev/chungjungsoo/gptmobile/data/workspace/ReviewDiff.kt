package dev.chungjungsoo.gptmobile.data.workspace

/** Bounded exact replacement hunk; deliberately avoids quadratic LCS for large files. */
object ReviewDiff {
    fun render(path: String, before: String, after: String): String {
        if (before == after) return "No changes"
        val old = before.split('\n')
        val new = after.split('\n')
        var prefix = 0
        while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(old.size, new.size) - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        return buildString {
            append("--- a/$path\n+++ b/$path\n@@ -${prefix + 1},${old.size - prefix - suffix} +${prefix + 1},${new.size - prefix - suffix} @@\n")
            old.subList(prefix, old.size - suffix).forEach { append('-').append(it).append('\n') }
            new.subList(prefix, new.size - suffix).forEach { append('+').append(it).append('\n') }
        }
    }
}
