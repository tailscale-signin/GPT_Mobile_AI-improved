package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticDrawTimingTest {
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
}
