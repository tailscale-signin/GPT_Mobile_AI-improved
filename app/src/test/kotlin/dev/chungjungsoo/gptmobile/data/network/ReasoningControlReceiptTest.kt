package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningControlReceiptTest {
    @Test fun requestedDisableDoesNotInventATransmittedControl() {
        val receipt = ReasoningControlReceipt.fromPayload("""{"model":"automatic"}""", allowReasoning = false)
        assertTrue(receipt.disableRequested)
        assertNull(receipt.transmittedControl)
        assertFalse(receipt.transmitsDisable)
        assertEquals("NOT_TRANSMITTED", receipt.providerSupport)
    }

    @Test fun effortReductionAndDisableAreDifferentWireCapabilities() {
        val effort = ReasoningControlReceipt.fromPayload("""{"reasoning_effort":"low"}""", allowReasoning = false)
        assertEquals("reasoning_effort=low", effort.transmittedControl)
        assertFalse(effort.transmitsDisable)
        assertEquals("EFFORT_ONLY", effort.providerSupport)
        val disable = ReasoningControlReceipt.fromPayload("""{"reasoning":{"enabled":false}}""", allowReasoning = false)
        assertTrue(disable.transmitsDisable)
        assertEquals("UNVERIFIED", disable.providerSupport)
    }
}
