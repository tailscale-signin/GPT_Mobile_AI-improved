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

/** Provider receipt and draw have their own clocks; a progress notice is never model text. */
internal fun linkedInvocationToFirstTextMillis(events: List<RunDiagnosticEvent>): Long? {
    for (text in events.filter { it.kind == RunDiagnosticKind.FIRST_TEXT && it.outputOrigin == OutputOrigin.MODEL_TEXT && it.invocationId != null }) {
        val start = events.firstOrNull { it.kind == RunDiagnosticKind.INVOCATION_STARTED && it.sameInvocation(text) } ?: continue
        elapsed(start, text)?.let { return it }
    }
    return null
}

internal fun linkedInvocationToDrawMillis(events: List<RunDiagnosticEvent>): Long? {
    for (draw in events.filter { it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT && it.measured == true && it.outputOrigin == OutputOrigin.MODEL_TEXT && it.invocationId != null }) {
        val start = events.firstOrNull { it.kind == RunDiagnosticKind.INVOCATION_STARTED && it.sameInvocation(draw) } ?: continue
        val text = events.firstOrNull { it.kind == RunDiagnosticKind.FIRST_TEXT && it.outputOrigin == OutputOrigin.MODEL_TEXT && it.sameInvocation(draw) } ?: continue
        if (elapsed(start, text) != null && elapsed(text, draw) != null) return elapsed(start, draw)
    }
    return null
}

/** FIRST_APPLICATION_REPORT may be an acknowledgement. Finality instead requires the
 * committed bytes for a terminal owner and the same bytes entering its measured draw part. */
internal fun linkedFinalReportToDrawMillis(events: List<RunDiagnosticEvent>): Long? {
    val draws = events.filter {
        it.kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT && it.measured == true && it.outputOrigin == OutputOrigin.APPLICATION_REPORT && it.fence != null && it.outputPartId != null
    }
    for (draw in draws) {
        val terminal = events.firstOrNull { it.kind == RunDiagnosticKind.RUN_TERMINAL && it.sameOwner(draw) } ?: continue
        val committed = events.filter {
            it.kind == RunDiagnosticKind.OUTPUT_CHECKPOINT && it.outputBoundary == OutputTraceBoundary.COMMITTED && it.sameOwner(draw) && elapsed(it, terminal) != null
        }.maxByOrNull { it.monotonicNanos ?: Long.MIN_VALUE } ?: continue
        if (committed.outputOrigin != OutputOrigin.APPLICATION_REPORT || committed.invocationId != draw.invocationId || committed.outputRepresentation != OutputTextRepresentation.VISIBLE_UTF8 || committed.outputFingerprint == null) continue
        if (elapsed(committed, draw) == null) continue
        val input = events.filter {
            it.kind == RunDiagnosticKind.OUTPUT_CHECKPOINT &&
                it.outputBoundary == OutputTraceBoundary.RENDER_INPUT &&
                it.outputOrigin == OutputOrigin.APPLICATION_REPORT &&
                it.sameOwner(draw) &&
                it.invocationId == draw.invocationId &&
                it.outputPartId == draw.outputPartId &&
                elapsed(committed, it) != null &&
                elapsed(it, draw) != null
        }.maxByOrNull { it.monotonicNanos ?: Long.MIN_VALUE } ?: continue
        if (input.outputFingerprint != committed.outputFingerprint || input.outputFingerprint?.utf8Bytes == 0L) continue
        return elapsed(committed, draw)
    }
    return null
}

private fun RunDiagnosticEvent.sameInvocation(other: RunDiagnosticEvent) = sameProcess(other) && invocationId != null && invocationId == other.invocationId
private fun RunDiagnosticEvent.sameOwner(other: RunDiagnosticEvent) = sameProcess(other) && fence != null && fence == other.fence
private fun RunDiagnosticEvent.sameProcess(other: RunDiagnosticEvent) = runId == other.runId && processSession == other.processSession
private fun elapsed(start: RunDiagnosticEvent, end: RunDiagnosticEvent): Long? {
    if (!start.sameProcess(end)) return null
    val first = start.monotonicNanos ?: return null
    val last = end.monotonicNanos ?: return null
    if (last < first) return null
    return runCatching { Math.subtractExact(last, first) / 1_000_000 }.getOrNull()
}
