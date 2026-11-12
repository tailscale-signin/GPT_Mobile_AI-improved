package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCommandReconciliationTest {
    private val original = GatewaySubmissionRecord("command", 1, 2, 3, "run", "connection", "http://original/v1", "profile", "fingerprint", "frozen request", "OUTCOME_UNKNOWN", createdAtMillis = 1)

    @Test fun missingReceiptBackoffSurvivesReconstructionAndNeverMakesReplayEligible() {
        var row = original
        var time = 1_000_000L
        for (delay in listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L)) {
            assertTrue(GatewayCommandReconciliation.due(row, time))
            val saved = GatewayCommandReconciliation.deferred(row, time)
            row = original.copy(receipt = saved.receipt)
            assertFalse(GatewayCommandReconciliation.due(row, time + delay - 1))
            assertTrue(GatewayCommandReconciliation.due(row, time + delay))
            assertEquals(original.copy(receipt = row.receipt), row)
            assertEquals("OUTCOME_UNKNOWN", row.state)
            time += delay
        }
    }

    @Test fun wallClockCorrectionAllowsAnotherReadWithoutDiscardingTheCommandFence() {
        val deferred = GatewayCommandReconciliation.deferred(original, 1_000_000)
        assertTrue(GatewayCommandReconciliation.due(deferred, 900_000))
        assertTrue(GatewayCommandReconciliation.due(deferred, 50_000_000))
        assertEquals("OUTCOME_UNKNOWN", deferred.state)
        assertTrue(GatewayCommandReconciliation.due(original.copy(receipt = "legacy receipt"), 1_000_000))
    }

    @Test fun persistedCheckpointIncludesItsTypeAndRetainsEarlierMarkerlessBackoff() {
        val saved = GatewayCommandReconciliation.deferred(original, 1_000_000)
        val checkpoint = Json.parseToJsonElement(requireNotNull(saved.receipt)).jsonObject
        assertEquals(JsonPrimitive("gateway_command_lookup_v1"), checkpoint["kind"])
        val legacy = original.copy(receipt = JsonObject(checkpoint - "kind").toString())
        assertFalse(GatewayCommandReconciliation.due(legacy, 1_009_999))
        assertTrue(GatewayCommandReconciliation.due(legacy, 1_010_000))
        val upgraded = GatewayCommandReconciliation.deferred(legacy, 1_010_000)
        assertEquals(JsonPrimitive("gateway_command_lookup_v1"), Json.parseToJsonElement(requireNotNull(upgraded.receipt)).jsonObject["kind"])
        assertFalse(GatewayCommandReconciliation.due(upgraded, 1_029_999))
        assertTrue(GatewayCommandReconciliation.due(upgraded, 1_030_000))
        assertEquals(original.copy(receipt = upgraded.receipt), upgraded)
    }
}
