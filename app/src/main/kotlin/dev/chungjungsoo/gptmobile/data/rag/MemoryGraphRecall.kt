package dev.chungjungsoo.gptmobile.data.rag

import java.util.Locale

/** One-hop expansion over authorized candidates only; never traverses the User hub. */
internal object MemoryGraphRecall {
    fun expand(anchors: List<VaultFact>, candidates: List<VaultFact>, limit: Int = 8): List<VaultFact> {
        fun nodes(fact: VaultFact): Set<String> = buildSet {
            fun addNode(value: String) {
                val node = value.trim().lowercase(Locale.ROOT)
                if (node.length in 3..100 && node !in setOf("user", "person", "fact", "entity", "observation", "it", "this", "that", "the app")) add(node)
            }
            if (fact.fact.entity.id != "user") addNode(fact.fact.entity.name)
            if (fact.fact.target.type !in setOf("FACT", "OBSERVATION", "EPISODE", "PREFERENCE")) addNode(fact.fact.target.name)
            fact.meaning?.subject?.let(::addNode)
        }
        val anchorNodes = anchors.associateWith(::nodes)
        val ids = anchors.map { it.id }.toSet()
        return candidates.asSequence().filter { candidate ->
            candidate.id !in ids &&
                candidate.enabled &&
                !candidate.awaitingReview &&
                candidate.supersededBy == null &&
                anchors.any { anchor ->
                    anchor.scope == candidate.scope &&
                        (
                            anchorNodes.getValue(anchor).intersect(nodes(candidate)).isNotEmpty() ||
                                anchor.id in candidate.meaning?.correctsRecordIds.orEmpty() ||
                                candidate.id in anchor.meaning?.correctsRecordIds.orEmpty()
                            )
                }
        }.sortedByDescending { it.lastSeenMillis }.take(limit.coerceIn(0, 8)).toList()
    }
}
