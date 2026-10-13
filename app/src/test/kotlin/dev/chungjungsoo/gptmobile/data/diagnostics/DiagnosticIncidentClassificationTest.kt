package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIncidentClassificationTest {
    @Test fun `durable crash recorded at info severity is still a critical issue`() {
        val result = classifyDiagnostic("I", "AppCrash", "eventId=abc · type=java.lang.IndexOutOfBoundsException · thread=main")
        assertTrue(result.actionable)
        assertEquals("critical", result.severity)
    }

    @Test fun `current rendering counters and Android skipped frames count as performance issues`() {
        assertTrue(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=120 · jankFrames=8").actionable)
        assertFalse(classifyDiagnostic("I", "Rendering", "FRAME_TIMING · frames=120 · jankFrames=0").actionable)
        assertEquals("performance", classifyDiagnostic("I", "Android", "I/Choreographer: Skipped 35 frames! The application may be doing too much work on its main thread.").category)
    }
}
