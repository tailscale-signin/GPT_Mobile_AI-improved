package dev.chungjungsoo.gptmobile.data.agent

import kotlin.math.ceil

/** A word goal belongs to the final writer, never to a bounded evidence worker. */
internal object LongResponsePolicy {
    private val wordGoal = Regex("(?i)(?<![\\p{L}\\d])([1-9]\\d{0,5}(?:[,\\u00a0 ]\\d{3})*(?:\\.\\d+)?\\s*[kK]?)\\s*[-–]?\\s*(?:words?|mots)\\b")
    private val word = Regex("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")

    fun requestedWords(prompt: String): Int? = wordGoal.findAll(prompt).mapNotNull { match ->
        val prefix = prompt.substring(0, match.range.first).takeLast(40)
        if (Regex("(?i)(?:at most|no more than|up to|under|fewer than|less than|maximum(?: of)?|max[.:]?)\\s*$").containsMatchIn(prefix)) return@mapNotNull null
        val raw = match.groupValues[1].replace(Regex("[,\\s\\u00a0]"), "")
        val value = raw.removeSuffix("k").removeSuffix("K").toDoubleOrNull() ?: return@mapNotNull null
        (value * if (raw.endsWith("k", true)) 1000 else 1).toInt().takeIf { it in 200..100_000 }
    }.lastOrNull()

    fun countWords(text: String): Int = word.findAll(text).count()

    fun continuationLimit(words: Int?, requestTokens: Int?): Int = if (words == null) {
        1
    } else {
        // Conservative allowance for multilingual text and reasoning, without changing a user's per-request cap.
        ceil(words * 2.2 / (requestTokens?.takeIf { it > 0 } ?: 1024)).toInt().coerceIn(2, 16)
    }

    fun needsMore(text: String, words: Int?): Boolean = words != null && countWords(text) < words * 9 / 10

    fun evidenceTask(task: String): String {
        val words = requestedWords(task) ?: return task
        return "Prepare evidence for the final writer's approximately $words-word answer. " +
            "This worker returns a bounded evidence brief, not the full essay. Cover the requested scope with an ordered outline, " +
            "distinct facts, dates, causes and consequences, sources and explicit gaps. For history, order eras from earliest to latest. " +
            "The final writer owns the word count and prose expansion.\n\nOriginal user request:\n$task"
    }
}
