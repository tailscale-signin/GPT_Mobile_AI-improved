package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder

/** One immutable extraction shared by prompt, decoding, coverage and local assessment. */
internal data class CombinedContentIndex(val records: List<CombinedClaimRecord>) {
    val fingerprints by lazy { records.associate { it.proposition to CombinedClaimMatcher.fingerprint(it.proposition) } }
}

internal object CombinedContentIndexes {
    private data class SourceKey(val uid: String, val body: String, val attempt: String?, val createdAt: Long?, val ancestry: Set<String>)
    private val entries = LinkedHashMap<List<SourceKey>, CombinedContentIndex>(4, 0.75f, true)

    fun get(sources: List<CombinedModelResponse>): CombinedContentIndex {
        val key = sources.map { SourceKey(it.platformUid, it.content, it.attemptId, it.createdAt, it.ancestryIds.toSet()) }
        synchronized(entries) { entries[key]?.let { return it } }
        val started = System.nanoTime()
        val result = CombinedContentIndex(java.util.Collections.unmodifiableList(CombinedClaimSemantics.extractRecords(sources)))
        // Oversized requests remain usable, but cannot evict the process heap with a cache.
        if (key.sumOf { it.body.length.toLong() } <= 262_144) {
            synchronized(entries) {
                entries[key]?.let { return it }
                entries[key] = result
                while (entries.size > 2) entries.remove(entries.keys.first())
            }
        }
        AppLogRecorder.record("CombinedSynthesis", "CONTENT_INDEX_PREPARED · sources=${sources.size} · claims=${result.records.size} · durationMs=${(System.nanoTime() - started) / 1_000_000}")
        return result
    }

    fun fingerprints(records: List<CombinedClaimRecord>): Map<String, CombinedClaimMatcher.Fingerprint> {
        val cached = synchronized(entries) { entries.values.firstOrNull { it.records === records } }
        return cached?.fingerprints ?: records.associate { it.proposition to CombinedClaimMatcher.fingerprint(it.proposition) }
    }

    internal fun clear() = synchronized(entries) { entries.clear() }
}

internal inline fun <T> measureCombinedPreparation(stage: String, block: () -> T): T {
    val started = System.nanoTime()
    try {
        return block()
    } finally {
        AppLogRecorder.record("CombinedSynthesis", "PREPARATION_TIMING · stage=$stage · durationMs=${(System.nanoTime() - started) / 1_000_000}")
    }
}
