package dev.chungjungsoo.gptmobile.data.memory

/** Counts reflect committed vault mutations, never model-proposed observations. */
data class MemoryEnrichmentCommit(val written: Int = 0, val updated: Int = 0, val accepted: Int = 0) {
    val changed: Boolean get() = written > 0 || updated > 0
}
