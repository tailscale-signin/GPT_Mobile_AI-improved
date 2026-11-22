package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.github.GitHubSearchArgumentFailure.Expected
import dev.chungjungsoo.gptmobile.data.github.GitHubSearchArgumentFailure.Rule
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Query options are part of the cursor/cache identity, including presentation limits. */
internal data class GitHubSearchOptions(
    val suffix: String = "",
    val caseSensitive: Boolean = false,
    val fuzzy: Boolean = false,
    val threshold: Double = 0.8,
    val minLines: Int = 0,
    val maxLines: Int = 1_000_000,
    val contextLines: Int = 2,
    val pattern: Boolean = false
) {
    fun json() = buildJsonObject {
        put("path_suffix", suffix)
        put("case_sensitive", caseSensitive)
        put("fuzzy_match", fuzzy)
        put("fuzzy_threshold", threshold)
        put("min_lines", minLines)
        put("max_lines", maxLines)
        put("context_lines", contextLines)
        put("pattern_match", pattern)
    }

    fun score(text: String, query: String, checkpoint: () -> Unit = {}): Double {
        if (pattern) {
            // Ordered literal segments, not a backtracking regular expression. A star
            // spans characters on this line; matches remain inside the pinned blob.
            var offset = 0
            for (part in query.split('*').filter { it.isNotEmpty() }) {
                checkpoint()
                val found = text.indexOf(part, offset, ignoreCase = !caseSensitive)
                if (found < 0) return 0.0
                offset = found + part.length
            }
            return 1.0
        }
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
        private val fuzzyQuery = Regex("[A-Za-z_][A-Za-z_0-9]{1,79}")
        fun supportsFuzzyQuery(query: String) = fuzzyQuery.matches(query)
        fun from(args: JsonObject): GitHubSearchOptions {
            val mode = GitHubSearchArguments.text(args, "mode", "literal")
            val query = GitHubSearchArguments.text(args, "query")
            val result = GitHubSearchOptions(
                GitHubSearchArguments.text(args, "path_suffix"),
                GitHubSearchArguments.boolean(args, "case_sensitive"),
                GitHubSearchArguments.boolean(args, "fuzzy_match"),
                GitHubSearchArguments.number(args, "fuzzy_threshold", 0.8),
                GitHubSearchArguments.integer(args, "min_lines", 0),
                GitHubSearchArguments.integer(args, "max_lines", 1_000_000),
                GitHubSearchArguments.integer(args, "context_lines", 2),
                mode == "patterns" || mode == "paths" && query.contains('*')
            )
            if (!result.threshold.isFinite() || result.threshold !in 0.5..1.0) GitHubSearchArguments.reject("fuzzy_threshold", Rule.RANGE, Expected.NUMBER_0_5_1, "Use a finite number from 0.5 to 1.")
            if (result.minLines !in 0..1_000_000) GitHubSearchArguments.reject("min_lines", Rule.RANGE, Expected.INTEGER_0_1000000, "Use an integer from 0 to 1000000.")
            if (result.maxLines !in 1..1_000_000) GitHubSearchArguments.reject("max_lines", Rule.RANGE, Expected.INTEGER_1_1000000, "Use an integer from 1 to 1000000.")
            if (result.maxLines < result.minLines) GitHubSearchArguments.reject("max_lines", Rule.LINE_ORDER, Expected.MAX_AT_LEAST_MIN, "max_lines must be at least min_lines.")
            if (result.contextLines !in 0..100) GitHubSearchArguments.reject("context_lines", Rule.RANGE, Expected.INTEGER_0_100, "Use an integer from 0 to 100.")
            if (result.suffix.length > 200) GitHubSearchArguments.reject("path_suffix", Rule.RANGE, Expected.TEXT_MAX_200, "Use at most 200 characters.")
            if (mode == "patterns" && listOf(".*", ".+", "\\d", "\\w", "(?", "[", "]", "^", "$").any(query::contains)) {
                GitHubSearchArguments.reject("query", Rule.PATTERN_SYNTAX, Expected.LITERAL_STAR_PATTERN, "Pattern mode uses literal segments and * only; regular expressions are unsupported.")
            }
            if (result.pattern && result.fuzzy) GitHubSearchArguments.reject("fuzzy_match", Rule.PATTERN_FUZZY, Expected.FALSE, "Fuzzy matching cannot be combined with patterns.")
            if (result.pattern && (query.length !in 1..200 || query.count { it == '*' } > 16 || query.none { it != '*' && !it.isWhitespace() } && !(mode == "paths" && query == "*"))) {
                GitHubSearchArguments.reject("query", Rule.PATTERN_BOUNDS, Expected.BOUNDED_PATTERN, "Use literal segments separated by at most 16 stars, with at least one literal character (paths mode also permits *).")
            }
            if (result.fuzzy && !supportsFuzzyQuery(query)) GitHubSearchArguments.reject("query", Rule.FUZZY_IDENTIFIER, Expected.IDENTIFIER_2_80, "Fuzzy search accepts one identifier of 2–80 characters. Retain a phrase and set fuzzy_match=false for a literal search.")
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
