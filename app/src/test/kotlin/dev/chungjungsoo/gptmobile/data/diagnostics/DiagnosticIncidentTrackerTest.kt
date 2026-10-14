package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIncidentTrackerTest {
    @Test fun traceRowsKeepOneOccurrenceButANewCrashRemainsDistinct() {
        val tracker = DiagnosticIncidentTracker()
        val rows = (0 until 142).map { i -> tracker.accept("2026-10-14T02:05:45.758Z F/DEBUG: ${if (i == 0) "Fatal signal 6" else "native frame $i"}", "e$i", null) }
        assertEquals(142, rows.size)
        assertEquals(1, rows.count { it.first })
        assertEquals(1, rows.map { it.id }.distinct().size)
        val later = tracker.accept("2026-10-14T02:06:00Z F/DEBUG: Fatal signal 6", "later", null)
        assertTrue(later.first)
        assertEquals("later", later.id)
    }

    @Test fun optionalCompatibilityProbeIsNotAUserFailureAndContinuationsStayAssociated() {
        assertFalse(classifyDiagnostic("W", "MCP", "DISCOVERY_NEGOTIATION · status=405 · outcome=legacy_fallback").actionable)
        val tracker = DiagnosticIncidentTracker()
        tracker.accept("2026-10-14T02:05:45Z E/AppCrash: type=java.lang.IllegalStateException", "crash", null)
        assertEquals(DiagnosticIncidentTracker.Association("crash", false), tracker.accept("  at app.frame()", "frame", "crash"))
        assertTrue(classifyDiagnostic("I", "Tool", "outcome=FAILED").actionable)
    }
}
