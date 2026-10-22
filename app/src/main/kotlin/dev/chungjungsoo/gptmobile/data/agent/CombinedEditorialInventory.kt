package dev.chungjungsoo.gptmobile.data.agent

/** A request-focused editor view. The frozen snapshot retains every original claim and owner. */
internal object CombinedEditorialInventory {
    private val requestNoise = setOf("rank", "ranking", "best", "top", "list", "find", "give", "show", "compare", "please", "write", "answer", "words", "word", "using", "about")
    fun select(records: List<CombinedClaimRecord>, request: String, policy: CombinedAnswerPolicy): List<CombinedClaimRecord> {
        val groups = mutableListOf<CombinedClaimRecord>()
        records.forEach { claim ->
            val index = groups.indexOfFirst { prior ->
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
        val focused = policy.singleExample || policy.maximumWords != null || policy.targetWords != null || Regex("(?i)\\b(?:rank(?:ed|ing)?|top \\d+|best|compare)\\b").containsMatchIn(request)
        if (!focused) return groups
        val terms = CombinedClaimMatcher.fingerprint(request).terms - requestNoise
        // A generic request such as "Compare evidence" cannot identify an irrelevant topic.
        if (terms.size < 2) return groups
        fun relevance(claim: CombinedClaimRecord) = CombinedClaimMatcher.fingerprint(claim.proposition).terms.intersect(terms).size
        val relevant = groups.filter { relevance(it) >= minOf(2, terms.size.coerceAtLeast(1)) }.ifEmpty { groups }
        val limit = if (policy.singleExample) 24 else 96
        val ranked = relevant.sortedWith(compareByDescending<CombinedClaimRecord> { relevance(it) }.thenBy { it.semanticId })
        val selected = linkedSetOf<CombinedClaimRecord>()
        // Include eligible information from each contributor, without treating model count as evidence.
        ranked.flatMap { it.profileUids }.distinct().forEach { uid -> ranked.firstOrNull { uid in it.profileUids }?.let(selected::add) }
        ranked.forEach { if (selected.size < limit) selected += it }
        val selectedTerms = selected.map { CombinedClaimMatcher.fingerprint(it.proposition).terms }
        // Do not remove an applicable minority caution merely because the view is compact.
        relevant.filter { it.qualifiers.uncertainty.isNotEmpty() || it.qualifiers.outcome in setOf("NULL_EFFECT", "INSUFFICIENT_EVIDENCE") }.forEach { caution ->
            val cautionTerms = CombinedClaimMatcher.fingerprint(caution.proposition).terms
            if (selectedTerms.any { it.intersect(cautionTerms).size >= 3 }) selected += caution
        }
        return selected.toList()
    }
}
