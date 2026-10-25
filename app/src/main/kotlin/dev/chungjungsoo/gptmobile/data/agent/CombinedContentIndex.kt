package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace

/** One immutable extraction shared by prompt, decoding, coverage and local assessment. */
internal data class CombinedContentIndex(val records: List<CombinedClaimRecord>) {
    val fingerprints by lazy { records.associate { it.proposition to CombinedClaimMatcher.fingerprint(it.proposition) } }
    val byId by lazy { records.flatMap { claim -> (claim.candidateIds + claim.id + claim.canonicalId).map { it to claim } }.toMap() }
}

internal object CombinedContentIndexes {
    private data class SourceKey(val uid: String, val digest: String, val attempt: String?, val createdAt: Long?, val ancestry: Set<String>, val extraction: Int, val completeness: String)
    private val entries = LinkedHashMap<List<SourceKey>, CombinedContentIndex>(4, 0.75f, true)

    private data class SourceEntry(val records: List<CombinedClaimRecord>, val characters: Int)
    private val sourceEntries = LinkedHashMap<SourceKey, SourceEntry>(64, 0.75f, true)
    private var cachedCharacters = 0L

    fun get(sources: List<CombinedModelResponse>): CombinedContentIndex {
        val key = sources.map { SourceKey(it.platformUid, dev.chungjungsoo.gptmobile.data.research.researchHash(it.content), it.attemptId, it.createdAt, it.ancestryIds.toSet(), it.claimExtractionVersion, it.contentCompleteness) }
        synchronized(entries) { entries[key]?.let { return it } }
        val started = System.nanoTime()
        var hits = 0
        val candidates = sources.flatMapIndexed { index, source ->
            val sourceKey = key[index]
            val cached = synchronized(entries) { sourceEntries[sourceKey] }
            val records = cached?.records ?: CombinedOperationTrace.measure("source_extraction", CombinedOperationOwner(digest = sourceKey.digest, profileUid = source.platformUid)) {
                CombinedClaimSemantics.extractRecords(listOf(source))
            }.also { extracted ->
                if (source.content.length <= 262_144) {
                    synchronized(entries) {
                        if (sourceKey !in sourceEntries) {
                            sourceEntries[sourceKey] = SourceEntry(extracted, source.content.length)
                            cachedCharacters += source.content.length
                            while (sourceEntries.size > 64 || cachedCharacters > 1_048_576L) {
                                cachedCharacters -= sourceEntries.remove(sourceEntries.keys.first())!!.characters
                            }
                        }
                    }
                }
            }
            if (cached != null) hits++
            fun remap(id: String) = if (id.startsWith("C1.")) "C${index + 1}." + id.substringAfter('.') else id
            records.map { record -> record.copy(id = remap(record.id), occurrences = record.occurrences.map { it.copy(candidateId = remap(it.candidateId)) }) }
        }
        val merged = candidates.groupBy { it.semanticId }.values.map { copies ->
            copies.first().copy(occurrences = copies.flatMap { it.occurrences }, profileUids = copies.flatMapTo(linkedSetOf()) { it.profileUids }, ancestryIds = copies.flatMapTo(linkedSetOf()) { it.ancestryIds })
        }
        val result = CombinedContentIndex(java.util.Collections.unmodifiableList(merged))
        // Oversized requests remain usable, but cannot evict the process heap with a cache.
        if (sources.sumOf { it.content.length.toLong() } <= 262_144) {
            synchronized(entries) {
                entries[key]?.let { return it }
                entries[key] = result
                while (entries.size > 2) entries.remove(entries.keys.first())
            }
        }
        val elapsed = System.nanoTime() - started
        AppLogRecorder.record("CombinedSynthesis", "CONTENT_INDEX_PREPARED · thread=${Thread.currentThread().name} · cacheHits=$hits · cacheMisses=${sources.size - hits} · sources=${sources.size} · claims=${result.records.size} · durationNs=$elapsed · durationMs=${elapsed / 1_000_000}")
        return result
    }

    fun fingerprints(records: List<CombinedClaimRecord>): Map<String, CombinedClaimMatcher.Fingerprint> {
        val cached = synchronized(entries) { entries.values.firstOrNull { it.records === records } }
        return cached?.fingerprints ?: records.associate { it.proposition to CombinedClaimMatcher.fingerprint(it.proposition) }
    }

    internal fun clear() = synchronized(entries) {
        entries.clear()
        sourceEntries.clear()
        cachedCharacters = 0
    }
}

internal inline fun <T> measureCombinedPreparation(stage: String, block: () -> T): T {
    val started = System.nanoTime()
    try {
        return block()
    } finally {
        val elapsed = System.nanoTime() - started
        AppLogRecorder.record("CombinedSynthesis", "PREPARATION_TIMING · stage=$stage · durationNs=$elapsed · durationMs=${elapsed / 1_000_000}")
    }
}
