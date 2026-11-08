package dev.chungjungsoo.gptmobile.data.diagnostics

/** Preserve the runs actually sampled in a window even if they finish before its report. */
internal class FrameRunCoverage(private val maximumRuns: Int = 16) {
    private val sampled = linkedMapOf<String, String>()
    var truncated: Boolean = false
        private set

    fun observe(phases: Map<String, String>) {
        phases.forEach { (id, phase) ->
            if (id in sampled || sampled.size < maximumRuns) {
                sampled[id] = phase
            } else {
                truncated = true
            }
        }
    }

    fun snapshot(): Map<String, String> = sampled.toMap()

    fun clear() {
        sampled.clear()
        truncated = false
    }
}
