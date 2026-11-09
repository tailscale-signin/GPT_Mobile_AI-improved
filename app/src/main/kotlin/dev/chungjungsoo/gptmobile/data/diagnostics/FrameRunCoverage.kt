package dev.chungjungsoo.gptmobile.data.diagnostics

/** Preserve the runs actually sampled in a window even if they finish before its report. */
internal class FrameRunCoverage(private val maximumRuns: Int = 16) {
    private val sampled = linkedMapOf<String, String>()
    private var captureEpoch: Long? = null
    var truncated: Boolean = false
        private set

    fun observe(phases: Map<String, String>, epoch: Long? = 0L) {
        align(epoch)
        if (epoch == null) return
        phases.forEach { (id, phase) ->
            if (id in sampled || sampled.size < maximumRuns) {
                sampled[id] = phase
            } else {
                truncated = true
            }
        }
    }

    fun snapshot(epoch: Long? = 0L): Map<String, String> {
        align(epoch)
        return sampled.toMap()
    }

    private fun align(epoch: Long?) {
        if (epoch != captureEpoch) {
            clear()
            captureEpoch = epoch
        }
    }

    fun clear() {
        sampled.clear()
        truncated = false
    }
}

/** Phase owners keep their capture epoch; a private or stale producer cannot join a later window. */
internal class FrameRunPhases(private val maximumRuns: Int = 16) {
    private data class Phase(val name: String, val epoch: Long)
    private val phases = linkedMapOf<String, Phase>()

    @Synchronized
    fun observe(runId: String, phase: String, epoch: Long?) {
        if (epoch == null) return
        phases.entries.removeAll { it.value.epoch < epoch }
        val id = runId.take(128)
        if (phases[id]?.epoch?.let { it > epoch } == true) return
        if (id in phases || phases.size < maximumRuns) phases[id] = Phase(phase.take(32), epoch)
    }

    @Synchronized
    fun finished(runId: String) {
        phases.remove(runId.take(128))
    }

    @Synchronized
    fun snapshot(epoch: Long?): Map<String, String> = phases.filterValues { epoch != null && it.epoch == epoch }.mapValues { it.value.name }

    @Synchronized
    fun clear() = phases.clear()
}
