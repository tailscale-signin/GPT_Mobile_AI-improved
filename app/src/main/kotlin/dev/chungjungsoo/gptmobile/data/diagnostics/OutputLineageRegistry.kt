package dev.chungjungsoo.gptmobile.data.diagnostics

/** Exact UTF-8 identity can carry provenance across representations; similarity cannot.
 * No text is retained and no first/latest invocation is guessed. Guard with the journal lock. */
internal class OutputLineageRegistry(private val capacity: Int = 1024) {
    init {
        require(capacity > 0)
    }

    private data class Key(val runId: String, val fingerprint: OutputFingerprint)
    private val receipts = linkedMapOf<Key, OutputLineage>()
    private val incompleteRuns = mutableSetOf<String>()

    fun resolve(runId: String, fingerprint: OutputFingerprint, explicit: OutputLineage): OutputLineage {
        val key = Key(runId, fingerprint)
        if (explicit.origin == OutputOrigin.UNKNOWN) {
            // An explicit invocation without an origin is still not proof of model-authored text.
            return if (explicit.invocationId != null || runId in incompleteRuns) explicit else receipts[key] ?: explicit
        }
        val previous = receipts[key]
        val merged = when {
            previous == null -> explicit
            previous.origin != explicit.origin -> OutputLineage()
            previous.invocationId != explicit.invocationId -> OutputLineage(explicit.origin)
            else -> previous
        }
        receipts[key] = merged
        if (receipts.size > capacity) {
            val removed = receipts.keys.first()
            receipts.remove(removed)
            // Forgetting an earlier identical response must not make a later provider look unique.
            incompleteRuns += removed.runId
        }
        // The caller's explicit ownership describes this boundary even if another invocation
        // emitted identical bytes. Only later inferred boundaries use the merged ambiguity.
        return explicit
    }

    fun removeRun(runId: String) {
        receipts.keys.removeAll { it.runId == runId }
        incompleteRuns.remove(runId)
    }

    fun clear() {
        receipts.clear()
        incompleteRuns.clear()
    }
}
