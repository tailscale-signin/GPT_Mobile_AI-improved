package dev.chungjungsoo.gptmobile.data.benchmark

/** A peak and its stored observation always come from the same capture. */
internal class BenchmarkResourceRecorder(private val capture: (String) -> BenchmarkResourceSample) {
    private val observations = mutableListOf<BenchmarkResourceSample>()
    fun observe(scope: String): BenchmarkResourceSample = capture(scope).also { synchronized(observations) { observations += it } }
    fun snapshots(): List<BenchmarkResourceSample> = synchronized(observations) { observations.toList() }
    val peakClientPssKb: Long? get() = snapshots().maxOfOrNull { it.processPssKb }
    val peakExecutionWorkerPssBytes: Long? get() = snapshots().filter { it.scope in setOf("execution", "before_cleanup") }.mapNotNull { it.workerPssBytes }.maxOrNull()
    val peakExecutionClientPssKb: Long? get() = snapshots().filter { it.scope in setOf("execution", "before_cleanup") }.maxOfOrNull { it.processPssKb }
}
