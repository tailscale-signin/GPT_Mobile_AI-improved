package dev.chungjungsoo.gptmobile.data.rag

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class MeaningEvaluationReport(
    val version: Int = 1,
    val queries: Int,
    val correct: Int,
    val precision: Double,
    val recall: Double,
    val abstentionsCorrect: Int,
    val abstentionQueries: Int,
    val policyViolations: Int,
    val semanticQueries: Int,
    val p50Millis: Double,
    val p95Millis: Double,
    val promptTokens: Int
)

/** Fixed fictional corpus. No vault, projects, user messages, or persistent indexes are accessed. */
object MemoryGoldenEvaluation {
    internal data class Case(val query: String, val facts: List<VaultFact>, val expected: Set<String>, val scope: String = "project:fixture")

    internal fun cases(): List<Case> {
        fun fact(id: String, text: String) = VaultFact(id, MemoryLearning.observation(text), scope = "project:fixture", meaning = MemoryLearning.meaning(text))
        val decision = fact("decision", "I decided to use SQLite because the app must work offline")
        val requirement = fact("requirement", "Combined Mode should produce one unified answer without repeating contributions")
        val astronomy = fact("astronomy", "I enjoy astronomy and observing the night sky")
        val coffee = fact("coffee", "I prefer coffee")
        val old = fact("old", "The toolbar should appear at the top").copy(enabled = false, supersededBy = "new")
        val latest = fact("new", "Actually, the toolbar should appear at the bottom").copy(meaning = MemoryLearning.meaning("Actually, the toolbar should appear at the bottom")?.copy(status = "ACTIVE", correctsRecordIds = setOf("old")))
        return listOf(
            Case("Why did I choose SQLite?", listOf(decision, coffee), setOf("decision")),
            Case("What motivated the database choice?", listOf(decision, coffee), setOf("decision")),
            Case("Why is the multi-model response stitching individual answers together?", listOf(requirement, coffee), setOf("requirement")),
            Case("What should Combined Mode do?", listOf(requirement, coffee), setOf("requirement")),
            Case("Observing celestial objects", listOf(astronomy, coffee), setOf("astronomy")),
            Case("Where should the toolbar appear now?", listOf(old, latest), setOf("new")),
            Case("How do I bake sourdough bread?", listOf(decision, astronomy, coffee), emptySet()),
            Case("Why did the Roman empire fall?", listOf(decision), emptySet()),
            Case("Coffee preference", listOf(coffee.copy(scope = "project:other")), emptySet()),
            Case("Coffee preference", listOf(coffee.copy(localOnly = true)), emptySet()),
            Case("Coffee preference", listOf(coffee.copy(awaitingReview = true)), emptySet()),
            Case("Coffee preference", listOf(coffee.copy(validUntilMillis = 1)), emptySet()),
            Case("Coffee preference", listOf(coffee.copy(enabled = false)), emptySet()),
            Case("Coffee preference", emptyList(), emptySet())
        )
    }

    suspend fun run(score: (suspend (String, List<VaultFact>) -> Map<String, Double>?)? = null): MeaningEvaluationReport {
        var correct = 0
        var truePositive = 0
        var selectedCount = 0
        var expectedCount = 0
        var abstentions = 0
        var violations = 0
        var semanticQueries = 0
        var tokens = 0
        val corpus = cases()
        val times = corpus.map { case ->
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            val candidates = case.facts.filter { it.scope == case.scope && MemoryEligibility.accepts(it, FactVaultSettings(), 1, 0, false, false, 1000) }
            val scores = score?.invoke(case.query, candidates)
            if (scores != null) semanticQueries++
            val selected = MemoryRecallPolicy.select(case.query, candidates, FactVaultSettings(), now = 1000, semanticScores = scores.orEmpty())
            val ids = selected.map { it.id }.toSet()
            if (ids == case.expected) correct++
            if (case.expected.isEmpty() && ids.isEmpty()) abstentions++
            violations += selected.count { it !in candidates }
            truePositive += ids.intersect(case.expected).size
            selectedCount += ids.size
            expectedCount += case.expected.size
            tokens += dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate.estimate(FactRecall(selected).prefix())
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return MeaningEvaluationReport(
            queries = corpus.size, correct = correct,
            precision = if (selectedCount == 0) 0.0 else truePositive.toDouble() / selectedCount,
            recall = truePositive.toDouble() / expectedCount.coerceAtLeast(1),
            abstentionsCorrect = abstentions, abstentionQueries = corpus.count { it.expected.isEmpty() },
            policyViolations = violations, semanticQueries = semanticQueries,
            p50Millis = times[times.size / 2], p95Millis = times[(times.size * 0.95).toInt().coerceAtMost(times.lastIndex)], promptTokens = tokens
        )
    }
}
