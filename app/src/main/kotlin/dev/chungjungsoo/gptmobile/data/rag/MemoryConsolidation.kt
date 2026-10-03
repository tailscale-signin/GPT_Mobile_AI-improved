package dev.chungjungsoo.gptmobile.data.rag

/** Similarity alone cannot distinguish “I like X” from “I dislike X”. */
internal object MemoryConsolidation {
    fun canMerge(existing: VaultFact, incoming: KnowledgeFact, scope: String, similarity: Double): Boolean {
        if (similarity < 0.96 || !existing.enabled || existing.supersededBy != null || existing.scope != scope) return false
        if (existing.fact.entity.id != incoming.entity.id || existing.fact.relation.relationType != incoming.relation.relationType) return false
        fun words(value: String) = Regex("[\\p{L}\\p{N}]+").findAll(value.lowercase(java.util.Locale.ROOT)).map { it.value }.toSet()
        val old = words(existing.fact.target.name)
        val new = words(incoming.target.name)
        val qualifiers = setOf("no", "not", "never", "without", "except", "only", "sometimes", "always", "before", "after")
        if (old.intersect(qualifiers) != new.intersect(qualifiers)) return false
        if (old.filter { it.any(Char::isDigit) }.toSet() != new.filter { it.any(Char::isDigit) }.toSet()) return false
        return old.intersect(new).size.toDouble() / old.union(new).size.coerceAtLeast(1) >= 0.75
    }
}
