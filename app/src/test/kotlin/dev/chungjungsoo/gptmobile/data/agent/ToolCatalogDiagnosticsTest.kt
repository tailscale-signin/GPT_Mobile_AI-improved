package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogDiagnosticsTest {
    @Test fun unchangedRoundsAreSuppressedWhileConfigurationChangesAndClearsRemainVisible() {
        val window = ToolCatalogDiagnostics.Window(now = { 100L })
        val first = mapOf("read" to "DEFERRED_NOT_REQUESTED", "write" to "CONTEXT_BUDGET")
        assertTrue(requireNotNull(window.observe("catalog", first)).contains("detailAdded=2"))
        repeat(31) { assertNull(window.observe("catalog", first, schemaAllowance = it.toLong())) }
        assertTrue(requireNotNull(window.observe("catalog", first)).contains("repeatedRounds=32"))
        val next = mapOf("write" to "DEFERRED_NOT_REQUESTED", "status" to "CONTEXT_BUDGET")
        val change = requireNotNull(window.observe("catalog", next))
        assertTrue(change.contains("detailAdded=1"))
        assertTrue(change.contains("detailRemoved=1"))
        assertTrue(change.contains("detailReasonChanged=1"))
        val cleared = requireNotNull(window.observe("catalog", emptyMap()))
        assertTrue(cleared.contains("omitted=0"))
        assertTrue(cleared.contains("detailRemoved=2"))
        assertEquals(JsonObject(emptyMap()), window.snapshot().single()["omissions"])
    }

    @Test fun exportedInventoryHasExactCountsWithExplicitDetailAndRetentionBounds() {
        val window = ToolCatalogDiagnostics.Window(maxStages = 2, maxTools = 2, now = { 1234 })
        window.observe("initial", mapOf("c" to "BUDGET"), inventoryNames = listOf("a", "b", "c", "a"))
        val first = window.snapshot().single()
        assertEquals(JsonPrimitive(3), first["inventoryCount"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), first["inventoryNames"])
        assertEquals(JsonPrimitive(true), first["detailsTruncated"])
        assertEquals(JsonPrimitive(1234L), first["observedAtMillis"])
        window.observe("provider", mapOf("read" to "BUDGET"))
        window.observe("dynamic", mapOf("new" to "BUDGET"))
        assertEquals(listOf(JsonPrimitive("provider"), JsonPrimitive("dynamic")), window.snapshot().map { it["stage"] })
        assertTrue(window.snapshot().all { it["stagesEvicted"] == JsonPrimitive(1L) })
        window.clear()
        assertTrue(window.snapshot().isEmpty())
    }

    @Test fun inventoryChangesAreVisibleEvenWhenNoToolIsOmitted() {
        val window = ToolCatalogDiagnostics.Window()
        assertTrue(requireNotNull(window.observe("catalog", emptyMap(), inventoryNames = listOf("read"))).contains("omitted=0"))
        assertNull(window.observe("catalog", emptyMap(), inventoryNames = listOf("read")))
        assertTrue(requireNotNull(window.observe("catalog", emptyMap(), inventoryNames = listOf("read", "write"))).contains("repeatedRounds=0"))
        assertEquals(JsonPrimitive(2), window.snapshot().single()["inventoryCount"])
    }

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
