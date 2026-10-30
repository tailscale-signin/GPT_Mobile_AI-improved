package dev.chungjungsoo.gptmobile.data.rag

import java.util.Locale

/** Classifies explicit user statements. It does not turn topic words into personal claims. */
internal object MemoryMeaningPolicy {
    private val correction = Regex("(?i)^(?:correction[,:]?|actually[, :]?|to clarify[, :]?|change of plan[, :]?)\\s*")
    private val requirement = Regex("(?i)^(.{2,100}?)\\s+(?:should|must|needs? to|is expected to)\\s+(.+)$")
    private val decision = Regex("(?i)^(?:i|we)\\s+(?:have\\s+)?(?:decided|chose|selected)\\b|^the decision is\\b")
    private val intent = Regex("(?i)^(?:i want|i need|i am trying to|i['’]m trying to|my goal is)\\b")
    private val failure = Regex("(?i)^(.{2,100}?)\\s+(?:is not working|isn't working|does not work|doesn't work|still fails|keeps failing|keeps crashing|is broken)\\b")

    // 'Since' can mean a date; it is deliberately not treated as a causal marker.
    private val reason = Regex("(?i)\\s+(?:because|so that|in order to)\\s+")

    fun extract(text: String): MemoryMeaning? {
        val quote = MemoryLearning.statements(text).singleOrNull() ?: return null
        val isCorrection = correction.containsMatchIn(quote)
        val statement = correction.replaceFirst(quote, "").trim()
        val expected = requirement.matchEntire(statement)
        val failed = failure.find(statement)
        val kind = when {
            decision.containsMatchIn(statement) -> "DECISION"
            expected != null -> "REQUIREMENT"
            intent.containsMatchIn(statement) -> "INTENT"
            failed != null -> "FAILURE"
            else -> return null
        }
        val causal = reason.find(statement)
        val subject = (expected ?: failed)?.groupValues?.get(1)?.trim()
        return MemoryMeaning(
            kind = if (isCorrection) "CORRECTION" else kind,
            intent = (causal?.let { statement.substring(0, it.range.first) } ?: statement).takeIf { kind in setOf("DECISION", "INTENT") },
            rationale = causal?.let { statement.substring(it.range.last + 1).trim().takeIf(String::isNotBlank) },
            expectedBehavior = statement.takeIf { kind == "REQUIREMENT" },
            failure = statement.takeIf { kind == "FAILURE" },
            status = if (isCorrection) "UNRESOLVED_CORRECTION" else "ACTIVE",
            subject = subject,
            evidenceQuote = quote
        )
    }

    fun correctionTargets(incoming: MemoryMeaning?, facts: List<VaultFact>, scope: String, messageId: Int): List<VaultFact> {
        if (incoming?.kind != "CORRECTION" || incoming.subject.isNullOrBlank()) return emptyList()
        if (incoming.subject.lowercase(Locale.ROOT) in setOf("i", "we", "you", "it", "that", "this", "they")) return emptyList()
        fun canonical(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val matches = facts.filter {
            it.scope == scope &&
                it.enabled &&
                !it.awaitingReview &&
                it.supersededBy == null &&
                !it.localOnly &&
                it.fact.relation.relationType == "MEMORY_EPISODE" &&
                it.meaning?.subject?.let { name -> canonical(name) == canonical(incoming.subject) } == true &&
                (messageId <= 0 || it.sourceMessageId < messageId)
        }
        // Multiple requirements for one feature can coexist. Never guess which one was corrected.
        return matches.takeIf { it.size == 1 }.orEmpty()
    }
}
