package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** Routine deferral is an inventory summary. Required-tool failures retain their warning receipts. */
internal object ToolCatalogDiagnostics {
    private data class Seen(val fingerprint: String, val repetitions: Int)
    private val seen = linkedMapOf<String, Seen>()

    @Synchronized
    fun recordOmissions(stage: String, omissions: Map<String, String>, schemaAllowance: Long? = null) {
        if (omissions.isEmpty()) {
            seen.remove(stage)
            return
        }
        val summary = summarizeOmissions(stage, omissions, schemaAllowance)
        val fingerprint = researchHash(summary)
        val prior = seen[stage]
        val repetitions = if (prior?.fingerprint == fingerprint) prior.repetitions + 1 else 0
        if (seen.size >= 16 && stage !in seen) seen.remove(seen.keys.first())
        seen[stage] = Seen(fingerprint, repetitions)
        if (repetitions == 0 || repetitions % 32 == 0) {
            AppLogRecorder.record("ToolCatalog", "$summary · repeatedRounds=$repetitions")
        }
    }

    internal fun summarizeOmissions(stage: String, omissions: Map<String, String>, schemaAllowance: Long? = null): String {
        val ordered = omissions.toSortedMap()
        val counts = ordered.values.groupingBy { it }.eachCount().toSortedMap()
        val sample = ordered.keys.take(8).map { it.take(96) }
        val digest = researchHash(ordered.entries.joinToString("\n") { "${it.key}:${it.value}" })
        return "TOOL_OMISSIONS · stage=$stage · omitted=${ordered.size} · reasons=$counts · sampleNames=$sample · detailNamesSampled=${ordered.size > sample.size} · inventoryDigest=$digest · schemaAllowance=${schemaAllowance ?: "not measured"}"
    }
}
