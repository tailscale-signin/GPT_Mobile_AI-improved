package dev.chungjungsoo.gptmobile.data.github

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Query options are part of the cursor/cache identity, including presentation limits. */
internal data class GitHubSearchOptions(
    val suffix: String = "",
    val caseSensitive: Boolean = false,
    val fuzzy: Boolean = false,
    val threshold: Double = 0.8,
    val minLines: Int = 0,
    val maxLines: Int = 1_000_000,
    val contextLines: Int = 2
) {
    fun json() = buildJsonObject {
        put("path_suffix", suffix)
        put("case_sensitive", caseSensitive)
        put("fuzzy_match", fuzzy)
        put("fuzzy_threshold", threshold)
        put("min_lines", minLines)
        put("max_lines", maxLines)
        put("context_lines", contextLines)
    }

    fun score(text: String, query: String, checkpoint: () -> Unit = {}): Double {
        if (text.contains(query, ignoreCase = !caseSensitive)) return 1.0
        if (!fuzzy) return 0.0
        val needle = if (caseSensitive) query else query.lowercase(Locale.ROOT)
        // Fuzzy matching is deliberately identifier-sized, with bounded edit work.
        return identifier.findAll(text).map { it.value }.filter { it.length <= 80 }.maxOfOrNull { raw ->
            checkpoint()
            val candidate = if (caseSensitive) raw else raw.lowercase(Locale.ROOT)
            if (kotlin.math.abs(candidate.length - needle.length).toDouble() / maxOf(candidate.length, needle.length) > 1 - threshold) {
                0.0
            } else {
                1.0 - distance(candidate, needle).toDouble() / maxOf(candidate.length, needle.length)
            }
        }?.takeIf { it >= threshold } ?: 0.0
    }

    companion object {
        private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
        fun from(args: JsonObject): GitHubSearchOptions {
            fun number(key: String, fallback: Int) = args[key]?.jsonPrimitive?.intOrNull ?: fallback
            val result = GitHubSearchOptions(
                args["path_suffix"]?.jsonPrimitive?.content.orEmpty(),
                args["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
                args["fuzzy_match"]?.jsonPrimitive?.booleanOrNull ?: false,
                args["fuzzy_threshold"]?.jsonPrimitive?.doubleOrNull ?: 0.8,
                number("min_lines", 0),
                number("max_lines", 1_000_000),
                number("context_lines", 2)
            )
            require(result.threshold.isFinite() && result.threshold in 0.5..1.0) { "fuzzy_threshold must be between 0.5 and 1." }
            require(result.minLines in 0..1_000_000 && result.maxLines in maxOf(1, result.minLines)..1_000_000)
            require(result.contextLines in 0..5 && result.suffix.length <= 200)
            if (result.fuzzy) require(args["query"]?.jsonPrimitive?.content.orEmpty().matches(Regex("[A-Za-z_][A-Za-z_0-9]{1,79}"))) { "Fuzzy search accepts one identifier of 2–80 characters." }
            return result
        }

        private fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val next = IntArray(b.length + 1)
                next[0] = i + 1
                for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                previous = next
            }
            return previous[b.length]
        }
    }
}
