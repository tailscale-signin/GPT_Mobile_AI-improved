package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
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
}
