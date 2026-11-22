package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticDrawTimingTest {
    @Test fun receiptAndDrawKeepTheirDistinctOwnedInvocationSpans() {
        val started = event(RunDiagnosticKind.INVOCATION_STARTED, "invocation", 3_000_000)
        val text = event(RunDiagnosticKind.FIRST_TEXT, "invocation", 11_000_000)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 16_000_000)
        val progress = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, null, 5_000_000).copy(outputOrigin = OutputOrigin.PROGRESS_NOTICE)
        val events = listOf(started, progress, text, draw)
        assertEquals(8L, linkedInvocationToFirstTextMillis(events))
        assertEquals(13L, linkedInvocationToDrawMillis(events))
        assertEquals(5L, linkedParsedToDrawMillis(events))
        assertNull(linkedInvocationToFirstTextMillis(listOf(started, text.copy(processSession = "restarted"))))
        assertNull(linkedInvocationToDrawMillis(listOf(started, draw)))
        assertNull(linkedInvocationToFirstTextMillis(listOf(started, text.copy(outputOrigin = OutputOrigin.APPLICATION_REPORT))))
    }

    @Test fun finalReportUsesTerminalCommittedBytesAndTheirMeasuredOwnedPart() {
        val report = finalReport()
        assertEquals(12L, linkedFinalReportToDrawMillis(report))
        val acknowledgement = event(RunDiagnosticKind.FIRST_APPLICATION_REPORT, null, 1_000_000).copy(outputOrigin = OutputOrigin.APPLICATION_REPORT, fence = 2)
        assertEquals(12L, linkedFinalReportToDrawMillis(listOf(acknowledgement) + report))
        // An acknowledgement and a later draw alone do not prove final publication.
        assertNull(linkedFinalReportToDrawMillis(listOf(acknowledgement, report.last())))
    }

    @Test fun finalReportRejectsDifferentFenceProcessPartBytesOriginAndUnmeasuredDraws() {
        val report = finalReport()
        val drawn = report.last()
        val replacements = listOf(
            drawn.copy(fence = 3),
            drawn.copy(processSession = "restarted"),
            drawn.copy(runId = "other"),
            drawn.copy(outputPartId = "4"),
            drawn.copy(outputPartId = null),
            drawn.copy(measured = null),
            drawn.copy(outputOrigin = OutputOrigin.SYSTEM_ERROR),
            drawn.copy(monotonicNanos = 1_000_000)
        )
        replacements.forEach { assertNull(linkedFinalReportToDrawMillis(report.dropLast(1) + it)) }
        val changedInput = report[2].copy(outputFingerprint = OutputFingerprint(12, "b".repeat(64)))
        assertNull(linkedFinalReportToDrawMillis(listOf(report[0], report[1], changedInput, drawn)))
        assertNull(linkedFinalReportToDrawMillis(listOf(report[0].copy(invocationId = "other-owner")) + report.drop(1)))
        assertNull(linkedFinalReportToDrawMillis(report + changedInput.copy(monotonicNanos = 31_000_000)))
        assertNull(linkedFinalReportToDrawMillis(report.filter { it.kind != RunDiagnosticKind.RUN_TERMINAL }))
        assertNull(linkedFinalReportToDrawMillis(report.filter { it.outputBoundary != OutputTraceBoundary.RENDER_INPUT }))
    }

    @Test fun anOlderAppCommitCannotBeCalledFinalAfterDifferentBytesAreCommitted() {
        val report = finalReport()
        val newerCommit = report[0].copy(monotonicNanos = 24_000_000, outputOrigin = OutputOrigin.MODEL_TEXT, outputFingerprint = OutputFingerprint(20, "b".repeat(64)))
        assertNull(linkedFinalReportToDrawMillis(report + newerCommit))
        val laterUnknownCommit = newerCommit.copy(outputOrigin = OutputOrigin.UNKNOWN)
        assertNull(linkedFinalReportToDrawMillis(report + laterUnknownCommit))
    }

    @Test fun drawWithoutInvocationLineageDoesNotPairWithFirstOrLatestProviderRound() {
        val parsed = event(RunDiagnosticKind.FIRST_TEXT, "first", 1_000_000)
        val latest = event(RunDiagnosticKind.FIRST_TEXT, "latest", 9_000_000)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, null, 11_000_000)
        assertNull(linkedParsedToDrawMillis(listOf(parsed, latest, draw)))
        assertNull(linkedParsedToDrawMillis(listOf(parsed, draw.copy(invocationId = "missing"))))
    }

    @Test fun explicitLineageStillRequiresTheSameRunProcessAndValidMonotonicOrder() {
        val started = event(RunDiagnosticKind.INVOCATION_STARTED, "invocation", 0)
        val parsed = event(RunDiagnosticKind.FIRST_TEXT, "invocation", 1_000_000)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 11_000_000)
        assertEquals(10L, linkedParsedToDrawMillis(listOf(started, parsed, draw)))
        assertNull(linkedParsedToDrawMillis(listOf(parsed, draw)))
        assertNull(linkedParsedToDrawMillis(listOf(started, parsed, draw.copy(runId = "other-run"))))
        assertNull(linkedParsedToDrawMillis(listOf(started, parsed, draw.copy(processSession = "restarted"))))
        assertNull(linkedParsedToDrawMillis(listOf(started, parsed, draw.copy(monotonicNanos = 0))))
        assertNull(linkedParsedToDrawMillis(listOf(started, parsed, draw.copy(measured = null))))
    }

    @Test fun precheckErrorsProgressAndLegacyUnclassifiedDrawsNeverBecomeModelLatency() {
        val started = event(RunDiagnosticKind.INVOCATION_STARTED, "invocation", 0)
        val parsed = event(RunDiagnosticKind.FIRST_TEXT, "invocation", 1_000_000)
        for (origin in listOf(OutputOrigin.SYSTEM_ERROR, OutputOrigin.PROGRESS_NOTICE, OutputOrigin.UNKNOWN)) {
            val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 11_000_000).copy(outputOrigin = origin)
            assertNull(linkedParsedToDrawMillis(listOf(started, parsed, draw)))
            assertNull(linkedParsedToDrawMillis(listOf(draw.copy(invocationId = null))))
        }
        val progress = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, null, 2_000_000).copy(outputOrigin = OutputOrigin.PROGRESS_NOTICE)
        val model = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 11_000_000)
        assertEquals(10L, linkedParsedToDrawMillis(listOf(started, parsed, progress, model)))
    }

    private fun event(kind: RunDiagnosticKind, invocation: String?, nanos: Long) = RunDiagnosticEvent(
        eventId = kind.name,
        processSession = "process",
        runId = "run",
        streamId = "process:run",
        sequence = 1,
        capturedAtMillis = 1,
        monotonicNanos = nanos,
        kind = kind,
        role = RunDiagnosticRole.PRIMARY,
        invocationId = invocation,
        outputOrigin = OutputOrigin.MODEL_TEXT,
        measured = true
    )

    private fun finalReport(): List<RunDiagnosticEvent> {
        val committed = event(RunDiagnosticKind.OUTPUT_CHECKPOINT, null, 20_000_000).copy(
            fence = 2,
            outputOrigin = OutputOrigin.APPLICATION_REPORT,
            outputBoundary = OutputTraceBoundary.COMMITTED,
            outputRepresentation = OutputTextRepresentation.VISIBLE_UTF8,
            outputFingerprint = OutputFingerprint(12, "a".repeat(64))
        )
        val terminal = event(RunDiagnosticKind.RUN_TERMINAL, null, 25_000_000).copy(fence = 2)
        val input = committed.copy(monotonicNanos = 30_000_000, outputBoundary = OutputTraceBoundary.RENDER_INPUT, outputRepresentation = OutputTextRepresentation.DISPLAY_FILTERED_MARKDOWN_UTF8, outputPartId = "3")
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, null, 32_000_000).copy(fence = 2, outputPartId = "3", outputOrigin = OutputOrigin.APPLICATION_REPORT)
        return listOf(committed, terminal, input, draw)
    }
}
