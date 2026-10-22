package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.Serializable

/** Deterministic task anchors, not a factual-accuracy or general semantic classifier. */
@Serializable
data class CombinedTaskContract(
    val request: String,
    val action: String,
    val subjects: List<String>,
    val scope: String,
    val relationship: String = "NEW_TASK",
    val referencedRequest: String? = null,
    val subjectConfidence: String = "EXPLICIT"
) {
    val historicalSurvey: Boolean get() = action == "HISTORY" && scope == "SURVEY"
    val factual: Boolean get() = action !in setOf("CREATIVE", "CONVERSATION")

    fun subjectMatches(text: String): Boolean = subjects.isEmpty() ||
        subjects.all { subject ->
            val alternatives = aliases[subject.lowercase()] ?: listOf(subject)
            alternatives.any { alias ->
                val terms = tokens(alias)
                terms.isNotEmpty() && tokens(text).containsAll(terms)
            }
        }

    fun violations(answer: String): List<String> {
        if (subjects.isEmpty() || subjectConfidence != "EXPLICIT" || answer.isBlank()) return emptyList()
        // A heading alone cannot establish that the body answered the task.
        val prose = answer.lines().filterNot { it.trimStart().startsWith('#') }.joinToString("\n")
        if (!subjectMatches(prose)) return listOf("TASK_SUBJECT_MISMATCH")
        return emptyList()
    }

    fun instruction(): String = "Current task: $request\nAction: $action; subjects: ${subjects.joinToString()}; scope: $scope; relationship: $relationship." +
        referencedRequest?.let { "\nExplicit reference resolves to: $it. An interrupted prior answer remains part of that prior task." }.orEmpty()

    companion object {
        private val aliases = mapOf(
            "germany" to listOf("Germany", "German", "Deutschland"),
            "france" to listOf("France", "French"),
            "portugal" to listOf("Portugal", "Portuguese")
        )
        private val reference = Regex("(?i)\\b(?:it|them|those|previous|above)\\b|\\b(?:compare|explain|summarize|rewrite|shorten)\\s+(?:this|that)\\b")
        private val followUpVerb = Regex("(?i)^\\s*(?:continue|expand|shorten|rewrite|elaborate|summarize)\\b")
        private fun referencesPrior(text: String): Boolean = reference.containsMatchIn(text) ||
            followUpVerb.containsMatchIn(text) &&
            aliases.keys.none { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
        private val noise = setOf("a", "an", "the", "of", "on", "in", "and", "me", "my", "please", "give", "write", "tell", "explain", "describe", "history", "essay", "word", "words", "about", "what", "is", "are", "how", "why", "does", "do", "can", "you")
        internal fun tokens(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filterNot { it in noise }.toSet()

        fun from(request: String, previousRequest: String? = null): CombinedTaskContract {
            val text = request.trim()
            val following = previousRequest != null && referencesPrior(text)
            val action = when {
                Regex("(?i)\\b(?:history|historical)\\b").containsMatchIn(text) -> "HISTORY"
                Regex("(?i)\\b(?:compare|comparison|versus)\\b").containsMatchIn(text) -> "COMPARE"
                Regex("(?i)\\b(?:poem|fiction|fictional|imagine|lyrics|creative)\\b").containsMatchIn(text) -> "CREATIVE"
                Regex("(?i)\\b(?:implement|debug|code|fix)\\b").containsMatchIn(text) -> "IMPLEMENT"
                text.lowercase().trimEnd('.', '!', '?') in setOf("hi", "hello", "hey", "test", "testing", "thanks") -> "CONVERSATION"
                else -> "ANSWER"
            }
            val explicit = Regex("(?i)\\b(?:history of|history about|essay (?:on|about)|explain|describe|tell me about|what (?:is|are))\\s+(.+?)(?:[.!?]|$)")
                .find(text)?.groupValues?.get(1)?.replace(Regex("(?i)\\s+(?:in|using|with)\\s+(?:exactly |about )?\\d+.*$"), "")?.trim()
            val prior = if (following) from(requireNotNull(previousRequest)) else null
            val named = aliases.keys.filter { subject -> Regex("(?i)\\b${Regex.escape(subject)}\\b").containsMatchIn(text) }
            val subjectText = explicit?.takeUnless { reference.containsMatchIn(it) || tokens(it).size > 6 || Regex("(?i)\\b(?:why|how|whether|should|would|could)\\b").containsMatchIn(it) }
            val directSubjects = named.ifEmpty { subjectText?.let { listOf(it) }.orEmpty() }
            val subjects = (directSubjects + prior?.subjects.orEmpty().takeIf { directSubjects.isEmpty() || action == "COMPARE" }.orEmpty()).distinct()
            val inferred = tokens(text).filterNot { it.toIntOrNull() != null }.take(8)
            val narrow = Regex("(?i)\\b(?:one|single|fact|event|during|between|from|until|century|centuries|ancient|medieval|modern)\\b|\\b[12][0-9]{3}\\b").containsMatchIn(text)
            return CombinedTaskContract(
                request = text,
                action = if (following && action == "ANSWER") prior?.action ?: action else action,
                subjects = subjects.ifEmpty { if (action == "CONVERSATION") emptyList() else inferred },
                scope = if (narrow) "FOCUSED" else prior?.scope ?: "SURVEY",
                relationship = if (following) "FOLLOW_UP" else "NEW_TASK",
                referencedRequest = previousRequest.takeIf { following },
                subjectConfidence = if (named.isNotEmpty() || prior?.subjectConfidence == "EXPLICIT" || subjectText != null && (action == "HISTORY" || Regex("(?i)\\bessay (?:on|about)\\b").containsMatchIn(text))) "EXPLICIT" else "LEXICAL_HINT_ONLY"
            )
        }

        /** New tasks never inherit an interrupted writer's continuation context. */
        fun relevantHistoryIndices(request: String, previousRequests: List<String>): List<Int> {
            if (previousRequests.isEmpty() || !referencesPrior(request)) return emptyList()
            val result = mutableListOf(previousRequests.lastIndex)
            var index = previousRequests.lastIndex
            while (index > 0 && referencesPrior(previousRequests[index])) {
                result += --index
            }
            return result.sorted()
        }
    }
}

/** Coverage buckets are inferred from the retained material, never invented as factual claims. */
internal object CombinedHistoryOutline {
    val labels = listOf("Origins and early societies", "Medieval developments", "Early modern transformation", "Nineteenth century", "Twentieth century", "Recent history and present")
    private val cues = listOf(
        Regex("(?i)\\b(?:ancient|prehistoric|roman|rome|gaul|celtic|lusitan|germanic|antiquity|bce|bc)\\b"),
        Regex("(?i)\\b(?:medieval|middle ages|feudal|charlemagne|carolingian|reconquista|crusad|capetian)\\w*\\b"),
        Regex("(?i)\\b(?:renaissance|reformation|enlightenment|napoleon|revolution|exploration|colonial|maritime)\\w*\\b"),
        Regex("(?i)\\b(?:industrial|bismarck|unification|nineteenth)\\w*\\b"),
        Regex("(?i)\\b(?:world war|weimar|nazi|holocaust|cold war|salazar|carnation|twentieth)\\w*\\b"),
        Regex("(?i)\\b(?:reunification|european union|eurozone|contemporary|twenty.first|present|today|recent|modern democracy)\\b")
    )
    fun periods(text: String): Set<Int> = buildSet {
        cues.forEachIndexed { index, cue -> if (cue.containsMatchIn(text)) add(index) }
        Regex("\\b([12][0-9]{3})\\b").findAll(text).forEach { match ->
            val year = match.value.toInt()
            add(
                when {
                    year < 1500 -> 1
                    year < 1800 -> 2
                    year < 1900 -> 3
                    year < 1990 -> 4
                    else -> 5
                }
            )
        }
    }
    fun substantive(text: String): Boolean = CombinedWordCounter.count(text) >= 6 &&
        !Regex("(?i)^(?:#+\\s*)?(?:introduction|conclusion|in conclusion|in summary|the history of .{1,40} is (?:rich|complex|fascinating))\\b").containsMatchIn(text.trim())
}
