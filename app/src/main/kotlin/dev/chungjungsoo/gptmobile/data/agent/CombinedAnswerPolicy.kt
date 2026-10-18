package dev.chungjungsoo.gptmobile.data.agent

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** User constraints govern editing, independently of the sliders and collected source volume. */
@Serializable
data class CombinedAnswerPolicy(
    val maximumWords: Int? = null,
    val targetWords: Int? = null,
    val singleExample: Boolean = false,
    val exactWords: Boolean = false,
    val requiresCurrentEvidence: Boolean = false,
    val minimumWords: Int? = null,
    val version: Int = 2,
    val wordCounterVersion: String = CombinedWordCounter.VERSION,
    val approximateTolerancePercent: Int = 10,
    val requiredFormat: String? = null,
    val requestedItems: Int? = null,
    val requestedLanguage: String? = null,
    val requiredTopics: List<String> = emptyList(),
    val comparative: Boolean = false,
    val noSearch: Boolean = false
) {
    companion object {
        private val words = Regex("(?i)(?:(at least|minimum(?: of)?|more than|over|under|fewer than|less than|at most|no more than|up to|maximum(?: of)?|max[.:]?)\\s+)?([1-9][0-9,]{0,6})\\s*[-–]?\\s*words?\\b")
        private val itemCount = Regex("(?i)\\b(?:(?:exactly|give|list|provide|tell me|show me)\\s+)?(one|two|three|four|five|six|seven|eight|nine|ten|[1-9][0-9]{0,3})\\s+(?:random\\s+|historical\\s+|history\\s+)?(?:facts?|examples?|events?|stories|jokes?|reasons?|benefits?|items?|options?)\\b")
        private val namedNumbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        fun from(request: String): CombinedAnswerPolicy {
            val goal = words.findAll(request).lastOrNull()
            val count = goal?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()?.takeIf { it in 1..100_000 }
            val qualifier = goal?.groupValues?.get(1)?.lowercase().orEmpty()
            val minimum = qualifier.startsWith("at least") || qualifier.startsWith("minimum") || qualifier in setOf("more than", "over")
            val strict = qualifier in setOf("under", "fewer than", "less than")
            val single = Regex("(?i)\\b(?:one|single|a random|random|an interesting random|a)\\s+(?:historical\\s+|history\\s+)?(?:fact|example|event|story|joke)\\b").containsMatchIn(request)
            val current = Regex("(?i)\\b(?:newest|latest|breaking|current|today'?s?)\\b.*\\b(?:news|story|stories|headlines?|events?)\\b|\\b(?:news|story|stories|headlines?)\\b.*\\b(?:newest|latest|current|today)\\b").containsMatchIn(request)
            val itemToken = itemCount.findAll(request).lastOrNull()?.groupValues?.get(1)?.lowercase()
            val items = itemToken?.toIntOrNull() ?: namedNumbers.indexOf(itemToken).takeIf { it > 0 }
            val format = when {
                Regex("(?i)\\b(?:bullet(?:ed)?(?: points?| list)?|as bullets)\\b").containsMatchIn(request) -> "BULLETS"
                Regex("(?i)\\b(?:numbered (?:list|items)|enumerate)\\b").containsMatchIn(request) -> "NUMBERED_LIST"
                Regex("(?i)\\b(?:in|as|use) (?:a )?(?:markdown )?table\\b").containsMatchIn(request) -> "TABLE"
                else -> null
            }
            val language = Regex("(?i)\\b(?:answer|reply|write|respond)?\\s*in (English|Portuguese|French|Spanish|German|Italian|Japanese|Korean|Chinese|Arabic|Hindi)\\b").find(request)?.groupValues?.get(1)?.lowercase()
            // Only explicit topic clauses are compiled. Guessing a topic from every noun would impose new work.
            val topicClause = Regex("(?i)\\b(?:cover|include|address)\\s+(?:these|the following)\\s+topics?\\s*:\\s*([^\\n.!?]+)").find(request)?.groupValues?.get(1)
            return CombinedAnswerPolicy(
                maximumWords = count?.let {
                    when {
                        minimum -> null
                        strict -> (it - 1).coerceAtLeast(0)
                        qualifier.isNotEmpty() -> it
                        else -> null
                    }
                },
                targetWords = count.takeIf { qualifier.isEmpty() },
                singleExample = single,
                exactWords = Regex("(?i)\\bexactly\\s+[0-9,]+\\s*words?\\b").containsMatchIn(request),
                requiresCurrentEvidence = current,
                minimumWords = count?.takeIf { minimum }?.let { if (qualifier in setOf("more than", "over")) it + 1 else it },
                requiredFormat = format,
                requestedItems = items,
                requestedLanguage = language,
                requiredTopics = topicClause?.split(Regex("\\s*(?:,|;|\\band\\b)\\s*"))?.filter(String::isNotBlank).orEmpty(),
                comparative = Regex("(?i)\\b(?:compare|comparison|versus|vs\\.?|all (?:models|results|contributions))\\b").containsMatchIn(request),
                noSearch = Regex("(?i)\\b(?:no (?:search|browsing|tools)|do not (?:search|browse|use tools)|don't (?:search|browse|use tools)|without (?:searching|browsing|tools))\\b").containsMatchIn(request)
            )
        }
    }

    fun violations(answer: String): List<String> = buildList {
        val count = visibleWordCount(answer)
        minimumWords?.let { if (count < it) add("WORD_MINIMUM_UNMET:$count/$it") }
        maximumWords?.let { if (count > it) add("WORD_LIMIT_EXCEEDED:$count/$it") }
        targetWords?.let {
            val tolerance = maxOf(2, it * approximateTolerancePercent / 100)
            when {
                exactWords && count != it -> add("EXACT_WORD_COUNT_UNMET:$count/$it")
                !exactWords && count > it + tolerance -> add("WORD_TARGET_EXCEEDED:$count/$it")
                !exactWords && count < it - tolerance -> add("WORD_TARGET_UNDERSHOT:$count/$it")
            }
        }
        val bulletCount = Regex("(?m)^ {0,3}[-*+]\\s+\\S").findAll(answer).count()
        val numberedCount = Regex("(?m)^ {0,3}[0-9]+[.)]\\s+\\S").findAll(answer).count()
        when (requiredFormat) {
            "BULLETS" -> if (bulletCount == 0) add("BULLET_LIST_REQUIRED")
            "NUMBERED_LIST" -> if (numberedCount == 0) add("NUMBERED_LIST_REQUIRED")
            "TABLE" -> if (!Regex("(?m)^\\s*\\|?.*\\|.*\\n\\s*\\|?\\s*:?-{3,}").containsMatchIn(answer)) add("TABLE_REQUIRED")
        }
        requestedItems?.takeIf { it > 1 }?.let { required ->
            val actual = when (requiredFormat) {
                "BULLETS" -> bulletCount
                "NUMBERED_LIST" -> numberedCount
                else -> maxOf(bulletCount, numberedCount)
            }
            if (actual > 0 && actual != required) add("ITEM_COUNT_UNMET:$actual/$required")
        }
        if (answer.contains(COMBINED_RECOVERY_MARKER)) add("INTERNAL_RECOVERY_MARKER")
        val sentences = CombinedContentCoverage.sentences(answer).map(CombinedContentCoverage::normalized).filter { it.length > 30 }
        if (sentences.size != sentences.distinct().size) add("DUPLICATE_SENTENCES")
    }
}

