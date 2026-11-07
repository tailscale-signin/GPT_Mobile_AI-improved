package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogDiagnosticsTest {
    @Test fun broadDeferredCatalogProducesOneBoundedOrderIndependentSummaryWithExactCounts() {
        val omitted = (1..100).associate { "tool_${it.toString().padStart(3, '0')}" to if (it <= 80) "DEFERRED_NOT_REQUESTED" else "CONTEXT_BUDGET" }
        val summary = ToolCatalogDiagnostics.summarizeOmissions("catalog", omitted, 4096)
        assertEquals(summary, ToolCatalogDiagnostics.summarizeOmissions("catalog", omitted.entries.reversed().associate { it.toPair() }, 4096))
        assertTrue(summary.contains("omitted=100"))
        assertTrue(summary.contains("DEFERRED_NOT_REQUESTED=80"))
        assertTrue(summary.contains("CONTEXT_BUDGET=20"))
        assertTrue(summary.contains("detailNamesSampled=true"))
        assertTrue(summary.length < 1024)
        assertFalse(summary.contains("tool_009"))
        assertFalse(summary.contains('\n'))
    }
}
