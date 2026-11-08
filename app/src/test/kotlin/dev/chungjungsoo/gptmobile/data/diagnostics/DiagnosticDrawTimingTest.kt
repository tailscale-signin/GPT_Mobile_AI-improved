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
        val parsed = event(RunDiagnosticKind.FIRST_TEXT, "invocation", 1_000_000)
        val draw = event(RunDiagnosticKind.FIRST_VISIBLE_TEXT, "invocation", 11_000_000)
        assertEquals(10L, linkedParsedToDrawMillis(listOf(parsed, draw)))
        assertNull(linkedParsedToDrawMillis(listOf(parsed, draw.copy(runId = "other-run"))))
        assertNull(linkedParsedToDrawMillis(listOf(parsed, draw.copy(processSession = "restarted"))))
        assertNull(linkedParsedToDrawMillis(listOf(parsed, draw.copy(monotonicNanos = 0))))
        assertNull(linkedParsedToDrawMillis(listOf(parsed, draw.copy(measured = null))))
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
        measured = true
    )
}
