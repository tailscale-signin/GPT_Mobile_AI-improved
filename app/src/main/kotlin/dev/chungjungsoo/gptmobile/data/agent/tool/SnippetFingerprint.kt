package dev.chungjungsoo.gptmobile.data.agent.tool

import java.text.Normalizer
import java.util.Locale

internal data class SnippetFingerprint(
    val tokens: List<String>,
    val shingles: Set<String>,
    val titleTokens: List<String>,
    val facts: Set<String>,
    val negations: Set<String>,
    val publishedDate: String?,
    val compatible: Boolean,
    val truncated: Boolean
) {
    fun match(other: SnippetFingerprint, policy: SearchMergePolicy): Double? {
        if (!compatible || !other.compatible || tokens.size < 12 || other.tokens.size < 12 || shingles.size < 8 || other.shingles.size < 8) return null
        if (facts != other.facts || negations != other.negations) return null
        if (publishedDate != null && other.publishedDate != null && publishedDate != other.publishedDate) return null
        if (titleTokens.size < 3 || other.titleTokens.size < 3) return null
        if (titleTokens.toSet().size < 3 || other.titleTokens.toSet().size < 3 || titleTokens.joinToString(" ") in genericTitles || other.titleTokens.joinToString(" ") in genericTitles) return null
        if (titleTokens != other.titleTokens && jaccard(titleTokens.toSet(), other.titleTokens.toSet()) < policy.titleThreshold) return null
        return jaccard(shingles, other.shingles).takeIf { it >= policy.snippetThreshold }
    }

    companion object {
        private val words = Regex("[\\p{L}\\p{M}\\p{N}]+")
        private val fact = Regex("[\\p{L}\\p{N}]*\\p{N}[\\p{L}\\p{N}.:/_-]*")
        private val negative = setOf("no", "not", "never", "without", "neither", "cannot", "can't", "n't", "pas", "sans", "aucun", "non", "nicht", "kein", "nunca", "sin")
        private val genericTitles = setOf("about our company", "welcome to our website", "latest news updates", "official home page", "official web site", "search engine results")
        private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

        fun create(snippet: String, title: String, publishedDate: String?): SnippetFingerprint {
            val normalized = normalize(snippet.take(2048).replace(Regex("<[^>]*>"), " "))
            val allTokens = words.findAll(normalized).map { it.value }.take(257).toList()
            val tokens = allTokens.take(256)
            val titleText = normalize(title.take(512))
            val titleTokens = if (SearchUrlIdentity.parse(title) != null) emptyList() else words.findAll(titleText).map { it.value }.take(64).toList()
            // The initial tokenizer does not reliably segment these scripts; retain every URL.
            val compatible = (normalized + titleText).codePoints().noneMatch { cp ->
                Character.UnicodeScript.of(cp) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR)
            }
            val facts = fact.findAll(normalized + " " + titleText).map { it.value }.toSet()
            val negations = (tokens + titleTokens).filter { it in negative }.toSet() +
                listOfNotNull("contraction".takeIf { "n't" in normalized || "n't" in titleText })
            return SnippetFingerprint(tokens, tokens.windowed(3).map { it.joinToString("\u0001") }.toSet(), titleTokens, facts, negations, publishedDate, compatible, snippet.length > 2048 || allTokens.size > 256)
        }

        fun jaccard(first: Set<String>, second: Set<String>): Double {
            if (first.isEmpty() || second.isEmpty()) return 0.0
            val small = if (first.size < second.size) first else second
            val large = if (first.size < second.size) second else first
            val intersection = small.count { it in large }
            return intersection.toDouble() / (first.size + second.size - intersection)
        }
    }
}
