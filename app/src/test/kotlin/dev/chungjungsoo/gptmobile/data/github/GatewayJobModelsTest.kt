package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GatewayJobModelsTest {
    @Test fun elapsedIncludesQueueAndWaitButFreezesAtServerFinish() {
        assertEquals(25_000L, gatewayElapsedMillis(100.0, null, 120.0, 5000))
        assertEquals(15_000L, gatewayElapsedMillis(100.0, 115.0, 200.0, 9000))
        assertEquals(0L, gatewayElapsedMillis(200.0, null, 100.0, -1000))
    }

    @Test fun sameJobIdOnDifferentAccountOrInstallationNeverCollides() {
        val key = gatewayIdentityKey("gateway", "owner", "engineering", "eng_1")
        assertNotEquals(key, gatewayIdentityKey("other", "owner", "engineering", "eng_1"))
        assertNotEquals(key, gatewayIdentityKey("gateway", "other", "engineering", "eng_1"))
        assertNotEquals(gatewayIdentityKey("a|b", "c"), gatewayIdentityKey("a", "b|c"))
    }

    @Test fun queuedAndApprovalStatesAreNotDisplayedAsGenerating() {
        assertEquals(GatewayJobGroup.QUEUED, gatewayJobGroup("queued"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobGroup("needs_attention"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobGroup("WAITING_FOR_DEVICE"))
        assertEquals(GatewayJobGroup.RUNNING, gatewayJobGroup("WAIT_CI"))
        assertEquals(GatewayJobGroup.RECENT, gatewayJobGroup("cancelled"))
    }

    @Test fun unknownAndCancellingStatesDoNotClaimTheWorkerIsRunning() {
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobGroup("future_unrecognized_state"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobGroup("CANCELLING"))
        assertEquals(GatewayJobGroup.NEEDS_ATTENTION, gatewayJobGroup(""))
    }
}
