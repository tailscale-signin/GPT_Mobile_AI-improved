package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** User constraints govern editing, independently of how many contributors were collected. */
@Serializable
data class CombinedAnswerPolicy(
    val maximumWords: Int? = null,
    val targetWords: Int? = null,
    val singleExample: Boolean = false,
    val exactWords: Boolean = false,
    val requiresCurrentEvidence: Boolean = false
) {
    companion object {
        private val words = Regex("(?i)(?:(under|fewer than|less than|at most|no more than|up to|maximum(?: of)?|max[.:]?)\\s+)?([1-9][0-9,]{0,6})\\s*[-–]?\\s*words?\\b")
        fun from(request: String): CombinedAnswerPolicy {
            val goal = words.findAll(request).lastOrNull()
            val count = goal?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()?.takeIf { it in 1..100_000 }
            val qualifier = goal?.groupValues?.get(1)?.lowercase().orEmpty()
            val strict = qualifier in setOf("under", "fewer than", "less than")
            val single = Regex("(?i)\\b(?:one|single|a random|random|an interesting random)\\s+(?:historical\\s+|history\\s+)?(?:fact|example|event|story)\\b").containsMatchIn(request)
            val current = Regex("(?i)\\b(?:newest|latest|breaking|current|today'?s?)\\b.*\\b(?:news|story|stories|headlines?|events?)\\b|\\b(?:news|story|stories|headlines?)\\b.*\\b(?:newest|latest|current|today)\\b").containsMatchIn(request)
            return CombinedAnswerPolicy(
                maximumWords = count?.let {
                    if (strict) {
                        (it - 1).coerceAtLeast(0)
                    } else if (qualifier.isNotEmpty()) {
                        it
                    } else {
                        null
                    }
                },
                targetWords = count.takeIf { qualifier.isEmpty() },
                singleExample = single,
                exactWords = Regex("(?i)\\bexactly\\s+[0-9,]+\\s*words?\\b").containsMatchIn(request),
                requiresCurrentEvidence = current
            )
        }
    }

    fun violations(answer: String): List<String> = buildList {
        val count = visibleWordCount(answer)
        maximumWords?.let { if (count > it) add("WORD_LIMIT_EXCEEDED:$count/$it") }
        targetWords?.let {
            if (exactWords && count != it) {
                add("EXACT_WORD_COUNT_UNMET:$count/$it")
            } else if (count > it + maxOf(2, it / 10)) {
                add("WORD_TARGET_EXCEEDED:$count/$it")
            } else if (count < it - maxOf(2, it / 10)) {
                add("WORD_TARGET_UNDERSHOT:$count/$it")
            }
            Unit
        }
        if (answer.contains(COMBINED_RECOVERY_MARKER)) add("INTERNAL_RECOVERY_MARKER")
        val sentences = CombinedContentCoverage.sentences(answer).map(CombinedContentCoverage::normalized).filter { it.length > 30 }
        if (sentences.size != sentences.distinct().size) add("DUPLICATE_SENTENCES")
    }
}

/** Frozen at the user turn, not recomputed independently by each model. */
@Serializable
data class CombinedTurnContext(val instant: String, val date: String, val timezone: String, val authority: String = "DEVICE_CLOCK_AT_USER_TURN") {
    fun instruction(): String = "\nAuthoritative turn context: date=$date; timezone=$timezone; instant=$instant; authority=$authority. Treat model-generated dates as assertions, not a reason to doubt this context. Successful current_date tool results take precedence over model guesses. For news, compare article publication timestamps in this timezone, distinguish publication from event dates, and never claim a globally newest story without evidence.\n"
    companion object {
        fun capture(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): CombinedTurnContext {
            val instant = Instant.ofEpochSecond(epochSeconds)
            return CombinedTurnContext(instant.toString(), instant.atZone(zone).toLocalDate().toString(), zone.id)
        }
    }
}

internal fun visibleWordCount(answer: String): Int = LongResponsePolicy.countWords(answer.replace(Regex("\\]\\([^)]*\\)"), "]"))

/** Last-resort sentence-boundary enforcement; original contributions remain untouched. */
internal fun enforceCombinedWordCeiling(answer: String, maximum: Int): String {
    if (visibleWordCount(answer) <= maximum) return answer
    if (maximum <= 0) return ""
    val iterator = java.text.BreakIterator.getSentenceInstance(java.util.Locale.ROOT).apply { setText(answer) }
    var end = iterator.first()
    var accepted = 0
    while (end != java.text.BreakIterator.DONE) {
        val candidate = answer.substring(0, end).trimEnd()
        if (visibleWordCount(candidate) > maximum) break
        accepted = end
        end = iterator.next()
    }
    return if (accepted > 0) {
        answer.substring(0, accepted).trimEnd()
    } else if (maximum >= 9) {
        "Unable to fit a reliable answer within this limit."
    } else {
        "Unavailable."
    }
}
