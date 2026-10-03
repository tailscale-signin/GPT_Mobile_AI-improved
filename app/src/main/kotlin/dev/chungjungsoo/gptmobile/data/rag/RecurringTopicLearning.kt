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

    private val aliases = linkedMapOf(
        "artificial intelligence" to listOf("artificial intelligence", "inteligencia artificial", "intelligence artificielle", "人工智能", "人工知能", "ai"),
        "machine learning" to listOf("machine learning", "apprentissage automatique", "aprendizaje automático", "机器学习", "機械学習"),
        "large language models" to listOf("large language models", "large language model", "llms", "llm"),
        "on-device inference" to listOf("on-device inference", "on device inference", "local inference", "offline inference"),
        "vector databases" to listOf("vector database", "vector databases", "vector stores", "vector store"),
        "astrophotography" to listOf("astrophotography", "astro photography", "astrophotographie", "astrofotografía"),
        "software architecture" to listOf("software architecture", "arquitectura de software", "architecture logicielle")
    )
    private val aliasPatterns = aliases.mapValues { (_, variants) -> variants.sortedByDescending { it.length }.map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])") } }
    private val extraStop = "ideas explain explainme tips using learn learning know more best better tell please discuss discussion que los las una para como con por del les des une dans avec pour est sont sur der die das und ist mit von ein eine wie was nicht über".split(' ').toSet()

    internal fun topics(text: String): List<String> {
        var normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val phrases = linkedSetOf<String>()
        aliasPatterns.forEach { (canonical, patterns) ->
            patterns.forEach { pattern ->
                if (pattern.containsMatchIn(normalized)) {
                    phrases += canonical
                    normalized = normalized.replace(pattern, " . ")
                }
            }
        }
        val words = Regex("[\\p{L}][\\p{L}\\p{N}+#-]{1,40}|[.!?;:]").findAll(normalized).map { it.value }.toList()
        val content = words.map { word -> word.takeIf { it.length >= 3 && it !in stop && it !in extraStop && !it.startsWith("http") } }
        content.zipWithNext().mapNotNull { (left, right) -> if (left != null && right != null) "$left $right" else null }.take(6).forEach { phrases += it }
        return (phrases + content.filterNotNull()).take(16)
    }

    fun observe(previous: List<MemoryTopicEvidence>, text: String, chatId: Int, messageId: Int, scope: String, now: Long): List<MemoryTopicEvidence> {
        if (messageId <= 0) return previous
        // Reuse the same source/secret/opt-out checks as fact capture, admitting questions
        // only as topic evidence, with no attempt to store the claim in the question.
        val safe = MemoryLearning.statements(text.replace('?', '.')).joinToString(" ")
        val terms = topics(safe)
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
