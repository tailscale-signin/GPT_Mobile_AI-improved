package dev.chungjungsoo.gptmobile.data.agent

/** Narrow event anchors reduce duplicate slots and false disputes; they do not verify facts. */
internal object CombinedHistoricalEvent {
    private val actions = listOf(
        "MEMBERSHIP" to Regex("(?i)\\b(?:joined|entered|accession|membership)\\b"),
        "ACCESSION" to Regex("(?i)\\b(?:seiz(?:e|ed|ing) power|coup|first consul)\\b"),
        "CORONATION" to Regex("(?i)\\b(?:emperor|coronat\\w*|crowned)\\b"),
        "CONQUEST" to Regex("(?i)\\b(?:conquer\\w*|conquest|annex\\w*)\\b"),
        "FOUNDATION" to Regex("(?i)\\b(?:establish\\w*|found\\w*|proclaim\\w*|creat\\w*)\\b"),
        "SPONSORSHIP" to Regex("(?i)\\b(?:sponsor\\w*|fund\\w*|patron\\w*)\\b"),
        "SAILING" to Regex("(?i)\\b(?:sailor|sailed|sailing|seafarer)\\b")
    )
    fun actions(text: String): Set<String> = actions.filter { it.second.containsMatchIn(text) }.mapTo(linkedSetOf()) { it.first }
    fun distinctActions(first: String, second: String): Boolean {
        val a = actions(first)
        val b = actions(second)
        return a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()
    }
    fun sameEvent(
        first: CombinedClaimRecord,
        second: CombinedClaimRecord,
        a: CombinedClaimMatcher.Fingerprint = CombinedClaimMatcher.fingerprint(first.proposition),
        b: CombinedClaimMatcher.Fingerprint = CombinedClaimMatcher.fingerprint(second.proposition)
    ): Boolean {
        val action = a.eventActions.intersect(b.eventActions)
        return action.isNotEmpty() &&
            a.numbers.isNotEmpty() &&
            a.numbers == b.numbers &&
            first.qualifiers == second.qualifiers &&
            a.negative == b.negative &&
            a.hedged == b.hedged &&
            a.terms.intersect(b.terms).size >= 3 &&
            a.terms.intersect(b.terms).size.toDouble() / minOf(a.terms.size, b.terms.size) >= 0.6
    }
}
