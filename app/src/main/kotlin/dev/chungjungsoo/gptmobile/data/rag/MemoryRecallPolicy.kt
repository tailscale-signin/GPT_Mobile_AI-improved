package dev.chungjungsoo.gptmobile.data.rag

import java.util.Locale
import kotlin.math.ln

/** Bounded lexical retrieval with related vocabulary, relation intent and conversation continuity. */
internal object MemoryRecallPolicy {
    private val stop = setOf("the", "and", "that", "this", "with", "have", "what", "how", "for", "are", "was", "can", "you", "your", "please", "help", "user", "about", "from", "would", "could", "tell")

    // Generic task words cannot make an unrelated personal fact relevant by themselves.
    private val generic = setOf("answer", "response", "reply", "question", "topic", "project", "work", "working", "help", "information", "history", "research", "explain", "write", "create", "make", "use", "using", "thing", "time")
    private val groups = listOf(
        setOf("concise", "brief", "short", "succinct", "brevity"),
        setOf("reply", "response", "answer", "replies"),
        setOf("code", "coding", "programming", "developer"),
        setOf("phone", "smartphone", "mobile", "android", "iphone"),
        setOf("computer", "laptop", "workstation", "desktop"),
        setOf("food", "diet", "eat", "meal", "vegetarian", "vegan", "allergic", "allergy"),
        setOf("location", "city", "live", "based", "where"),
        setOf("job", "occupation", "profession", "career", "work"),
        setOf("project", "building", "developing", "working"),
        setOf("preference", "prefer", "favorite", "like"),
        setOf("unified", "synthesize", "synthesis", "integrate", "merge", "combine", "combined", "stitch", "stitching", "concatenate", "aggregate"),
        setOf("repeat", "repeating", "duplicate", "duplicated", "redundant", "repetition"),
        setOf("model", "models", "multi-model", "ai", "assistant", "profile")
    )
    private fun tokens(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+", RegexOption.IGNORE_CASE).findAll(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        .map { it.value }.filter { (it.length > 2 || it == "ai") && it !in stop }.map { if (it.endsWith("s") && it.length > 4) it.dropLast(1) else it }.toSet()
    private fun expanded(text: String, document: Boolean = false): Set<String> {
        val terms = tokens(text + " " + RecurringTopicLearning.topics(text).joinToString(" ")).toMutableSet()
        if (document && terms.any { it in setOf("kotlin", "python", "typescript", "javascript", "java", "rust") }) terms += "programming"
        return terms + groups.filter { group -> group.any { it in terms } }.flatMap { tokens(it.joinToString(" ")) }
    }
    fun isFollowUp(query: String): Boolean = tokens(query).size <= 10 && Regex("(?i)\\b(?:it|that|those|them|continue|earlier|previous|again|why)\\b").containsMatchIn(query)

    fun requestsHistory(query: String): Boolean = Regex("(?i)\\b(?:previous|earlier|history|used to|what changed|why did .+ change)\\b").containsMatchIn(query)

    /** One selection path for typing previews and dispatched requests. Privacy filtering stays upstream. */
    fun select(query: String, facts: List<VaultFact>, settings: FactVaultSettings, previousContext: String = "", now: Long = System.currentTimeMillis(), semanticScores: Map<String, Double> = emptyMap()): List<VaultFact> {
        val policy = settings.normalized()
        val history = requestsHistory(query)
        val eligible = facts.filter { (it.enabled || history && it.supersededBy != null) && !it.awaitingReview && (history || it.supersededBy == null) && (it.validFromMillis == null || it.validFromMillis <= now) && (it.validUntilMillis == null || it.validUntilMillis > now) }
        val ranked = rank(query, eligible, previousContext, policy.rankByRecency, policy.rankByFrequency, now, semanticScores)
        val neighbors = if (policy.meaningAwareMemory) MemoryGraphRecall.expand(ranked.take(3), eligible).filterNot { neighbor -> ranked.any { it.id == neighbor.id } } else emptyList()
        val prioritized = (if (policy.alwaysRecallPinned) ranked.sortedByDescending { it.pinned } else ranked) + neighbors
        val selected = mutableListOf<VaultFact>()
        val seen = hashSetOf<List<String>>()
        fun canonical(text: String) = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[\\p{Z}\\s]+"), " ").trim()
        for (fact in prioritized) {
            if (selected.size >= policy.maxRecall) break
            // Preserve quantities, negations, relation, scope and entity; only duplicate wording is collapsed.
            val key = listOf(fact.scope, fact.fact.entity.name, fact.fact.relation.relationType, fact.fact.target.name).map { canonical(it) }
            if (key in seen) continue
            if (dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate.estimate(FactRecall(selected + fact).prefix()) <= policy.recallTokens) {
                selected += fact
                seen += key
            }
        }
        return selected
    }

    fun rank(query: String, facts: List<VaultFact>, previousContext: String = "", useRecency: Boolean = true, useFrequency: Boolean = true, now: Long = System.currentTimeMillis(), semanticScores: Map<String, Double> = emptyMap()): List<VaultFact> {
        val queryTerms = tokens(query)
        val asksWhy = queryTerms.any { it in setOf("why", "reason", "rationale", "motivation", "because") }
        val asksExpected = queryTerms.any { it in setOf("expect", "expected", "requirement", "supposed", "should") }
        val asksFailure = queryTerms.any { it in setOf("fail", "failed", "failure", "broken", "bug", "issue", "problem", "wrong") }
        val asksCorrection = queryTerms.any { it in setOf("correction", "corrected", "changed", "change", "updated", "instead") }
        val broad = Regex("(?i)\\b(?:remember|know|saved|facts|profile)\\b.*\\b(?:me|my|about me)\\b|\\b(?:my memories|my preferences)\\b").containsMatchIn(query)
        val personal = Regex("(?i)\\b(?:I|me|my|mine)\\b").containsMatchIn(query)
        val expandedQuery = expanded(query)
        val continuity = if (isFollowUp(query)) expanded(previousContext.takeLast(2000)) else emptySet()
        val corpus = facts.associateWith { fact ->
            val meaning = fact.meaning
            tokens(
                listOfNotNull(
                    fact.fact.entity.name,
                    fact.fact.target.name,
                    meaning?.intent,
                    meaning?.rationale,
                    meaning?.expectedBehavior,
                    meaning?.failure
                ).joinToString(" ")
            )
        }
        val documentFrequency = corpus.values.flatten().groupingBy { it }.eachCount()
        return facts.map { fact ->
            val terms = corpus.getValue(fact)
            val exact = queryTerms.intersect(terms).filterNot { it in generic }.sumOf { term -> ln(1.0 + facts.size.toDouble() / (1 + documentFrequency.getOrDefault(term, 0))) * 4 }
            val specificSynonym = groups.any { group -> group.any { it in queryTerms && it !in generic } && group.any { it in terms && it !in generic } }
            val relatedMatches = expandedQuery.intersect(expanded(fact.fact.target.name, document = true)).filterNot { it in generic }.size
            val relation = fact.fact.relation.relationType
            val intent = if (!personal) {
                0.0
            } else {
                when {
                    relation in setOf("PREFERS", "AVOIDS", "RESPONSE_LANGUAGE") && queryTerms.any { it in setOf("prefer", "preference", "like", "favorite") } -> 4.0
                    relation == "LOCATED_IN" && queryTerms.any { it in setOf("live", "where", "location", "city") } -> 5.0
                    relation == "NAMED" && "name" in queryTerms -> 5.0
                    relation == "OCCUPATION" && queryTerms.any { it in setOf("job", "work", "occupation", "profession") } -> 5.0
                    relation == "WORKING_ON" && queryTerms.any { it in setOf("project", "working", "building", "learning") } -> 4.0
                    relation == "GOAL" && queryTerms.any { it in setOf("goal", "trying", "plan", "planning", "objective") } -> 4.0
                    relation in setOf("OWNS", "USES") &&
                        queryTerms.any { it in setOf("phone", "computer", "laptop", "device", "tool", "tools", "use", "uses", "own", "owns") } -> 4.0
                    else -> 0.0
                }
            }
            val followUp = if (continuity.isNotEmpty()) continuity.intersect(terms).filterNot { it in generic }.size.coerceAtMost(3) * 0.6 else 0.0
            val broadProfile = if (broad && fact.fact.entity.id == "user") 3.0 else 0.0
            val meaning = fact.meaning
            val similarity = semanticScores[fact.id] ?: 0.0
            val semantic = if (similarity >= 0.55) (similarity - 0.45) * 10.0 else 0.0
            val meaningAnchor = exact > 0.0 || specificSynonym || followUp > 0.0 || semantic > 0.0
            val meaningIntent = if (!meaningAnchor || meaning == null) {
                0.0
            } else {
                when {
                    asksWhy && !meaning.rationale.isNullOrBlank() -> 3.5
                    asksExpected && meaning.kind == "REQUIREMENT" -> 3.0
                    asksFailure && meaning.kind in setOf("FAILURE", "REQUIREMENT", "CORRECTION") -> 2.5
                    asksCorrection && meaning.kind == "CORRECTION" -> 3.0
                    else -> 0.0
                }
            }
            // Synonyms boost an anchored match; a generic topic expansion alone is not evidence.
            val related = if (exact > 0.0 || intent > 0.0 || followUp > 0.0 || semantic > 0.0 || specificSynonym) relatedMatches.coerceAtMost(3) * 0.35 else 0.0
            val relevance = exact + related + intent + followUp + broadProfile + meaningIntent + semantic
            val ageDays = ((now - fact.lastSeenMillis).coerceAtLeast(0) / 86_400_000.0)
            val recency = if (useRecency) 0.6 / (1.0 + ageDays / 30.0) else 0.0
            val frequency = if (useFrequency) ln(1.0 + fact.occurrences.coerceAtLeast(1)) * 0.4 else 0.0
            // Boost only relevant facts; repetition cannot force unrelated context into a prompt.
            val score = if (relevance > 0.0) relevance + recency + frequency + fact.confidence * 0.2 else 0.0
            fact to score
        }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<VaultFact, Double>> { it.second }.thenByDescending { it.first.pinned }.thenByDescending { it.first.savedAtMillis })
            .map { it.first }
    }
}
