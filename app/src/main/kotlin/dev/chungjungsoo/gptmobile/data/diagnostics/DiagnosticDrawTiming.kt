package dev.chungjungsoo.gptmobile.data.diagnostics

/** A run can include several providers and verification rounds. Never infer output lineage
 * from the first/latest invocation or compare monotonic values from different processes. */
internal fun linkedParsedToDrawMillis(events: List<RunDiagnosticEvent>): Long? {
    val drawn = events.firstOrNull { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT && it.measured == true && it.outputOrigin == OutputOrigin.MODEL_TEXT && it.invocationId != null } ?: return null
    val invocation = drawn.invocationId ?: return null
    val started = events.firstOrNull {
        it.kind == RunDiagnosticKind.INVOCATION_STARTED && it.invocationId == invocation && it.runId == drawn.runId && it.processSession == drawn.processSession
    }?.monotonicNanos ?: return null
    val parsed = events.firstOrNull {
        it.kind == RunDiagnosticKind.FIRST_TEXT && it.outputOrigin == OutputOrigin.MODEL_TEXT && it.invocationId == invocation && it.runId == drawn.runId && it.processSession == drawn.processSession
    } ?: return null
    val first = parsed.monotonicNanos ?: return null
    val last = drawn.monotonicNanos ?: return null
    if (last < first || first < started) return null
    return runCatching { Math.subtractExact(last, first) / 1_000_000 }.getOrNull()
}
