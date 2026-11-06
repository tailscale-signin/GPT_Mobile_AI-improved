package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.TreeMap
import kotlinx.serialization.Serializable

@Serializable
internal data class RunJournalCheckpoint(val runId: String, val streamId: String, val highWatermark: Long, val capturedAtMillis: Long)

@Serializable
internal data class RunJournalMissingRange(val streamId: String, val firstSequence: Long, val lastSequence: Long)

@Serializable
internal data class RunJournalExportCoverage(
    val capturedAtMillis: Long,
    val coverageScope: String = "all_retained_batches_at_frozen_boundary",
    val eventCount: Long = 0,
    val highWatermarks: Map<String, Long> = emptyMap(),
    val missingSequences: Long = 0,
    val missingBatches: Int = 0,
    val unreadableCheckpoints: Int = 0,
    val unknownTailStreams: Int = 0,
    val storageFailed: Boolean = false,
    val captureEnabled: Boolean = false,
    val privacySuppressed: Boolean = false,
    val missingRanges: List<RunJournalMissingRange> = emptyList(),
    val missingRangesTruncated: Boolean = false,
    val retentionPrunedBatches: Long = 0,
    val counterScope: String = "retention_counter_process_session; coverage_selected_run",
    val ordering: String = "Sort within streamId by sequence; capturedAtMillis and eventId correlate cross-source observations.",
    val sourceCompleteness: String = "Retained lifecycle metadata; unknown legacy tails, queue losses and expired history remain explicit. Provider wire payloads are not captured."
)

/** Compact interval tracking avoids retaining event payloads or one hash-set entry per event. */
internal class RunJournalSequenceCoverage {
    private val streams = linkedMapOf<String, TreeMap<Long, Long>>()

    fun add(stream: String, sequence: Long): Boolean {
        require(sequence > 0)
        val ranges = streams.getOrPut(stream) { TreeMap() }
        val previous = ranges.floorEntry(sequence)
        if (previous != null && previous.value >= sequence) return false
        val next = ranges.ceilingEntry(sequence)
        val joinsPrevious = previous != null && previous.value == sequence - 1
        val joinsNext = next != null && sequence != Long.MAX_VALUE && next.key == sequence + 1
        val start = if (joinsPrevious) previous.key else sequence
        val end = if (joinsNext) next.value else sequence
        if (joinsNext) ranges.remove(next.key)
        ranges[start] = end
        return true
    }

    fun count(stream: String): Long = streams[stream]?.entries?.sumOf { it.value - it.key + 1 } ?: 0L

    fun gaps(highWatermarks: Map<String, Long>, limit: Int): Pair<List<RunJournalMissingRange>, Boolean> {
        require(limit > 0)
        val gaps = mutableListOf<RunJournalMissingRange>()
        var truncated = false
        fun add(stream: String, first: Long, last: Long) {
            if (first > last) return
            if (gaps.size < limit) gaps += RunJournalMissingRange(stream, first, last) else truncated = true
        }
        for ((stream, high) in highWatermarks) {
            var expected = 1L
            for ((first, last) in streams[stream].orEmpty()) {
                if (first > high) break
                add(stream, expected, first - 1)
                expected = if (last == Long.MAX_VALUE) Long.MAX_VALUE else last + 1
            }
            if (expected <= high && streams[stream]?.floorEntry(high)?.value != Long.MAX_VALUE) add(stream, expected, high)
        }
        return gaps to truncated
    }
}