/**
 * Visible prose v2: substantive headings and inline-code words count. Contractions, hyphenated
 * words and decimal/thousands quantities each count once. Fenced code, URLs, numeric citations,
 * footnote references/definitions and hidden protocol records do not count. Link labels do count.
 */
object CombinedWordCounter {
    const val VERSION = "VISIBLE_PROSE_V2"
    private val word = Regex("[\\p{L}\\p{N}]+(?:[’'\\-‐‑][\\p{L}\\p{N}]+|[.,](?=\\d)[\\p{N}]+)*")
    fun prose(answer: String): String = answer
        .replace(Regex("(?ms)^\\s*(```|~~~)[^\\n]*\\n.*?^\\s*\\1[^\\n]*(?:\\n|$)"), " ")
        .replace(Regex("(?m)^\\s*\\[\\^[^]]+]:.*$"), " ")
        .replace(Regex("\\[(?:[0-9]+(?:\\s*[,–-]\\s*[0-9]+)*|\\^[^]]+)\\](?:\\([^)]*\\))?"), " ")
        .replace(Regex("\\[([^]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://[^\\s<>]+"), " ")
        .replace(Regex("(?m)^\\s*(?:#{1,6}\\s+|[-*+]\\s+|[0-9]+[.)]\\s+)"), "")
    fun count(answer: String): Int = word.findAll(prose(answer)).count()
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

internal fun visibleWordCount(answer: String): Int = CombinedWordCounter.count(answer)

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
