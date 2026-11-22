package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun incompleteBoundedHistoriesDoNotRescanTheJournalForEveryEvent() {
        val report = finalReport()
        val histories = mapOf(
            "missing invocation starts" to List(4096) { event(RunDiagnosticKind.FIRST_TEXT, "invocation-$it", it.toLong()) },
            "missing model receipts" to listOf(event(RunDiagnosticKind.INVOCATION_STARTED, "invocation", 0)) +
                List(4095) { event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", it.toLong()) },
            "missing terminal owners" to List(4096) { report.last().copy(fence = it.toLong()) },
            "missing commits" to listOf(report[1]) + List(4095) { report.last().copy(outputPartId = "$it") },
            "missing render inputs" to report.take(2) + List(4094) { report.last().copy(outputPartId = "$it") }
        )
        for ((missing, history) in histories) {
            val events = CountingEvents(history)
            assertNull(missing, linkedInvocationToFirstTextMillis(events))
            assertNull(missing, linkedInvocationToDrawMillis(events))
            assertNull(missing, linkedFinalReportToDrawMillis(events))
            assertTrue("$missing: ${events.reads} event reads", events.reads <= 12L * history.size)
        }
    }

    @Test fun unsortedHistoriesKeepMeasuredLineageAndFirstDrawOrder() {
        val started = event(RunDiagnosticKind.INVOCATION_STARTED, "invocation", 3_000_000)
        val text = event(RunDiagnosticKind.FIRST_TEXT, "invocation", 11_000_000)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 16_000_000)
        val events = listOf(draw, text, started)
        assertEquals(8L, linkedInvocationToFirstTextMillis(events))
        assertEquals(13L, linkedInvocationToDrawMillis(events))
        val earlierDraw = draw.copy(monotonicNanos = 15_000_000)
        assertEquals(13L, linkedInvocationToDrawMillis(events + earlierDraw))
        assertEquals(12L, linkedInvocationToDrawMillis(listOf(earlierDraw) + events))
        val report = finalReport()
        assertEquals(12L, linkedFinalReportToDrawMillis(report.reversed()))
        val earlierReportDraw = report.last().copy(monotonicNanos = 31_000_000)
        assertEquals(12L, linkedFinalReportToDrawMillis(report + earlierReportDraw))
        assertEquals(11L, linkedFinalReportToDrawMillis(listOf(earlierReportDraw) + report))
    }

    @Test fun firstDuplicateInvocationMetadataRemainsAuthoritative() {
        val started = event(RunDiagnosticKind.INVOCATION_STARTED, "invocation", 3_000_000)
        val text = event(RunDiagnosticKind.FIRST_TEXT, "invocation", 11_000_000)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 16_000_000)
        for (firstStart in listOf(started.copy(monotonicNanos = null), started.copy(monotonicNanos = 12_000_000))) {
            val events = listOf(draw, firstStart, started, text)
            assertNull(linkedInvocationToFirstTextMillis(events))
            assertNull(linkedInvocationToDrawMillis(events))
        }
        val missingTextTime = text.copy(monotonicNanos = null)
        val events = listOf(draw, missingTextTime, started, text)
        // Receipt timing may continue to a later receipt; draw attribution uses the first one.
        assertEquals(8L, linkedInvocationToFirstTextMillis(events))
        assertNull(linkedInvocationToDrawMillis(events))
    }

    @Test fun firstTerminalAndEqualTimestampCommitOrderRemainAuthoritative() {
        val report = finalReport()
        val committed = report[0]
        val otherOrigin = committed.copy(outputOrigin = OutputOrigin.MODEL_TEXT)
        assertEquals(12L, linkedFinalReportToDrawMillis(listOf(committed, otherOrigin) + report.drop(1)))
        assertNull(linkedFinalReportToDrawMillis(listOf(otherOrigin) + report))
        assertNull(linkedFinalReportToDrawMillis(listOf(report[1].copy(monotonicNanos = null)) + report))
        assertNull(linkedFinalReportToDrawMillis(listOf(report[1].copy(monotonicNanos = 18_000_000)) + report))
        val laterTerminal = report[1].copy(monotonicNanos = 40_000_000)
        val committedAfterDraw = committed.copy(monotonicNanos = 35_000_000)
        assertNull(linkedFinalReportToDrawMillis(listOf(committed, laterTerminal, report[2], report.last(), committedAfterDraw)))
    }

    @Test fun renderInputTimestampTiesAndLatestBytesKeepTheirMeaning() {
        val report = finalReport()
        val input = report[2]
        val differentBytes = input.copy(outputFingerprint = OutputFingerprint(12, "b".repeat(64)))
        assertEquals(12L, linkedFinalReportToDrawMillis(report + differentBytes))
        assertNull(linkedFinalReportToDrawMillis(listOf(differentBytes) + report))
        assertNull(linkedFinalReportToDrawMillis(listOf(input.copy(outputFingerprint = null)) + report))
        assertNull(linkedFinalReportToDrawMillis(report + differentBytes.copy(monotonicNanos = 31_000_000)))
        assertEquals(12L, linkedFinalReportToDrawMillis(report + differentBytes.copy(monotonicNanos = 33_000_000)))
        // Only application-report inputs compete for this attribution; their bytes still must match.
        assertEquals(12L, linkedFinalReportToDrawMillis(report + differentBytes.copy(monotonicNanos = 31_000_000, outputOrigin = OutputOrigin.MODEL_TEXT)))
        assertNull(linkedFinalReportToDrawMillis(report.take(2) + input.copy(monotonicNanos = 19_000_000) + report.last()))
    }

    @Test fun overflowingSpansCannotBeReplacedWithALaterValidDraw() {
        val started = event(RunDiagnosticKind.INVOCATION_STARTED, "overflow", Long.MIN_VALUE)
        val text = event(RunDiagnosticKind.FIRST_TEXT, "overflow", -1)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "overflow", 0)
        val valid = listOf(
            event(RunDiagnosticKind.INVOCATION_STARTED, "valid", 3_000_000),
            event(RunDiagnosticKind.FIRST_TEXT, "valid", 11_000_000),
            event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "valid", 16_000_000)
        )
        assertNull(linkedInvocationToDrawMillis(listOf(started, text, draw) + valid))
        val report = finalReport()
        assertEquals(12L, linkedFinalReportToDrawMillis(listOf(report[0].copy(monotonicNanos = Long.MIN_VALUE)) + report))
        assertNull(linkedFinalReportToDrawMillis(listOf(report[0].copy(monotonicNanos = Long.MIN_VALUE)) + report.drop(1)))
    }

    private class CountingEvents(private val events: List<RunDiagnosticEvent>) : AbstractList<RunDiagnosticEvent>() {
        var reads = 0L
        override val size: Int get() = events.size
        override fun get(index: Int): RunDiagnosticEvent {
            reads++
            return events[index]
        }
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
