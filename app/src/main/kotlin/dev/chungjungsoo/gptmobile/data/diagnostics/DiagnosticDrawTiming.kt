package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.TreeMap

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
    val starts = firstInvocationEvents(events, RunDiagnosticKind.INVOCATION_STARTED)
    for (text in events) {
        if (text.kind != RunDiagnosticKind.FIRST_TEXT || text.outputOrigin != OutputOrigin.MODEL_TEXT) continue
        val key = text.invocationKey() ?: continue
        val start = starts[key] ?: continue
        elapsed(start, text)?.let { return it }
    }
    return null
}

internal fun linkedInvocationToDrawMillis(events: List<RunDiagnosticEvent>): Long? {
    val starts = firstInvocationEvents(events, RunDiagnosticKind.INVOCATION_STARTED)
    val texts = firstInvocationEvents(events, RunDiagnosticKind.FIRST_TEXT, OutputOrigin.MODEL_TEXT)
    for (draw in events) {
        if (draw.kind != RunDiagnosticKind.FIRST_VISIBLE_TEXT || draw.measured != true || draw.outputOrigin != OutputOrigin.MODEL_TEXT) continue
        val key = draw.invocationKey() ?: continue
        val start = starts[key] ?: continue
        val text = texts[key] ?: continue
        if (elapsed(start, text) != null && elapsed(text, draw) != null) return elapsed(start, draw)
    }
    return null
}

/** FIRST_APPLICATION_REPORT may be an acknowledgement. Finality instead requires the
 * committed bytes for a terminal owner and the same bytes entering its measured draw part. */
internal fun linkedFinalReportToDrawMillis(events: List<RunDiagnosticEvent>): Long? {
    val terminals = mutableMapOf<OwnerKey, RunDiagnosticEvent>()
    val inputs = mutableMapOf<RenderPartKey, TreeMap<Long, RunDiagnosticEvent>>()
    for (event in events) {
        if (event.kind == RunDiagnosticKind.RUN_TERMINAL) {
            val owner = event.ownerKey() ?: continue
            terminals.putIfAbsent(owner, event)
        } else if (event.kind == RunDiagnosticKind.OUTPUT_CHECKPOINT && event.outputBoundary == OutputTraceBoundary.RENDER_INPUT && event.outputOrigin == OutputOrigin.APPLICATION_REPORT) {
            val owner = event.ownerKey() ?: continue
            val part = event.outputPartId ?: continue
            val nanos = event.monotonicNanos ?: continue
            // Histories need not be chronological. Keep the first event for equal timestamps.
            inputs.getOrPut(RenderPartKey(owner, event.invocationId, part)) { TreeMap() }.putIfAbsent(nanos, event)
        }
    }

    val commits = mutableMapOf<OwnerKey, RunDiagnosticEvent>()
    for (event in events) {
        if (event.kind != RunDiagnosticKind.OUTPUT_CHECKPOINT || event.outputBoundary != OutputTraceBoundary.COMMITTED) continue
        val owner = event.ownerKey() ?: continue
        val terminal = terminals[owner] ?: continue
        val nanos = event.monotonicNanos ?: continue
        if (elapsed(event, terminal) == null) continue
        val previous = commits[owner]?.monotonicNanos
        // Select the final commit before validating its origin or bytes; never fall back to an older report.
        if (previous == null || nanos > previous) commits[owner] = event
    }

    for (draw in events) {
        if (draw.kind != RunDiagnosticKind.FIRST_VISIBLE_TEXT || draw.measured != true || draw.outputOrigin != OutputOrigin.APPLICATION_REPORT) continue
        val owner = draw.ownerKey() ?: continue
        val part = draw.outputPartId ?: continue
        val nanos = draw.monotonicNanos ?: continue
        val committed = commits[owner] ?: continue
        if (committed.outputOrigin != OutputOrigin.APPLICATION_REPORT || committed.invocationId != draw.invocationId || committed.outputRepresentation != OutputTextRepresentation.VISIBLE_UTF8 || committed.outputFingerprint == null) continue
        val duration = elapsed(committed, draw) ?: continue
        val input = inputs[RenderPartKey(owner, draw.invocationId, part)]?.floorEntry(nanos)?.value ?: continue
        // Once commit-to-draw is valid, any eligible input lies inside that non-overflowing interval.
        if (elapsed(committed, input) == null || elapsed(input, draw) == null) continue
        if (input.outputFingerprint != committed.outputFingerprint || input.outputFingerprint?.utf8Bytes == 0L) continue
        return duration
    }
    return null
}

private data class InvocationKey(val runId: String, val processSession: String, val invocationId: String)
private data class OwnerKey(val runId: String, val processSession: String, val fence: Long)
private data class RenderPartKey(val owner: OwnerKey, val invocationId: String?, val partId: String)

private fun RunDiagnosticEvent.invocationKey() = invocationId?.let { InvocationKey(runId, processSession, it) }
private fun RunDiagnosticEvent.ownerKey() = fence?.let { OwnerKey(runId, processSession, it) }

private fun firstInvocationEvents(events: List<RunDiagnosticEvent>, kind: RunDiagnosticKind, origin: OutputOrigin? = null): Map<InvocationKey, RunDiagnosticEvent> {
    val first = mutableMapOf<InvocationKey, RunDiagnosticEvent>()
    for (event in events) {
        if (event.kind != kind || (origin != null && event.outputOrigin != origin)) continue
        val key = event.invocationKey() ?: continue
        // A later valid duplicate must not replace the first event's missing or invalid timestamp.
        first.putIfAbsent(key, event)
    }
    return first
}

private fun RunDiagnosticEvent.sameProcess(other: RunDiagnosticEvent) = runId == other.runId && processSession == other.processSession
private fun elapsed(start: RunDiagnosticEvent, end: RunDiagnosticEvent): Long? {
    if (!start.sameProcess(end)) return null
    val first = start.monotonicNanos ?: return null
    val last = end.monotonicNanos ?: return null
    if (last < first) return null
    return runCatching { Math.subtractExact(last, first) / 1_000_000 }.getOrNull()
}
