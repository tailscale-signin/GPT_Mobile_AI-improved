package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticClockReconciliationTest {
    private fun event(id: String, wall: Long, mono: Long) = buildJsonObject {
        put("eventId", id)
        put("time", wall)
        put("monotonicNanos", mono)
        put("processId", 1)
    }

    @Test fun wallClockJumpIsAClockAdjustmentAndNeverANegativeDuration() {
        val result = DiagnosticClockReconciliation.summarize(JsonArray(listOf(event("first", 10000, 1000000000), event("second", 1000, 2000000000))))
        assertEquals("2", result.getValue("anchoredRecords").jsonPrimitive.content)
        assertEquals("second", result.getValue("wallClockAdjustmentEvents").jsonArray.single().jsonPrimitive.content)
        assertEquals("1", result.getValue("clockDiscontinuities").jsonPrimitive.content)
        assertEquals("0", result.getValue("reorderedRecords").jsonPrimitive.content)
    }

    @Test fun oneMillisecondOutOfOrderDeliveryIsReorderingAndDoesNotResetTheClockAnchor() {
        val rows = JsonArray(listOf(event("later", 1001, 1001000000), event("earlier", 1000, 1000000000), event("next", 1002, 1002000000)))
        val result = DiagnosticClockReconciliation.summarize(rows)
        assertEquals("0", result.getValue("clockDiscontinuities").jsonPrimitive.content)
        assertEquals("1", result.getValue("reorderedRecords").jsonPrimitive.content)
        assertEquals(listOf("earlier", "later", "next"), result.getValue("displayOrderEventIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("later", (rows.first() as kotlinx.serialization.json.JsonObject).getValue("eventId").jsonPrimitive.content)
    }
}
