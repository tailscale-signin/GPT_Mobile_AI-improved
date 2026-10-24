package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalCaptureSummaryTest {
    @Test fun disabledEmptyPartialAvailableAndStorageFailureHaveDifferentMeanings() {
        fun status(events: Int, enabled: Boolean, dropped: Int = 0, failed: Boolean = false) = JournalCaptureSummary.describe(
            buildJsonObject {
                put("captureEnabled", enabled)
                put("droppedEvents", dropped)
                put("storageFailed", failed)
            },
            events
        ).getValue("availability").jsonPrimitive.content
        assertEquals("DISABLED", status(0, false))
        assertEquals("EMPTY", status(0, true))
        assertEquals("PARTIAL", status(0, true, dropped = 2))
        assertEquals("PARTIAL", status(3, true, dropped = 1))
        assertEquals("AVAILABLE", status(3, true))
        assertEquals("AVAILABLE", status(3, false))
        assertEquals("STORAGE_FAILED", status(0, false, failed = true))
    }

    @Test fun legacyZeroEventsRemainEmptyWithUnknownPolicyAndCounterScopeIsExplicit() {
        val legacy = JournalCaptureSummary.describe(null, 0)
        assertEquals("EMPTY", legacy.getValue("availability").jsonPrimitive.content)
        assertEquals("false", legacy.getValue("policyKnown").jsonPrimitive.content)
        assertEquals("legacy_unspecified", legacy.getValue("counterScope").jsonPrimitive.content)
        val disabled = JournalCaptureSummary.describe(null, 0, buildJsonObject { put("diagnosticsEnabled", false) })
        assertEquals("DISABLED", disabled.getValue("availability").jsonPrimitive.content)
    }
}
