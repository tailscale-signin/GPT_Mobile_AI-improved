package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCoalescerTest {
    @Test fun noisyPolicyDecisionsAreCountedAndErrorsRemainIndividual() {
        var now = 0L
        val coalescer = DiagnosticCoalescer { now }
        val line = "TOOL_AUTHORIZATION_HIDDEN PERSONAL_DATA_LOCAL_ONLY"
        assertEquals(1, coalescer.accept("ToolCatalog", line, "I").size)
        repeat(25) { assertTrue(coalescer.accept("ToolCatalog", line, "I").isEmpty()) }
        now = 15_000
        assertTrue(coalescer.accept("ToolCatalog", line, "I").single().second.contains("count=26"))
        repeat(3) { assertEquals(1, coalescer.accept("Voice", "recognition rejected", "E").size) }
        coalescer.accept("ToolCatalog", line, "I")
        assertTrue(coalescer.drain().single().second.contains("count=1"))
        assertTrue(coalescer.drain().isEmpty())
    }
}
