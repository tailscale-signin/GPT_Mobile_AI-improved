package dev.chungjungsoo.gptmobile.data.benchmark

/** Typed views select protected rows; the Store retains their original canonical payload bytes. */
internal data class BenchmarkRestoreState(
    val runs: List<BenchmarkRun> = emptyList(),
    val batches: List<BenchmarkBatch> = emptyList(),
    val attempts: List<BenchmarkAttempt> = emptyList(),
    val events: List<BenchmarkBatchEvent> = emptyList()
)

internal data class BenchmarkRestoreOwnership(val runIds: Set<String>, val batchIds: Set<String>)

/** A pending request owns its entire accepted batch, including not-yet-started sibling run IDs. */
internal fun benchmarkRestoreOwnership(canonical: BenchmarkRestoreState): BenchmarkRestoreOwnership =
    benchmarkLinkedOwnership(
        canonical.copy(attempts = emptyList(), events = emptyList()),
        canonical.runs.filter { run -> run.samples.any { it.engineeringCleanupPending() } }.map { it.id }.toSet(),
        emptySet()
    )

/** A backup cannot retire local work or introduce another owner for any retained run/attempt. */
internal fun benchmarkMergeRestoreOwnership(
    canonical: BenchmarkRestoreState,
    incoming: BenchmarkRestoreState,
    ownership: BenchmarkRestoreOwnership = benchmarkRestoreOwnership(canonical)
): BenchmarkRestoreState {
    if (ownership.runIds.isEmpty()) return incoming
    val retainedRuns = canonical.runs.filter { it.id in ownership.runIds }
    val retainedBatches = canonical.batches.filter { it.id in ownership.batchIds }
    val retainedAttempts = canonical.attempts.filter { it.batchId in ownership.batchIds }
    val retainedEvents = canonical.events.filter { it.batchId in ownership.batchIds }
    val attemptIds = retainedAttempts.map { it.id }.toSet()
    val conflictingRuns = incoming.runs.filter { run -> run.samples.any { it.attemptId in attemptIds } }.map { it.id }
    val conflictingBatches = incoming.attempts.filter { it.id in attemptIds }.map { it.batchId } +
        incoming.events.filter { it.attemptId in attemptIds }.map { it.batchId }
    // Drop the complete incoming component when a foreign batch aliases an original owner.
    // Keeping its other rows would leave a queue capable of referencing retained local work.
    val excluded = benchmarkLinkedOwnership(incoming, ownership.runIds + conflictingRuns, ownership.batchIds + conflictingBatches)
    return BenchmarkRestoreState(
        runs = (incoming.runs.filterNot { it.id in excluded.runIds } + retainedRuns).sortedByDescending { it.startedAt },
        batches = (incoming.batches.filterNot { it.id in excluded.batchIds } + retainedBatches).sortedByDescending { it.createdAt },
        attempts = incoming.attempts.filterNot { it.batchId in excluded.batchIds || it.runId in excluded.runIds || it.id in attemptIds } + retainedAttempts,
        events = incoming.events.filterNot { it.batchId in excluded.batchIds || it.runId in excluded.runIds || it.attemptId in attemptIds } + retainedEvents
    )
}

/** Linear traversal keeps restoration bounded even for long chains of conflicting imported IDs. */
private fun benchmarkLinkedOwnership(state: BenchmarkRestoreState, runSeeds: Set<String>, batchSeeds: Set<String>): BenchmarkRestoreOwnership {
    val runBatches = mutableMapOf<String, MutableSet<String>>()
    val batchRuns = mutableMapOf<String, MutableSet<String>>()
    fun link(runId: String?, batchId: String?) {
        if (runId == null || batchId == null) return
        runBatches.getOrPut(runId) { mutableSetOf() }.add(batchId)
        batchRuns.getOrPut(batchId) { mutableSetOf() }.add(runId)
    }
    state.runs.forEach { link(it.id, it.batchId) }
    state.batches.forEach { batch -> batch.items.forEach { link(it.runId, batch.id) } }
    state.attempts.forEach { link(it.runId, it.batchId) }
    state.events.forEach { link(it.runId, it.batchId) }
    val runs = runSeeds.toMutableSet()
    val batches = batchSeeds.toMutableSet()
    val pendingRuns = ArrayDeque(runSeeds)
    val pendingBatches = ArrayDeque(batchSeeds)
    while (pendingRuns.isNotEmpty() || pendingBatches.isNotEmpty()) {
        while (pendingRuns.isNotEmpty()) {
            runBatches[pendingRuns.removeFirst()].orEmpty().forEach { if (batches.add(it)) pendingBatches.addLast(it) }
        }
        while (pendingBatches.isNotEmpty()) {
            batchRuns[pendingBatches.removeFirst()].orEmpty().forEach { if (runs.add(it)) pendingRuns.addLast(it) }
        }
    }
    return BenchmarkRestoreOwnership(runs, batches)
}
