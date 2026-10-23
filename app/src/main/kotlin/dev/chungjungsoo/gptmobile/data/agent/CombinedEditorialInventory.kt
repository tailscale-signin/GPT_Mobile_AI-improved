package dev.chungjungsoo.gptmobile.data.agent

/** A request-focused editor view. The frozen snapshot retains every original claim and owner. */
internal object CombinedEditorialInventory {
    private val requestNoise = setOf("rank", "ranking", "best", "top", "list", "find", "give", "show", "compare", "please", "write", "answer", "words", "word", "using", "about")
    fun select(records: List<CombinedClaimRecord>, request: String, policy: CombinedAnswerPolicy, expansion: Int = 0): List<CombinedClaimRecord> {
        val groups = mutableListOf<CombinedClaimRecord>()
        records.forEach { claim ->
            val index = groups.indexOfFirst { prior ->
                CombinedHistoricalEvent.sameEvent(prior, claim) ||
                    prior.qualifiers == claim.qualifiers &&
                    CombinedClaimMatcher.match(prior.proposition, claim.proposition) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                    CombinedClaimMatcher.match(claim.proposition, prior.proposition) >= CombinedClaimMatcher.Match.PARAPHRASE
            }
            if (index < 0) {
                groups += claim
            } else {
                val prior = groups[index]
                groups[index] = prior.copy(occurrences = prior.occurrences + claim.occurrences, profileUids = prior.profileUids + claim.profileUids, ancestryIds = prior.ancestryIds + claim.ancestryIds)
            }
        }
        val task = policy.task ?: CombinedTaskContract.from(request)
        val focused = policy.singleExample || policy.maximumWords != null || policy.targetWords != null
        val terms = CombinedClaimMatcher.fingerprint(request).terms - requestNoise
        fun relevance(claim: CombinedClaimRecord): Int {
            val substantive = CombinedHistoryOutline.substantive(claim.proposition)
            val periods = if (task.historicalSurvey) CombinedHistoryOutline.periods(claim.proposition).size else 0
            return (if (substantive) 12 else -12) + periods * 6 +
                CombinedClaimMatcher.fingerprint(claim.proposition).terms.intersect(terms).size * 2 +
                (if (claim.qualifiers.uncertainty.isNotEmpty()) 3 else 0)
        }
        // Contributors are screened at admission. An event need not repeat the country name
        // to belong to that contribution's subject (e.g. "The Bastille fell in 1789").
        // A ranking has a comparison domain. Keep related qualifications without filling
        // spare slots with unrelated domains; historical events use coverage instead.
        val ranking = task.action != "HISTORY" && Regex("(?i)\\b(?:rank|ranking|best|top|recommend)\\b").containsMatchIn(request)
        val aligned = if (ranking) groups.filter { CombinedClaimMatcher.fingerprint(it.proposition).terms.intersect(terms).isNotEmpty() } else emptyList()
        val domainTerms = aligned.flatMap { CombinedClaimMatcher.fingerprint(it.proposition).terms }.toSet() - requestNoise
        val relevant = if (aligned.isEmpty()) groups else groups.filter { it in aligned || CombinedClaimMatcher.fingerprint(it.proposition).terms.intersect(domainTerms).size >= 2 }
        val ranked = relevant.sortedWith(compareByDescending<CombinedClaimRecord> { relevance(it) }.thenBy { it.semanticId })
        val limit = when {
            policy.singleExample -> 12
            focused -> ((policy.targetWords ?: policy.maximumWords ?: 500) / 20).coerceIn(12, 72)
            else -> 72
        }
        val expandedLimit = (limit * (expansion.coerceIn(0, 2) + 1)).coerceAtMost(216)
        val selected = linkedSetOf<CombinedClaimRecord>()
        if (task.historicalSurvey) {
            // Cover every supported period before giving any period a second slot.
            repeat(2) {
                CombinedHistoryOutline.labels.indices.forEach { period ->
                    if (selected.size < expandedLimit) ranked.firstOrNull { it !in selected && CombinedHistoryOutline.substantive(it.proposition) && CombinedHistoryOutline.periods(it.proposition).minOrNull() == period }?.let(selected::add)
                }
            }
        }
        ranked.flatMap { it.profileUids }.distinct().forEach { uid ->
            if (selected.size < expandedLimit) ranked.firstOrNull { uid in it.profileUids && CombinedHistoryOutline.substantive(it.proposition) }?.let(selected::add)
        }
        ranked.forEach { if (selected.size < expandedLimit) selected += it }
        val selectedTerms = selected.map { CombinedClaimMatcher.fingerprint(it.proposition).terms }
        // Qualifications remain attached even when they exceed the discretionary claim target.
        groups.filter { it.qualifiers.uncertainty.isNotEmpty() || it.qualifiers.outcome in setOf("NULL_EFFECT", "INSUFFICIENT_EVIDENCE") }.forEach { caution ->
            val cautionTerms = CombinedClaimMatcher.fingerprint(caution.proposition).terms
            if (selectedTerms.any { it.intersect(cautionTerms).size >= 3 }) selected += caution
        }
        return if (task.historicalSurvey) selected.sortedBy { CombinedHistoryOutline.periods(it.proposition).minOrNull() ?: Int.MAX_VALUE } else selected.toList()
    }
}
