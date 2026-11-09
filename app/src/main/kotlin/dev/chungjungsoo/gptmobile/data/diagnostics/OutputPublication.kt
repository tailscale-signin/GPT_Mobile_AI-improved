package dev.chungjungsoo.gptmobile.data.diagnostics

/** Different semantic milestones; publication is not evidence that a frame was drawn. */
enum class OutputPublicationKind { FIRST_PROGRESS, FIRST_VISIBLE_ANSWER, FIRST_SYSTEM_ERROR, FIRST_APPLICATION_REPORT, FIRST_RETAINED_CONTENT, FIRST_UNATTRIBUTED_OUTPUT }

data class FirstOutputPublication(val kind: OutputPublicationKind, val lineage: OutputLineage, val characters: Int)

/** Retain identity only when every contributing fragment has the same explicit owner. */
internal class OutputLineageAccumulator {
    private var observed: OutputLineage? = null
    val lineage: OutputLineage get() = observed ?: OutputLineage()

    fun append(value: OutputLineage) {
        observed = observed?.let { if (it == value) it else OutputLineage() } ?: value
    }

    fun reset() {
        observed = null
    }
}

/** A bounded set of first publications, independent of diagnostics being enabled or hashes arriving. */
internal class OutputPublicationTracker {
    private val seen = mutableSetOf<OutputPublicationKind>()
    private val pending = mutableListOf<FirstOutputPublication>()

    fun observe(lineage: OutputLineage, characters: Int) {
        if (characters <= 0) return
        val kind = when (lineage.origin) {
            OutputOrigin.MODEL_TEXT -> OutputPublicationKind.FIRST_VISIBLE_ANSWER
            OutputOrigin.SYSTEM_ERROR -> OutputPublicationKind.FIRST_SYSTEM_ERROR
            OutputOrigin.PROGRESS_NOTICE -> OutputPublicationKind.FIRST_PROGRESS
            OutputOrigin.APPLICATION_REPORT -> OutputPublicationKind.FIRST_APPLICATION_REPORT
            OutputOrigin.RETAINED_CONTENT -> OutputPublicationKind.FIRST_RETAINED_CONTENT
            OutputOrigin.UNKNOWN -> OutputPublicationKind.FIRST_UNATTRIBUTED_OUTPUT
        }
        if (seen.add(kind)) pending += FirstOutputPublication(kind, lineage, characters)
    }

    fun drain(kind: OutputPublicationKind? = null): List<FirstOutputPublication> = pending.filter { kind == null || it.kind == kind }.also { pending.removeAll(it.toSet()) }
}
