package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import kotlin.math.max

/**
 * State and matching algorithm for the Suggestion Button Hold-to-Highlight feature.
 *
 * When a user presses and holds a suggestion button (e.g. dynamic action chip),
 * the corresponding source sentence in the AI assistant response smoothly fades toward
 * bright yellow (#FFD600) and bold font-weight (700) over a 2-second transition.
 * On release or interruption, the transition reverses smoothly from its current state.
 */
object SuggestionHighlightManager {

    val HIGHLIGHT_YELLOW: Color = Color(0xFFFFD600)
    val HIGHLIGHT_TRANSLUCENT_BG: Color = Color(0xFFFFD600).copy(alpha = 0.25f)
    const val ANIMATION_DURATION_MS = 2000

    /**
     * Splits assistant response markdown/plain text into clean candidate sentences.
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // Strip fenced code blocks to prevent partial code block matching
        val withoutCodeBlocks = text.replace(Regex("""```[\s\S]*?```"""), "")

        // Split by standard sentence terminators (. ! ?) followed by whitespace or line breaks
        val rawSentences = withoutCodeBlocks.split(Regex("""(?<=[.?!])\s+|\n\n+"""))

        return rawSentences
            .map { it.trim() }
            .filter { sentence ->
                val clean = sentence.replace(Regex("""^[\s*#\-_•\d\.\)\:]+"""), "").trim()
                clean.length >= 8
            }
    }

    /**
     * Finds the sentence within [responseContent] that best matches the suggestion [buttonText] / [promptText].
     * Returns null if no matching sentence can be confidently mapped (threshold < 0.35 similarity).
     */
    fun findMatchingSentence(responseContent: String, buttonText: String, promptText: String = ""): String? {
        if (responseContent.isBlank() || (buttonText.isBlank() && promptText.isBlank())) return null

        val sentences = splitIntoSentences(responseContent)
        if (sentences.isEmpty()) return null

        val cleanButton = buttonText.trim().lowercase(Locale.ROOT)
        val cleanPrompt = promptText.trim().lowercase(Locale.ROOT)

        var bestSentence: String? = null
        var highestScore = 0.0

        for (rawSentence in sentences) {
            val sentenceLower = rawSentence.lowercase(Locale.ROOT)
            val score = computeSimilarity(sentenceLower, cleanButton, cleanPrompt)

            if (score > highestScore) {
                highestScore = score
                bestSentence = rawSentence
            }
        }

        // Confidence threshold: at least 0.35 similarity to prevent false-positive highlights
        return if (highestScore >= 0.35) bestSentence else null
    }

    /**
     * Calculates token overlap and substring containment score.
     */
    private fun computeSimilarity(sentenceLower: String, buttonLower: String, promptLower: String): Double {
        var score = 0.0

        // Direct containment bonus
        if (buttonLower.isNotBlank() && sentenceLower.contains(buttonLower)) {
            score = max(score, 0.85)
        }
        if (promptLower.isNotBlank() && sentenceLower.contains(promptLower)) {
            score = max(score, 0.95)
        }

        // Token intersection calculation
        val stopWords = setOf("a", "an", "the", "to", "for", "in", "of", "and", "or", "me", "you", "i", "we", "is", "it", "with")
        val sentenceTokens = tokenize(sentenceLower).filter { it !in stopWords }
        val buttonTokens = tokenize(buttonLower).filter { it !in stopWords }
        val promptTokens = tokenize(promptLower).filter { it !in stopWords }

        val queryTokens = (buttonTokens + promptTokens).distinct()
        if (queryTokens.isEmpty() || sentenceTokens.isEmpty()) return score

        val intersectionCount = queryTokens.count { token -> sentenceTokens.any { sToken -> sToken.contains(token) || token.contains(sToken) } }
        val jaccardScore = intersectionCount.toDouble() / queryTokens.size.toDouble()

        return max(score, jaccardScore)
    }

    private fun tokenize(text: String): List<String> {
        return text.split(Regex("""[^a-zA-Z0-9]+""")).filter { it.isNotBlank() && it.length > 2 }
    }

    /**
     * Linearly interpolates font weight based on progress [0f, 1f].
     */
    fun interpolateFontWeight(progress: Float): FontWeight {
        val targetWeight = 700 // Bold
        val baseWeight = 400   // Normal
        val calculated = (baseWeight + (targetWeight - baseWeight) * progress.coerceIn(0f, 1f)).toInt()
        return FontWeight(calculated)
    }
}
