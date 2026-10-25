package dev.chungjungsoo.gptmobile.data.agent

/** Advisory editing allocations. Candidate alignment is never evidence verification. */
internal object CombinedSectionPlan {
    data class Section(val label: String, val targetWords: Int?, val candidateIds: List<String>, val alignment: String)

    fun create(policy: CombinedAnswerPolicy, claims: List<CombinedClaimRecord>): List<Section> {
        val history = policy.task?.historicalSurvey == true
        val oneItem = policy.singleExample || policy.requestedItems == 1
        val labels = when {
            policy.requiredTopics.isNotEmpty() -> policy.requiredTopics
            oneItem -> listOf("One relevant fact with its necessary qualifications")
            policy.requestedItems != null && policy.requestedItems > 1 -> (1..policy.requestedItems).map { "Distinct numbered item $it" }
            history -> CombinedHistoryOutline.labels
            policy.requiresOfficialPrices -> listOf("Exact requested model and API services", "Comparable official prices, currency, token units and dates", "Missing or unverified values")
            else -> listOf("Answer the current question with relevant headings and grouped, nonduplicated details")
        }
        val budget = (policy.targetWords ?: policy.maximumWords ?: policy.minimumWords)?.coerceAtLeast(0)
        return labels.mapIndexed { index, label ->
            val candidates = when {
                history && policy.requiredTopics.isEmpty() && !oneItem && policy.requestedItems == null -> claims.filter { index in CombinedHistoryOutline.periods(it.proposition) }
                policy.requiredTopics.isNotEmpty() -> {
                    val terms = CombinedClaimMatcher.fingerprint(label).terms
                    claims.filter { terms.isNotEmpty() && CombinedClaimMatcher.fingerprint(it.proposition).terms.containsAll(terms) }
                }
                labels.size == 1 -> claims
                else -> emptyList()
            }
            Section(
                label,
                budget?.let { it / labels.size + if (index < it % labels.size) 1 else 0 },
                candidates.map { it.id },
                if (history && policy.requiredTopics.isEmpty() && !oneItem && policy.requestedItems == null) "PERIOD_CANDIDATES_ONLY" else "CANDIDATE_ALIGNMENT_ONLY"
            )
        }
    }
}
