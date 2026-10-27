package dev.chungjungsoo.gptmobile.data.agent

/** Advisory editing allocations. Candidate alignment is never evidence verification. */
internal object CombinedSectionPlan {
    data class Section(val label: String, val targetWords: Int?, val candidateIds: List<String>, val alignment: String)

    fun create(policy: CombinedAnswerPolicy, claims: List<CombinedClaimRecord>): List<Section> {
        val history = policy.task?.historicalSurvey == true
        val oneItem = policy.singleExample || policy.requestedItems == 1
        val topics = if (policy.requiredTopics.isEmpty() && !oneItem && policy.requestedItems == null && !history && !policy.requiresOfficialPrices) topicGroups(claims) else emptyMap()
        val labels = when {
            policy.requiredTopics.isNotEmpty() -> policy.requiredTopics
            oneItem -> listOf("One relevant fact with its necessary qualifications")
            policy.requestedItems != null && policy.requestedItems > 1 -> (1..policy.requestedItems).map { "Distinct numbered item $it" }
            history -> CombinedHistoryOutline.labels
            policy.requiresOfficialPrices -> listOf("Exact requested model and API services", "Comparable official prices, currency, token units and dates", "Missing or unverified values")
            else -> topics.keys.toList().ifEmpty { listOf("Answer the current question with relevant headings and grouped, nonduplicated details") }
        }
        val budget = (policy.targetWords ?: policy.maximumWords ?: policy.minimumWords)?.coerceAtLeast(0)
        val sections = labels.mapIndexed { index, label ->
            val candidates = when {
                history && policy.requiredTopics.isEmpty() && !oneItem && policy.requestedItems == null -> claims.filter { index in CombinedHistoryOutline.periods(it.proposition) }
                policy.requiredTopics.isNotEmpty() -> {
                    val terms = CombinedClaimMatcher.fingerprint(label).terms
                    claims.filter { terms.isNotEmpty() && CombinedClaimMatcher.fingerprint(it.proposition).terms.containsAll(terms) }
                }
                topics.isNotEmpty() -> topics[label].orEmpty()
                labels.size == 1 -> claims
                else -> emptyList()
            }
            Section(
                label,
                null,
                candidates.map { it.id },
                when {
                    history && policy.requiredTopics.isEmpty() && !oneItem && policy.requestedItems == null -> "PERIOD_CANDIDATES_ONLY"
                    topics.isNotEmpty() -> "TOPIC_CANDIDATES_REQUIRING_WRITER_REVIEW"
                    else -> "CANDIDATE_ALIGNMENT_ONLY"
                }
            )
        }
        val allocations = budget?.let { CombinedSectionBudget.allocate(it, sections.map { section -> section.candidateIds.distinct().size }) }
        return sections.mapIndexed { index, section -> section.copy(targetWords = allocations?.get(index)) }
    }

    /** A local, editable plan gives every candidate a destination without another model call. */
    private fun topicGroups(claims: List<CombinedClaimRecord>): Map<String, List<CombinedClaimRecord>> {
        val groups = linkedMapOf<String, MutableList<CombinedClaimRecord>>()
        val pending = mutableListOf<CombinedClaimRecord>()
        val safety = Regex("(?i)\\b(?:safety|unsafe|hazards?|side effects?|adverse effects?|flammab\\w*|explosiv\\w*|contraindicat\\w*|supervised|ventilat\\w*)\\b")
        val limits = Regex("(?i)\\b(?:evidence (?:is|remains) (?:limited|weak|mixed|inconsistent)|large.*human.*(?:limited|needed)|not (?:established|proven|a replacement)|more research|further (?:research|studies)|most robust evidence|uncertain|unverified|conflicting (?:results|evidence))\\b")
        val label = Regex("^\\s*(?:[-*+] |[0-9]+[.)] )?\\*\\*([^*\\n]{2,80})\\*\\*\\s*:?")
        fun canonicalLabel(proposed: String): String {
            val terms = CombinedClaimMatcher.fingerprint(proposed).terms
            return groups.keys.firstOrNull { existing ->
                val other = CombinedClaimMatcher.fingerprint(existing).terms
                terms.isNotEmpty() && other.isNotEmpty() && terms.intersect(other).size.toDouble() / maxOf(terms.size, other.size) >= 0.75
            } ?: proposed
        }
        claims.forEach { claim ->
            val topic = when {
                safety.containsMatchIn(claim.proposition) -> "Safety, risks and conditions of use"
                limits.containsMatchIn(claim.proposition) -> "Evidence limitations and relevant disagreements"
                else -> label.find(claim.proposition)?.groupValues?.get(1)?.trim()?.trimEnd(':')
            }
            if (topic.isNullOrBlank()) pending += claim else groups.getOrPut(canonicalLabel(topic)) { mutableListOf() } += claim
        }
        // Ignore ubiquitous subject words when assigning an unlabelled sentence to
        // a topic. A bare shared subject must not move a minority caution to an
        // unrelated benefit or treat a new outcome as established support.
        val prints = claims.associate { it.id to CombinedClaimMatcher.fingerprint(it.proposition).terms }
        val frequency = prints.values.flatten().groupingBy { it }.eachCount()
        fun distinctive(claim: CombinedClaimRecord) = prints.getValue(claim.id).filterTo(linkedSetOf()) { frequency.getValue(it) <= maxOf(2, claims.size / 2) }
        pending.forEach { claim ->
            val terms = distinctive(claim)
            val best = groups.entries.map { (topic, candidates) ->
                val shared = candidates.maxOfOrNull { other -> terms.intersect(distinctive(other)).size } ?: 0
                topic to shared
            }.maxByOrNull { it.second }?.takeIf { it.second >= 2 && it.second.toDouble() / terms.size.coerceAtLeast(1) >= 0.35 }?.first
            groups.getOrPut(best ?: "Overview and other relevant details") { mutableListOf() } += claim
        }
        if (groups.size <= 12) return groups
        val retained = groups.entries.take(11).associateTo(linkedMapOf()) { it.key to it.value.toMutableList() }
        retained.getOrPut("Other relevant details") { mutableListOf() }.addAll(groups.entries.drop(11).flatMap { it.value })
        return retained
    }
}
