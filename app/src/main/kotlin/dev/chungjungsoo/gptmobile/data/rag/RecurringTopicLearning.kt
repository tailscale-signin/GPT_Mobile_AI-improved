package dev.chungjungsoo.gptmobile.data.rag

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class MemoryTopicEvidence(
    val label: String,
    val scope: String,
    val messageKeys: Set<String>,
    val lastSeenMillis: Long
)

/** Records an interest signal, never treats a question or pasted source as a personal fact. */
internal object RecurringTopicLearning {
    private val stop = ("about after again also always another answer app before being could create current does doing each ensure every feature from give have help here into just know like make more most much need only please question really response same should some that them then there these they this those through turn update user using very want what when where which while will with work would your you how the and for can are was has had not all its but our out now new get let".split(' ')).toSet()

    fun observe(previous: List<MemoryTopicEvidence>, text: String, chatId: Int, messageId: Int, scope: String, now: Long): List<MemoryTopicEvidence> {
        if (messageId <= 0) return previous
        // Reuse the same source/secret/opt-out checks as fact capture, admitting questions
        // only as topic evidence, with no attempt to store the claim in the question.
        val safe = MemoryLearning.statements(text.replace('?', '.')).joinToString(" ")
        val terms = Regex("[\\p{L}][\\p{L}\\p{N}+#.-]{3,40}").findAll(safe)
            .map { it.value.trimEnd('.').lowercase(Locale.ROOT) }
            .filter { it !in stop && !it.startsWith("http") }.distinct().take(16).toList()
        if (terms.isEmpty()) return previous
        val key = "$chatId:$messageId"
        val map = previous.associateBy { "${it.scope}:${it.label}" }.toMutableMap()
        for (term in terms) {
            val id = "$scope:$term"
            val old = map[id]
            if (key in old?.messageKeys.orEmpty()) continue
            map[id] = MemoryTopicEvidence(term, scope, (old?.messageKeys.orEmpty() + key).takeLastBounded(16), now)
        }
        return map.values.sortedByDescending { it.lastSeenMillis }.take(512)
    }

    private fun Set<String>.takeLastBounded(limit: Int): Set<String> = toList().takeLast(limit).toSet()
}
